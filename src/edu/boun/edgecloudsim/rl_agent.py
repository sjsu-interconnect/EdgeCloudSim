from sb3_contrib import MaskablePPO

class SchedulingAgent:
    def __init__(self, env, model_path=None):
        #used for existing models, create new model if not exist
        self.env = env
        self.model_path = model_path

        if model_path is not None:
            self.model = MaskablePPO.load(model_path, env=env)
        else:
            self.model = MaskablePPO("MlpPolicy", env, verbose=1)

    #get action decision from agent
    def act(self, obs, action_mask):
        action, _ = self.model.predict(
            obs,
            action_masks=action_mask,
            deterministic=False
        )
        return int(action)

    def train(self, total_timesteps=100000):
        self.model.learn(total_timesteps=total_timesteps)

    def save(self, path="maskable_ppo_edgecloudsim"):
        self.model.save(path)