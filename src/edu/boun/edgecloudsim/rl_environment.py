import numpy as np
import gymnasium as gym
from gymnasium import spaces

class SchedulingEnvironment(gym.Env):
    def __init__(self, num_dags=1000, num_edge_dc=5, num_cloud_dc=6):
        super().__init__()
        self.num_dags = num_dags
        self.current_step = 0
        self.max_steps = 1000
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

        OBSERVATION_SIZE = 2 + (5 * self.total_dc)

        #action space: pick type, data center
        self.max_dc = max(self.num_edge_dc, self.num_cloud_dc)
        self.action_space = spaces.MultiDiscrete([2, self.max_dc])

        #observation/state space: receive task information + data center information
        self.observation_space = spaces.Box(low = 0, high = 1, shape=(OBSERVATION_SIZE, ), dtype=np.float32)

    def reset(self, seed=42, options=None):
        #reset the simulation
        #get first task in priority queue
        #return observation and information
        super().reset(seed=seed)
        self.current_step = 0
        self.completed_dags = 0
        self.current_cost = 0
        self.current_time = 0
        obs = self._get_obs()
        info = self._get_info()

        return obs, info

    def step(self, action):
        #action
        #send to edgecloudsim
        #get cost, time
        #compute reward
        #ready next task in priority queue
        #return observation, reward, terminated, truncated, info
        self.current_cost = self.np_random.uniform(1, 100)
        self.current_time = self.np_random.uniform(1, 100)

        reward = self._get_reward()
        
        dag_completed = False
        if dag_completed:
            self.completed_dags += 1
            
        terminated = self._is_terminated()
        obs = self._get_obs()

        info = self._get_info()

        return obs, reward, terminated, False, info
    
    def _get_reward(self):
        normalized_cost = self.current_cost / self.max_cost
        normalized_latency = self.current_time / self.max_latency
        reward = -((self.alpha_latency * normalized_latency) + (self.alpha_cost * normalized_cost))
        return reward

    def _get_obs(self, data=None):
        #internal state to observation state representation, do not concern about vms within data center

        #features of current task
        task_features = []
        #load on cpu
        task_cpu = self.np_random.uniform(0, 1)
        #load on memory
        task_memory = self.np_random.uniform(0, 1)
        task_features.append(task_cpu)
        task_features.append(task_memory)

        dc_features = []
        #features of data center
        for dc in range(self.total_dc):
            available_nodes = self.np_random.uniform(0, 1) #are there available nodes/vm in the data center
            utilization_capacity = self.np_random.uniform(0, 1) #what is the current load on the data center
            available_ram = self.np_random.uniform(0, 1)
            available_cpu = self.np_random.uniform(0, 1)
            queue_length = self.np_random.uniform(0, 1) #number of tasks waiting to start
            dc_features.extend([available_nodes, utilization_capacity, available_ram, available_cpu, queue_length])

        obs = np.array(task_features + dc_features, dtype=np.float32)
        
        return obs

    def _is_terminated(self):
        return self.completed_dags == self.num_dags

    #action masking, edge/cloud -> data center
    def _get_action_mask(self):
        mask = np.ones((2, self.max_dc), dtype=bool)
        #check if current data center is available to schedule
        mask[0, self.num_edge_dc:] = False
        mask[1, self.num_cloud_dc:] = False
        
        return mask
    
    def _get_info(self):
        return {
            "action_mask": self._get_action_mask(),
            "dags_completed": self.completed_dags,
            "cost": self.current_cost,
            "time_taken": self.current_time
        }

    def render(self):
        pass