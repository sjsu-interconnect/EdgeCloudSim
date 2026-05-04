from threading import Thread
import time
import os

from edgecloudsim_to_rl_server import app
from rl_environment import SchedulingEnvironment
from rl_agent import SchedulingAgent

def start_app():
    app.run(host="0.0.0.0", port=8000, debug=False, use_reloader=False)

def main():
    thread = Thread(target=start_app, daemon=True)
    thread.start()

    print("[Training.py] Flask bridge started on port 8000")
    time.sleep(1.0) #let server start first

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