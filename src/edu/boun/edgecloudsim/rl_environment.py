import numpy as np
import gymnasium as gym
from gymnasium import spaces
from edgecloudsim_to_rl_bridge import bridge_state

class SchedulingEnvironment(gym.Env):
    def __init__(self, num_edge_dc=8, num_cloud_dc=1, timeout_seconds=120):
        super().__init__()

        self.num_edge_dc = num_edge_dc
        self.num_cloud_dc = num_cloud_dc
        self.total_dc = self.num_edge_dc + self.num_cloud_dc

        OBSERVATION_SIZE = 2 + 4 + (6 * self.total_dc)
        
        #action space: pick type, data center
        self.total_actions = self.num_edge_dc + self.num_cloud_dc
        self.action_space = spaces.Discrete(self.total_actions)

        #observation/state space: receive task information + data center information
        self.observation_space = spaces.Box(low = 0, high = 1, shape=(OBSERVATION_SIZE, ), dtype=np.float32)

        self.current_state = None
        self.current_reward = 0.0
        self.current_done = False
        self.current_info = {}
        
        self.timeout_seconds = timeout_seconds

    def reset(self, *, seed=None, options=None): #edgecloudsim state comes in here
        super().reset(seed=seed)

        self.current_state = bridge_state.wait_for_initial_state(self.timeout_seconds)
        self.current_reward = 0.0
        self.current_done = False
        self.current_info = {}

        obs = self._get_obs(self.current_state)

        action_mask = bridge_state.get_current_action_mask()
        if action_mask is None:
            action_mask = self._get_action_mask(self.current_state)

        info = {
            "action_mask": np.asarray(action_mask, dtype=bool),
            "raw_state": self.current_state,
        }
        return obs, info

    def step(self, action):
        if self.current_state is None:
            raise RuntimeError("step() called before reset() or without a current state")
        
        request_id_before = bridge_state.get_act_request_id()

        action_json = self.action_to_json(action, self.current_state)

        # submit chosen action so /act can return it to EdgeCloudSim
        bridge_state.submit_action(action_json)

        #wait for /observe to publish reward, next_state, done
        transition = bridge_state.wait_for_transition(self.timeout_seconds)

        reward = float(transition["reward"])
        next_state = transition["next_state"]
        done = bool(transition["done"])
        info = transition.get("info", {})
        
        self.current_reward = float(reward)
        self.current_done = bool(done)
        self.current_info = info

        if not done:
            self.current_state = bridge_state.wait_for_next_act_request(
                request_id_before, self.timeout_seconds
            )
        else:
            self.current_state = next_state

        obs = self._get_obs(self.current_state)
        terminated = done
        truncated = False
        
        action_mask = bridge_state.get_current_action_mask()
        if action_mask is None:
            action_mask = self._get_action_mask(self.current_state)

        info = {
            **self._get_info(),
            "action_mask": np.asarray(action_mask, dtype=bool),
            "raw_state": self.current_state,
        }

        return obs, reward, terminated, truncated, info

    def _get_obs(self, state=None):
        #internal state to observation state representation, do not concern about vms within data center
        if state is None:
            return np.zeros(self.observation_space.shape, dtype=np.float32)
        
        task = state["task"]
        clusterState = state["cluster"]
        budget = state["budget"]
        queue_length = state["queue"]
        
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

        #debug, check if receiving index or raw id
        if not hasattr(self, "_printed_dc_ids"):
            print("EDGE DC IDS:", sorted(set(int(vm["dcId"]) for vm in edge_vms)))
            print("CLOUD DC IDS:", sorted(set(int(vm["dcId"]) for vm in cloud_vms)))
            self._printed_dc_ids = True
        
        #get data center id from vm and check
        for vm in edge_vms:
            dc_id = int(vm["dcId"])
            if 0 <= dc_id < self.num_edge_dc:
                mask[dc_id] = True
        
        for vm in cloud_vms:
            dc_id = int(vm["dcId"])
            if 0 <= dc_id < self.num_cloud_dc:
                mask[self.num_edge_dc + dc_id] = True
        
        return mask
    
    def _get_data_center_features(self, vm_list, dc_id):
        dc_vms = [vm for vm in vm_list if int(vm["dcId"]) == int(dc_id)]

        vm_count = len(dc_vms)
        has_available_vm = 1.0 if vm_count > 0 else 0.0
        total_available_mips = sum(float(vm["availableMips"]) for vm in dc_vms)
        avg_available_mips = total_available_mips / vm_count if vm_count > 0 else 0.0
        avg_utilization = sum(float(vm["utilization"]) for vm in dc_vms) / vm_count if vm_count > 0 else 0.0
        total_queue_len = sum(int(vm["queueLen"]) for vm in dc_vms)

        return [
            self.normalize(vm_count / 10.0),
            self.normalize(avg_available_mips / 10000.0),
            self.normalize(total_available_mips / 50000.0),
            self.normalize(avg_utilization),
            self.normalize(total_queue_len / 100.0),
            has_available_vm
        ]
    
    def action_to_json(self, action, state=None):
        action = int(action)

        if state is None:
            state = self.current_state
        if state is None:
            raise ValueError("No state available for action_to_json")
        
        cluster = state["cluster"]

        #translate action back to json for edgecloudsim
        if action < self.num_edge_dc:
            tier_name = "EDGE"
            dc_idx = int(action)
            vm_list = cluster.get("edgeVms", [])
        else:
            tier_name = "CLOUD"
            dc_idx = int(action - self.num_edge_dc)
            vm_list = cluster.get("cloudVms", [])
        
        dc_vms = [vm for vm in vm_list if int(vm["dcId"]) == int(dc_idx)]

        if not dc_vms:
            raise ValueError(f"No VM available for {tier_name} datacenter {dc_idx}")
        
        best_vm = min(
            dc_vms,
            key=lambda vm: (int(vm["queueLen"]), -float(vm["availableMips"]))
        )
        vm_id = int(best_vm["vmId"])
        task = state["task"]
        time_info = state["time"]

        print(
            f"[ACT] simTime={time_info.get('simTime', 0.0):.2f} "
            f"dag={task.get('dagId', 'NA')} "
            f"task={task.get('taskId', 'NA')} "
            f"tier={tier_name} dc={dc_idx} vm={vm_id}"
        )

        #return action: edge/cloud + data center id + vm id
        return ({
            "tier": tier_name,
            "datacenterId": int(dc_idx),
            "vmId": int(vm_id), 
            "actionIndex": int(action)
        })
    
    def _get_info(self):
        return self.current_info
    
    def render(self):
        pass

    def normalize(self, value):
        return float(np.clip(value, 0.0, 1.0))
    
    def get_obs(self):
        return self._get_obs(self.current_state)
    
    def action_masks(self):
        action_mask = bridge_state.get_current_action_mask()
        if action_mask is not None:
            return np.asarray(action_mask, dtype=bool)
        return self._get_action_mask(self.current_state)