import numpy as np
import gymnasium as gym
from gymnasium import spaces

class SchedulingEnvironment(gym.Env):
    def __init__(self, num_dags=100000, num_edge_dc=8, num_cloud_dc=1):
        super().__init__()

        self.current_state = None

        self.num_dags = num_dags
        self.completed_dags = 0
        self.current_cost = 0
        self.current_time = 0

        #weights
        self.alpha_latency = 0.5
        self.alpha_cost = 1

        #normalization constants
        self.max_cost = 100.0
        self.max_latency = 100.0

        self.num_edge_dc = num_edge_dc
        self.num_cloud_dc = num_cloud_dc
        self.total_dc = self.num_edge_dc + self.num_cloud_dc

        OBSERVATION_SIZE = 2 + 4 + (6 * self.total_dc)

        #action space: pick type, data center
        self.total_actions = self.num_edge_dc + self.num_cloud_dc
        self.action_space = spaces.Discrete(self.total_actions)

        #observation/state space: receive task information + data center information
        self.observation_space = spaces.Box(low = 0, high = 1, shape=(OBSERVATION_SIZE, ), dtype=np.float32)

    def reset(self, seed=42, options=None):
        super().reset(seed=seed)
        self.completed_dags = 0
        self.current_cost = 0
        self.current_time = 0
        self.current_state = None

        obs = np.zeros(self.observation_space.shape, dtype=np.float32)
        info = self._get_info()

        return obs, info

    def step(self, action):
        reward = self._get_reward()
        terminated = self._is_terminated()
        obs = self._get_obs(self.current_state)
        info = self._get_info()
            
        return obs, reward, terminated, False, info
    
    def _get_reward(self):
        normalized_cost = self.current_cost / self.max_cost
        normalized_latency = self.current_time / self.max_latency
        reward = -((self.alpha_latency * normalized_latency) + (self.alpha_cost * normalized_cost))
        return reward

    def _get_obs(self, state=None):
        #internal state to observation state representation, do not concern about vms within data center
        if state is None:
            return np.zeros(self.observation_space.shape, dtype=np.float32)
        
        task = state["task"]
        clusterState = state["cluster"]
        budget = state["budget"]
        queue_length = state["queue"]
        time = state["time"]
        
        #global features
        #number of tasks in queue for vms on dc, amount of money used, active dags

        global_features = [
            self.normalize(budget.get("budgetFractionUsed", 0.0)),        # how much budget used
            self.normalize(budget.get("remainingBudget", 0.0) / 100.0),   # remaining budget
            self.normalize(queue_length.get("activeDagCount", 0) / 100.0), # active dags
            self.normalize(queue_length.get("totalQueueLen", 0) / 500.0),  # total queue
        ]

        #features of current task
        #mips, size of task
        #need to normalize
        task_features = [
            self.normalize(task.get("mi", 0.0) / 1000.0),
            self.normalize(task.get("dataSizeBytes", 0.0) / (10 * 1024 * 1024)),
        ]

        #features of data center
        dc_features = []
        edge_dc_vms = clusterState.get("edgeVms", [])
        cloud_dc_vms = clusterState.get("cloudVms", [])

        #get features of each tier dc based on vm info
        for dc_id in range(self.num_edge_dc):
            features = self._get_data_center_features(edge_dc_vms, dc_id)
            dc_features.extend(features)
        
        for dc_id in range(self.num_cloud_dc):
            features = self._get_data_center_features(cloud_dc_vms, dc_id)
            dc_features.extend(features)

        obs = np.array(task_features + global_features + dc_features, dtype=np.float32)
        
        return obs

    def _is_terminated(self):
        return self.completed_dags >= self.num_dags

    #action masking, edge/cloud -> data center
    def _get_action_mask(self, state=None):
        #initial state
        mask = np.zeros(self.total_actions, dtype=bool)

        if state is None:
            mask[:] = True
            return mask

        #in current state, get edge and cloud vms
        current_state = state["cluster"]
        edge_vms = current_state.get("edgeVms", [])
        cloud_vms = current_state.get("cloudVms", [])
        
        #get data center id from vm and check
        for vm in edge_vms:
            dc_id = vm["dcId"]
            if 0 <= dc_id < self.num_edge_dc:
                mask[dc_id] = True
        
        for vm in cloud_vms:
            dc_id = vm["dcId"]
            if 0 <= dc_id < self.num_cloud_dc:
                mask[self.num_edge_dc + dc_id] = True
        
        return mask
    
    def _get_info(self):
        return {
            "dags_completed": self.completed_dags,
            "cost": self.current_cost,
            "time_taken": self.current_time
        }

    def render(self):
        pass

    def normalize(self, value):
        return float(np.clip(value, 0.0, 1.0))
    
    def _get_data_center_features(self, vm_list, dc_id):
        dc_vms = [vm for vm in vm_list if vm["dcId"] == dc_id]

        vm_count = len(dc_vms)
        has_available_vm = 1.0 if vm_count > 0 else 0.0
        total_available_mips = sum(vm["availableMips"] for vm in dc_vms)
        avg_available_mips = total_available_mips / vm_count if vm_count > 0 else 0.0
        avg_utilization = sum(vm["utilization"] for vm in dc_vms) / vm_count if vm_count > 0 else 0.0
        total_queue_len = sum(vm["queueLen"] for vm in dc_vms)

        return [
            self.normalize(vm_count / 10.0),
            self.normalize(avg_available_mips / 10000.0),
            self.normalize(total_available_mips / 50000.0),
            self.normalize(avg_utilization),
            self.normalize(total_queue_len / 100.0),
            has_available_vm
        ]