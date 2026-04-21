from threading import Thread
import time

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

    print("Start PPO training")
    agent.train(total_timesteps=100000)

    print("training finished, saving model")
    agent.save("maskable_ppo_edgecloudsim")

if __name__ == "__main__":
    main()