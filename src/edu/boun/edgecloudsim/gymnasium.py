import numpy as np
import edu.boun.edgecloudsim.gymnasium as gym
from edu.boun.edgecloudsim.gymnasium import spaces

class SchedulingEnvironment(gym.Env):
    def __init__(self, num_dc = 8, num_dags=1000):
        super().__init__()
        self.num_dc = num_dc
        self.num_dags = num_dags
        self.alpha = 0.1
        self.current_step = 0
        self.max_steps = 1000
        self.completed_dags = 0
        self.current_cost = 0
        self.current_time = 0

        OBSERVATION_SIZE = 2 + (5 * num_dc)

        #action space: picking a data center
        self.action_space = spaces.Discrete(num_dc)

        #observation/state space: receive task information + data center information
        self.observation_space = spaces.Box(low = 0, high = 1, shape=(OBSERVATION_SIZE, ), dtype=np.float32)

    def reset(self, seed=42):
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
        cost = np.random.uniform(1, 100)
        time_taken = np.random.uniform(1, 10)

        reward = time_taken - cost
        
        dag_completed = False
        if dag_completed:
            self.completed_dags += 1
            
        terminated = self._is_terminated()
        obs = self._get_obs()

        info = self._get_info()

        return obs, reward, terminated, False, info

    def _get_obs(self):
        #internal state to observation state representation, do not concern about vms within data center

        #features of current task
        task_features = []
        #load on cpu
        task_cpu = np.random.uniform(0, 1)
        #load on memory
        task_memory = np.random.uniform(0, 1)
        task_features.append(task_cpu)
        task_features.append(task_memory)

        dc_features = []
        #features of data center
        for dc in range(self.num_dc):
            available_nodes = np.random.uniform(0, 1) #are there available nodes/vm in the data center
            utilization_capacity = np.random.uniform(0, 1) #what is the current load on the data center
            available_ram = np.random.uniform(0, 1)
            available_cpu = np.random.uniform(0, 1)
            queue_length = np.random.uniform(0, 1) #number of tasks waiting to start
            dc_features.extend([available_nodes, utilization_capacity, available_ram, available_cpu, queue_length])

        obs = np.array(task_features + dc_features, dtype=np.float32)
        
        return obs

    def _is_terminated(self):
        return self.completed_dags == self.num_dags

    #action masking
    def _get_action_mask(self):
        mask = []
        #check if current data center is available to schedule
        for i in range(self.num_dc):
            #is_available: True or False
            is_available = True
            mask.append(is_available)
        
        mask = np.array(mask, dtype=bool)

        if not any(mask):
            mask[0] = True
        
        return mask
    
    def _get_info(self):
        return {
            "action_mask": self._get_action_mask(),
            "dags_completed": self.completed_dags,
            "cost": self.current_cost,
            "time_taken": self.current_time
        }