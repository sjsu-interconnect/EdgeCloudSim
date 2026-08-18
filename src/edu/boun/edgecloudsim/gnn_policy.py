"""
gnn_policy.py

GNN feature extractor for SB3 MaskablePPO.

Graph structure:
  Node 0: task node for the current task being scheduled
  Next nodes: one node per edge/cloud datacenter
  Last node: global system state

Each node has NUM_NODE_FEATURES=18 features: 15 content features and 3 role features.
Observation is flattened as node features followed by the adjacency matrix.
"""

import numpy as np
import torch
import torch.nn as nn
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
import gymnasium as gym


# ── Constants ──────────────────────────────────────────────────────────────────
NUM_TASK_TYPES = 7
TASK_TYPES = {
    "vae_encode": 0,
    "unet_denoise": 1,
    "sampler": 2,
    "vae_decode": 3,
    "lora_load": 4,
    "controlnet_load": 5,
    "base_model_load": 6,
}

NUM_NODE_CONTENT_FEATURES = 15
NUM_ROLE_FEATURES = 3
NUM_NODE_FEATURES = NUM_NODE_CONTENT_FEATURES + NUM_ROLE_FEATURES

ROLE_TASK = np.array([1.0, 0.0, 0.0], dtype=np.float32)
ROLE_DC = np.array([0.0, 1.0, 0.0], dtype=np.float32)
ROLE_GLOBAL = np.array([0.0, 0.0, 1.0], dtype=np.float32)

# Reservation waits can grow with workload size. This is only a soft reference
# scale for log normalization, not a hard cap.
WAIT_TIME_SCALE = 600_000.0

DC_SHORTEST_WAIT_TIME = 7
DC_BUSY_VM = 8
DC_COST_PER_BW = 9
DC_UPLOAD_DELAY = 10
DC_DOWNLOAD_DELAY = 11
DC_PROCESSING_TIME = 12

GLOBAL_EDGE_UTILIZATION = 0
GLOBAL_CLOUD_UTILIZATION = 1
GLOBAL_BUDGET_USED = 2
GLOBAL_REMAINING_BUDGET = 3
GLOBAL_ACTIVE_DAGS = 4


# Normalization helper functions
def log_norm(value: float, max_expected: float = 5000.0) -> float:
    """Log normalization — handles any scale without hard clipping."""
    import math
    return math.log1p(max(0.0, value)) / math.log1p(max_expected)


def clip_norm(value: float, divisor: float) -> float:
    return float(np.clip(value / divisor, 0.0, 1.0))


def safe_hash(value: str) -> float:
    return (abs(hash(value)) % 10000) / 10000.0


def reservation_wait_stats(dc_vms: list) -> tuple[float, float]:
    """Return shortest VM wait and busy VM fraction for one datacenter."""
    if not dc_vms:
        return 0.0, 0.0

    waiting_times = [
        float(vm.get("estimatedWaitTimeMs", vm.get("reservedWaitMs", 0.0)))
        for vm in dc_vms
    ]
    shortest_wait_time = min(waiting_times)
    busy_fraction = sum(1 for waiting_time in waiting_times if waiting_time > 0.0) / len(waiting_times)
    return shortest_wait_time, busy_fraction


# Node features
def task_node_features(task: dict, budget: dict) -> np.ndarray:
    """15 content features for the task node."""
    task_type_str = task.get("taskType", "vae_encode")
    task_type_idx = TASK_TYPES.get(task_type_str, 0)
    task_type_onehot = np.zeros(NUM_TASK_TYPES, dtype=np.float32)
    task_type_onehot[task_type_idx] = 1.0

    features = np.zeros(NUM_NODE_CONTENT_FEATURES, dtype=np.float32)
    features[0] = clip_norm(task.get("mi", 0.0), 13_000_000.0)
    features[1] = clip_norm(task.get("dataSizeBytes", 0.0), 1_073_741_824.0)
    features[2:9] = task_type_onehot  # 7 values
    features[9] = float(np.clip(budget.get("budgetFractionUsed", 0.0), 0.0, 1.0))
    features[10] = safe_hash(task.get("dagId", ""))
    return features


def dc_node_features(vm_list: list, dc_id: int, is_edge: bool) -> np.ndarray:
    """15 content features for a DC node."""
    dc_vms = [vm for vm in vm_list if int(vm["dcId"]) == dc_id]

    if dc_vms:
        total_mips = sum(float(vm["availableMips"]) for vm in dc_vms)
        avg_util = sum(float(vm["utilization"]) for vm in dc_vms) / len(dc_vms)
        total_queue = sum(int(vm["queueLen"]) for vm in dc_vms)
        cost_per_sec = float(dc_vms[0].get("costPerSec", 0.0))
        cost_per_bw = float(dc_vms[0].get("costPerBw", 0.0))
        upload_delay_ms = min(float(vm.get("estimatedUploadDelayMs", 0.0)) for vm in dc_vms)
        download_delay_ms = min(float(vm.get("estimatedDownloadDelayMs", 0.0)) for vm in dc_vms)
        processing_time_ms = min(float(vm.get("estimatedProcessingTimeMs", 0.0)) for vm in dc_vms)
    else:
        total_mips = avg_util = total_queue = cost_per_sec = cost_per_bw = 0.0
        upload_delay_ms = download_delay_ms = processing_time_ms = 0.0

    shortest_wait_time, busy_vm_fraction = reservation_wait_stats(dc_vms)

    # Edge and cloud have very different MIPS scales.
    mips_divisor = 20_000.0 if is_edge else 12_800_000.0

    features = np.zeros(NUM_NODE_CONTENT_FEATURES, dtype=np.float32)
    features[0] = clip_norm(total_mips, mips_divisor)
    features[1] = float(np.clip(avg_util, 0.0, 1.0))
    features[2] = log_norm(total_queue, 5000.0)
    features[3] = 1.0 if is_edge else 0.0
    features[4] = 0.0 if is_edge else 1.0
    features[5] = dc_id / 8.0  # normalized DC id
    features[6] = clip_norm(cost_per_sec, 1e-4)
    features[DC_SHORTEST_WAIT_TIME] = log_norm(shortest_wait_time, WAIT_TIME_SCALE)
    features[DC_BUSY_VM] = float(np.clip(busy_vm_fraction, 0.0, 1.0))
    features[DC_COST_PER_BW] = clip_norm(cost_per_bw, 1e-9)
    features[DC_UPLOAD_DELAY] = log_norm(upload_delay_ms, 10_000.0)
    features[DC_DOWNLOAD_DELAY] = log_norm(download_delay_ms, 10_000.0)
    features[DC_PROCESSING_TIME] = log_norm(processing_time_ms, 60_000.0)
    return features


def global_node_features(cluster: dict, budget: dict, queue: dict) -> np.ndarray:
    """15 content features for the global node."""
    edge = cluster.get("edge", {})
    cloud = cluster.get("cloud", {})

    features = np.zeros(NUM_NODE_CONTENT_FEATURES, dtype=np.float32)
    features[GLOBAL_EDGE_UTILIZATION] = float(np.clip(edge.get("utilization", 0.0), 0.0, 1.0))
    features[GLOBAL_CLOUD_UTILIZATION] = float(np.clip(cloud.get("utilization", 0.0), 0.0, 1.0))
    features[GLOBAL_BUDGET_USED] = float(
        np.clip(budget.get("budgetFractionUsed", 0.0), 0.0, 1.0)
    )
    features[GLOBAL_REMAINING_BUDGET] = clip_norm(budget.get("remainingBudget", 0.0), 1800.0)
    features[GLOBAL_ACTIVE_DAGS] = clip_norm(queue.get("activeDagCount", 0), 1000.0)
    return features


def build_graph_obs(state: dict, num_edge_dc: int = 8, num_cloud_dc: int = 1) -> np.ndarray:
    """
    Build flattened graph observation from state dict.
    Returns array of shape (obs_dim,) = (num_nodes * 18 + num_nodes * num_nodes,)
    """
    num_dc = num_edge_dc + num_cloud_dc
    num_nodes = num_dc + 2          # DCs + task node + global node
    task_idx = 0
    global_idx = num_nodes - 1

    task = state.get("task", {})
    cluster = state.get("cluster", {})
    budget = state.get("budget", {})
    queue = state.get("queue", {})

    edge_vms = cluster.get("edgeVms", [])
    cloud_vms = cluster.get("cloudVms", [])

    node_features = np.zeros((num_nodes, NUM_NODE_FEATURES), dtype=np.float32)
    adjacency = np.eye(num_nodes, dtype=np.float32)  # self-loops

    node_features[task_idx, :NUM_NODE_CONTENT_FEATURES] = task_node_features(task, budget)
    node_features[task_idx, NUM_NODE_CONTENT_FEATURES:] = ROLE_TASK

    for dc_id in range(num_edge_dc):
        node_idx = dc_id + 1
        node_features[node_idx, :NUM_NODE_CONTENT_FEATURES] = dc_node_features(edge_vms, dc_id, is_edge=True)
        node_features[node_idx, NUM_NODE_CONTENT_FEATURES:] = ROLE_DC
        adjacency[task_idx, node_idx] = 1.0
        adjacency[node_idx, task_idx] = 1.0
        adjacency[global_idx, node_idx] = 1.0
        adjacency[node_idx, global_idx] = 1.0

    cloud_node_idx = num_edge_dc + 1
    node_features[cloud_node_idx, :NUM_NODE_CONTENT_FEATURES] = dc_node_features(cloud_vms, 0, is_edge=False)
    node_features[cloud_node_idx, NUM_NODE_CONTENT_FEATURES:] = ROLE_DC
    adjacency[task_idx, cloud_node_idx] = 1.0
    adjacency[cloud_node_idx, task_idx] = 1.0
    adjacency[global_idx, cloud_node_idx] = 1.0
    adjacency[cloud_node_idx, global_idx] = 1.0

    node_features[global_idx, :NUM_NODE_CONTENT_FEATURES] = global_node_features(cluster, budget, queue)
    node_features[global_idx, NUM_NODE_CONTENT_FEATURES:] = ROLE_GLOBAL
    adjacency[task_idx, global_idx] = 1.0
    adjacency[global_idx, task_idx] = 1.0

    return np.concatenate([node_features.flatten(), adjacency.flatten()])


def graph_obs_dim(num_edge_dc: int = 8, num_cloud_dc: int = 1) -> int:
    num_nodes = num_edge_dc + num_cloud_dc + 2
    return (num_nodes * NUM_NODE_FEATURES) + (num_nodes * num_nodes)


# GNN layers
class MessagePassingBlock(nn.Module):
    """Single GNN message passing layer with residual connection."""

    def __init__(self, hidden_dim: int) -> None:
        super().__init__()
        self.self_projection = nn.Linear(hidden_dim, hidden_dim)
        self.neighbor_projection = nn.Linear(hidden_dim, hidden_dim)
        self.layer_norm = nn.LayerNorm(hidden_dim)

    def forward(self, node_embeddings: torch.Tensor, normalized_adjacency: torch.Tensor) -> torch.Tensor:
        neighbor_embeddings = torch.bmm(normalized_adjacency, node_embeddings)
        updated_embeddings = torch.relu(
            self.self_projection(node_embeddings)
            + self.neighbor_projection(neighbor_embeddings)
        )
        return self.layer_norm(node_embeddings + updated_embeddings)


# ── SB3 feature extractor ──────────────────────────────────────────────────────
class GNNExtractor(BaseFeaturesExtractor):
    """
    GNN-based feature extractor for SB3 MaskablePPO.

    Takes flattened graph observation (node_features + adjacency) and returns
    a feature vector by concatenating task, mean-DC, and global node embeddings.

    Usage in rl_agent.py:
        policy_kwargs = dict(
            features_extractor_class=GNNExtractor,
            features_extractor_kwargs=dict(
                num_edge_dc=8,
                num_cloud_dc=1,
                hidden_dim=128,
                n_layers=2,
            ),
        )
        model = MaskablePPO("MlpPolicy", env, policy_kwargs=policy_kwargs, verbose=1)
    """

    def __init__(
        self,
        observation_space: gym.Space,
        num_edge_dc: int = 8,
        num_cloud_dc: int = 1,
        hidden_dim: int = 128,
        n_layers: int = 2,
    ) -> None:
        self.num_edge_dc = num_edge_dc
        self.num_cloud_dc = num_cloud_dc
        self.num_dc = num_edge_dc + num_cloud_dc
        self.num_nodes = self.num_dc + 2
        self.hidden_dim = hidden_dim

        # Output: [task_h | mean_dc_h | global_h] = 3 × hidden_dim
        features_dim = hidden_dim * 3
        super().__init__(observation_space, features_dim=features_dim)

        self.input_proj = nn.Linear(NUM_NODE_FEATURES, hidden_dim)
        self.gnn_layers = nn.ModuleList([
            MessagePassingBlock(hidden_dim) for _ in range(max(1, n_layers))
        ])

    @staticmethod
    def _normalize_adjacency(adjacency: torch.Tensor) -> torch.Tensor:
        node_degrees = adjacency.sum(dim=-1)
        inverse_sqrt_degrees = torch.rsqrt(node_degrees.clamp(min=1.0))
        return adjacency * inverse_sqrt_degrees.unsqueeze(-1) * inverse_sqrt_degrees.unsqueeze(-2)

    def forward(self, obs: torch.Tensor) -> torch.Tensor:
        #[# of samples in batch, # of task nodes, # of hidden features]
        batch_size = obs.shape[0]
        feat_size = self.num_nodes * NUM_NODE_FEATURES
        adj_size = self.num_nodes * self.num_nodes

        node_features = obs[:, :feat_size].reshape(batch_size, self.num_nodes, NUM_NODE_FEATURES)
        adjacency = obs[:, feat_size: feat_size + adj_size].reshape(batch_size, self.num_nodes, self.num_nodes)

        # GNN encoding
        node_embeddings = torch.relu(self.input_proj(node_features))
        normalized_adjacency = self._normalize_adjacency(adjacency)
        for layer in self.gnn_layers:
            node_embeddings = layer(node_embeddings, normalized_adjacency)

        task_embedding = node_embeddings[:, 0, :]  # task node
        dc_embedding = node_embeddings[:, 1:self.num_dc + 1, :].mean(1)  # mean over DC nodes
        global_embedding = node_embeddings[:, -1, :]  # global node

        return torch.cat([task_embedding, dc_embedding, global_embedding], dim=-1)
