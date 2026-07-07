import os

from stable_baselines3.common.monitor import Monitor

from rl_environment import SchedulingEnvironment
from rl_agent import SchedulingAgent

def main():
    print("Training start, make sure redis server and edgecloudsim server are active")

    env = Monitor(SchedulingEnvironment())
    agent = SchedulingAgent(env)

    try:
        print("Start training")
        agent.train(total_timesteps=1000000)
    except RuntimeError as e:
        print(f"Training ended (simulation finished): {e}")
    except Exception as e:
        print(f"Training exception: {e}")
        raise
    finally:
        print("Training finished, saving model")
        agent.save("ppo_model")
        print("Shutting down")
        os._exit(0)

if __name__ == "__main__":
    main()
