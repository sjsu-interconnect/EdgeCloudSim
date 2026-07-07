from sb3_contrib import MaskablePPO
from gnn_policy import GNNExtractor


class SchedulingAgent:
    def __init__(self, env):
        self.env = env

        # GNN feature extractor configuration
        policy_kwargs = dict(
            features_extractor_class=GNNExtractor,
            features_extractor_kwargs=dict(
                num_edge_dc=8,
                num_cloud_dc=1,
                hidden_dim=128,
                n_layers=2,
            ),
            net_arch=dict(pi=[128, 64], vf=[128, 64]),
        )

        self.model = MaskablePPO(
            "MlpPolicy",
            env,
            policy_kwargs=policy_kwargs,
            verbose=1,
            tensorboard_log="./tensorboard_logs/",
        )

    # Get action from agent
    def act(self, obs, action_mask):
        action, _ = self.model.predict(
            obs,
            action_masks=action_mask,
            deterministic=False
        )
        return int(action)

    def train(self, total_timesteps=100000):
        self.model.learn(total_timesteps=total_timesteps)

    def save(self, path="ppo_model"):
        self.model.save(path)
