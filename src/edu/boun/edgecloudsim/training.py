import os

from rl_environment import SchedulingEnvironment
from rl_agent import SchedulingAgent

def main():
    print("[Training] Starting - make sure redis and edgecloudsim to rl server are running")

    env = SchedulingEnvironment()
    agent = SchedulingAgent(env)

    try:
        print("Start PPO training")
        agent.train(total_timesteps=6500)
        print("agent.train returned normally")
    except RuntimeError as e:
        print(f"Training ended (simulation finished): {e}")
    except Exception as e:
        print(f"agent.train raised unexpected exception: {e}")
        raise
    finally:
        print("Training finished, saving model")
        agent.save("maskable_ppo_edgecloudsim")
        print("Shutting down")
        os._exit(0)

if __name__ == "__main__":
    main()