import numpy as np
import gymnasium as gym
from gymnasium import spaces

class SchedulingEnvironment(gym.Env):
    def __init__(self, num_dc = 8, num_dags=1000):
        super.__init__()
        self.num_dc = num_dc
        self.num_dags = num_dags
        self.alpha = 0.1
        self.current_step = 0
        self.max_steps = 1000
        self.completed_dags = 0

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
        obs = self._get_obs()
        return obs, {}

    def step(self):
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

        info = {}

        return obs, reward, terminated, False, info

    def _get_obs(self):
        #internal state to observation state representation, do not concern about vms within data center

        #features of current task
        #load on cpu
        task_cpu = np.random.uniform(0, 1)
        #load on memory
        task_memory = np.random.uniform(0, 1)

        dc_features = []
        #features of data center
        for dc in range(self.num_dc):
            available_nodes = np.random.uniform(0, 1) #are there available nodes/vm in the data center
            utilization_capacity = np.random.uniform(0, 1) #what is the current load on the data center
            available_ram = np.random.uniform(0, 1)
            available_cpu = np.random.uniform(0, 1)
            queue_length = np.random.uniform(0, 1) #number of tasks waiting to start
            dc_features.extend([available_nodes, utilization_capacity, available_ram, available_cpu, queue_length])

        obs = np.array(task_cpu + task_memory + dc_features, dtype=np.float32)
        
        return obs

    def _is_terminated(self):
        return self.completed_dags == self.num_dags

