from flask import Flask, request, jsonify
from rl_environment import SchedulingEnvironment
# from stable_baselines3 import PPO
from sb3_contrib import MaskablePPO
import numpy as np

app = Flask(__name__)
env = SchedulingEnvironment()
obv, info = env.reset()
model = MaskablePPO("MlpPolicy", env, verbose=1)

last_action = None
last_obs = None

#request action from edgecloudsim
@app.route('/act', methods=['POST'])
def get_action():
    global last_action, last_obs

    data = request.json
    # print("data received: ", data)
    #get state from edgecloudsim
    state = data["state"]

    #store current state
    env.current_state = state

    #1 step, last state get reward
    if last_action is not None:
        obs, reward, terminated, truncated, info = env.step(last_action)

        if terminated:
            obs, info = env.reset()
            last_action = None
            last_obs = None
    else:
        obs = env._get_obs(state)
    
    action_mask = env._get_action_mask(state)
    print(f"[MASK] {action_mask}")
    action, _ = model.predict(obs, action_masks=action_mask)
    action = int(action)

    if action < env.num_edge_dc:
        tier_name = "EDGE"
        dc_idx = action
    else:
        tier_name = "CLOUD"
        dc_idx = action - env.num_edge_dc

    last_action = action
    last_obs = obs

    #get vms from chosen data center
    cluster = state["cluster"]
    if tier_name == "EDGE":
        vm_list = cluster.get("edgeVms", [])
    else:
        vm_list = cluster.get("cloudVms", [])
    
    dc_vms = [vm for vm in vm_list if vm["dcId"] == dc_idx]
    if not dc_vms:
        return jsonify({"error": f"No VM available for {tier_name} datacenter {dc_idx}"}), 400
    vm_id = int(dc_vms[0]["vmId"])

    task = state["task"]
    time_info = state["time"]
    print(
        f"[ACT] simTime={time_info.get('simTime', 0.0):.2f} "
        f"dag={task.get('dagId', 'NA')} "
        f"task={task.get('taskId', 'NA')} "
        f"tier={tier_name} dc={dc_idx} vm={vm_id}"
    )

    #return action: edge/cloud + data center id + vm id
    return jsonify({
        "tier": tier_name,
        "datacenterId": dc_idx,
        "vmId": vm_id
    })

#send result
@app.route('/observe', methods=['POST'])
def send_result():
    data = request.json
    # print("data received: ", data)
    info = data.get("info", {})

    #store current time and cost used for reward
    env.current_cost = info.get("actualCost", 0.0)
    env.current_time = info.get("actualLatency", 0.0)

    if data.get("done", False):
        env.completed_dags += 1

    reward = data.get("reward", env._get_reward())
    print(
        f"[OBSERVE] reward={reward:.4f} "
        f"latency={info.get('actualLatency', 0.0):.2f} "
        f"cost={info.get('actualCost', 0.0):.4f} "
        f"done={data.get('done', False)} "
        f"budgetViolated={info.get('budgetViolated', False)}"
    )

    #acknowledge reward was received
    return jsonify({
        "status": "ok",
        "reward": reward
    })


if __name__ == '__main__':
    app.run(port=8000, debug=True)