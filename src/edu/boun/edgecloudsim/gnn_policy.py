"""
gnn_policy.py

GNN feature extractor for SB3 MaskablePPO.

Graph structure:
  Node 0:      task node    (current task being scheduled)
  Nodes 1-8:   edge DC nodes (one per edge DC)
  Node 9:      cloud node
  Node 10:     global node  (system-wide state)

Each node has NODE_FEAT_DIM=15 features (12 content + 3 role encoding).
Observation is flattened: node_features (11×15=165) + adjacency (11×11=121) = 286 values.
"""

import numpy as np
import torch
import torch.nn as nn
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
import gymnasium as gym


# ── Constants ──────────────────────────────────────────────────────────────────
NUM_TASK_TYPES = 7
TASK_TYPES = {
    "vae_encode":       0,
    "unet_denoise":     1,
    "sampler":          2,
    "vae_decode":       3,
    "lora_load":        4,
    "controlnet_load":  5,
    "base_model_load":  6,
}

NODE_FEAT_DIM  = 15   # 12 content features + 3 role encoding
CONTENT_DIM    = 12
ROLE_DIM       = 3

# Role encodings
ROLE_TASK   = np.array([1.0, 0.0, 0.0], dtype=np.float32)
ROLE_DC     = np.array([0.0, 1.0, 0.0], dtype=np.float32)
ROLE_GLOBAL = np.array([0.0, 0.0, 1.0], dtype=np.float32)


# ── Normalization helpers ──────────────────────────────────────────────────────
def log_norm(value: float, max_expected: float = 5000.0) -> float:
    """Log normalization — handles any scale without hard clipping."""
    import math
    return math.log1p(max(0.0, value)) / math.log1p(max_expected)


def clip_norm(value: float, divisor: float) -> float:
    return float(np.clip(value / divisor, 0.0, 1.0))


def safe_hash(value: str) -> float:
    return (abs(hash(value)) % 10000) / 10000.0


# ── Node feature builders ──────────────────────────────────────────────────────
def task_node_features(task: dict, budget: dict) -> np.ndarray:
    """12 content features for the task node."""
    task_type_str = task.get("taskType", "vae_encode")
    task_type_idx = TASK_TYPES.get(task_type_str, 0)
    task_type_onehot = np.zeros(NUM_TASK_TYPES, dtype=np.float32)
    task_type_onehot[task_type_idx] = 1.0

    features = np.zeros(CONTENT_DIM, dtype=np.float32)
    features[0] = clip_norm(task.get("mi", 0.0), 13_000_000.0)
    features[1] = clip_norm(task.get("dataSizeBytes", 0.0), 1_073_741_824.0)
    features[2:9] = task_type_onehot                              # 7 values
    features[9]  = float(np.clip(budget.get("budgetFractionUsed", 0.0), 0.0, 1.0))
    features[10] = safe_hash(task.get("dagId", ""))
    features[11] = 0.0  # reserved
    return features


def dc_node_features(vm_list: list, dc_id: int, is_edge: bool) -> np.ndarray:
    """12 content features for a DC node."""
    dc_vms = [vm for vm in vm_list if int(vm["dcId"]) == dc_id]

    if dc_vms:
        total_mips  = sum(float(vm["availableMips"]) for vm in dc_vms)
        avg_util    = sum(float(vm["utilization"]) for vm in dc_vms) / len(dc_vms)
        total_queue = sum(int(vm["queueLen"]) for vm in dc_vms)
        cost_per_sec = float(dc_vms[0].get("costPerSec", 0.0))
    else:
        total_mips = avg_util = total_queue = cost_per_sec = 0.0

    # Normalize MIPS differently for edge vs cloud
    mips_divisor = 20_000.0 if is_edge else 12_800_000.0

    features = np.zeros(CONTENT_DIM, dtype=np.float32)
    features[0] = clip_norm(total_mips, mips_divisor)
    features[1] = float(np.clip(avg_util, 0.0, 1.0))
    features[2] = log_norm(total_queue, 5000.0)
    features[3] = 1.0 if is_edge else 0.0
    features[4] = 0.0 if is_edge else 1.0
    features[5] = dc_id / 8.0                                    # normalized DC id
    features[6] = clip_norm(cost_per_sec, 1e-4)
    # features[7:12] = zeros
    return features


def global_node_features(cluster: dict, budget: dict, queue: dict, time: dict) -> np.ndarray:
    """12 content features for the global node."""
    edge  = cluster.get("edge",  {})
    cloud = cluster.get("cloud", {})

    features = np.zeros(CONTENT_DIM, dtype=np.float32)
    features[0]  = clip_norm(edge.get("availableMips", 0.0),  16_000.0)
    features[1]  = float(np.clip(edge.get("utilization", 0.0), 0.0, 1.0))
    features[2]  = log_norm(edge.get("queueLen", 0),  20_000.0)
    features[3]  = clip_norm(cloud.get("availableMips", 0.0), 12_800_000.0)
    features[4]  = float(np.clip(cloud.get("utilization", 0.0), 0.0, 1.0))
    features[5]  = log_norm(cloud.get("queueLen", 0),  1_000.0)
    features[6]  = float(np.clip(budget.get("budgetFractionUsed", 0.0), 0.0, 1.0))
    features[7]  = clip_norm(budget.get("remainingBudget", 0.0), 1800.0)
    features[8]  = clip_norm(queue.get("activeDagCount", 0),  1000.0)
    features[9]  = log_norm(queue.get("totalQueueLen", 0),   50_000.0)
    features[10] = clip_norm(time.get("simTime", 0.0),       60_000_000.0)
    features[11] = 0.0  # reserved
    return features


# ── Graph observation builder ──────────────────────────────────────────────────
def build_graph_obs(state: dict, num_edge_dc: int = 8, num_cloud_dc: int = 1) -> np.ndarray:
    """
    Build flattened graph observation from state dict.
    Returns array of shape (obs_dim,) = (num_nodes*15 + num_nodes*num_nodes,)
    """
    num_dc    = num_edge_dc + num_cloud_dc
    num_nodes = num_dc + 2          # DCs + task node + global node
    task_idx  = 0
    global_idx = num_nodes - 1

    task    = state.get("task",    {})
    cluster = state.get("cluster", {})
    budget  = state.get("budget",  {})
    queue   = state.get("queue",   {})
    time    = state.get("time",    {})

    edge_vms  = cluster.get("edgeVms",  [])
    cloud_vms = cluster.get("cloudVms", [])

    node_features = np.zeros((num_nodes, NODE_FEAT_DIM), dtype=np.float32)
    adjacency     = np.eye(num_nodes, dtype=np.float32)   # self-loops

    # Task node (index 0)
    node_features[task_idx, :CONTENT_DIM] = task_node_features(task, budget)
    node_features[task_idx, CONTENT_DIM:] = ROLE_TASK

    # Edge DC nodes (indices 1 to num_edge_dc)
    for dc_id in range(num_edge_dc):
        node_idx = dc_id + 1
        node_features[node_idx, :CONTENT_DIM] = dc_node_features(edge_vms, dc_id, is_edge=True)
        node_features[node_idx, CONTENT_DIM:] = ROLE_DC
        # Connect task ↔ DC and global ↔ DC
        adjacency[task_idx, node_idx]  = 1.0
        adjacency[node_idx, task_idx]  = 1.0
        adjacency[global_idx, node_idx] = 1.0
        adjacency[node_idx, global_idx] = 1.0

    # Cloud DC node (index num_edge_dc + 1)
    cloud_node_idx = num_edge_dc + 1
    node_features[cloud_node_idx, :CONTENT_DIM] = dc_node_features(cloud_vms, 0, is_edge=False)
    node_features[cloud_node_idx, CONTENT_DIM:] = ROLE_DC
    adjacency[task_idx, cloud_node_idx]   = 1.0
    adjacency[cloud_node_idx, task_idx]   = 1.0
    adjacency[global_idx, cloud_node_idx] = 1.0
    adjacency[cloud_node_idx, global_idx] = 1.0

    # Global node (last index)
    node_features[global_idx, :CONTENT_DIM] = global_node_features(cluster, budget, queue, time)
    node_features[global_idx, CONTENT_DIM:]  = ROLE_GLOBAL
    adjacency[task_idx, global_idx]  = 1.0
    adjacency[global_idx, task_idx]  = 1.0

    return np.concatenate([node_features.flatten(), adjacency.flatten()])


def graph_obs_dim(num_edge_dc: int = 8, num_cloud_dc: int = 1) -> int:
    num_nodes = num_edge_dc + num_cloud_dc + 2
    return (num_nodes * NODE_FEAT_DIM) + (num_nodes * num_nodes)


# ── GNN layers ─────────────────────────────────────────────────────────────────
class MessagePassingBlock(nn.Module):
    """Single GNN message passing layer with residual connection."""

    def __init__(self, hidden_dim: int) -> None:
        super().__init__()
        self.self_proj  = nn.Linear(hidden_dim, hidden_dim)
        self.neigh_proj = nn.Linear(hidden_dim, hidden_dim)
        self.norm       = nn.LayerNorm(hidden_dim)

    def forward(self, node_h: torch.Tensor, norm_adj: torch.Tensor) -> torch.Tensor:
        neigh_h = torch.bmm(norm_adj, node_h)
        updated = torch.relu(self.self_proj(node_h) + self.neigh_proj(neigh_h))
        return self.norm(node_h + updated)


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
        num_edge_dc:  int = 8,
        num_cloud_dc: int = 1,
        hidden_dim:   int = 128,
        n_layers:     int = 2,
    ) -> None:
        self.num_edge_dc  = num_edge_dc
        self.num_cloud_dc = num_cloud_dc
        self.num_dc       = num_edge_dc + num_cloud_dc
        self.num_nodes    = self.num_dc + 2
        self.hidden_dim   = hidden_dim

        # Output: [task_h | mean_dc_h | global_h] = 3 × hidden_dim
        features_dim = hidden_dim * 3
        super().__init__(observation_space, features_dim=features_dim)

        self.input_proj = nn.Linear(NODE_FEAT_DIM, hidden_dim)
        self.gnn_layers = nn.ModuleList([
            MessagePassingBlock(hidden_dim) for _ in range(max(1, n_layers))
        ])

    @staticmethod
    def _normalize_adjacency(adj: torch.Tensor) -> torch.Tensor:
        degree      = adj.sum(dim=-1)
        inv_sqrt    = torch.rsqrt(degree.clamp(min=1.0))
        return adj * inv_sqrt.unsqueeze(-1) * inv_sqrt.unsqueeze(-2)

    def forward(self, obs: torch.Tensor) -> torch.Tensor:
        B         = obs.shape[0]
        feat_size = self.num_nodes * NODE_FEAT_DIM
        adj_size  = self.num_nodes * self.num_nodes

        node_features = obs[:, :feat_size].reshape(B, self.num_nodes, NODE_FEAT_DIM)
        adjacency     = obs[:, feat_size: feat_size + adj_size].reshape(B, self.num_nodes, self.num_nodes)

        # GNN encoding
        h        = torch.relu(self.input_proj(node_features))
        norm_adj = self._normalize_adjacency(adjacency)
        for layer in self.gnn_layers:
            h = layer(h, norm_adj)

        task_h   = h[:, 0, :]                           # task node
        dc_h     = h[:, 1:self.num_dc + 1, :].mean(1)  # mean over DC nodes
        global_h = h[:, -1, :]                          # global node

        return torch.cat([task_h, dc_h, global_h], dim=-1)