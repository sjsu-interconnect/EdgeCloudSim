from flask import Flask, request, jsonify
from rl_environment import SchedulingEnvironment
import numpy as np

app = Flask(__name__)
env = SchedulingEnvironment()
obv, info = env.reset()

#request action from edgecloudsim
@app.route('/act', methods=['POST'])
def get_action():
    data = request.json

    print("data received: ", data)

    state = data["state"]
    obs = env._get_obs(state)
    action_mask = env._get_action_mask(state)

    #pick tier at random at mask stage 1
    tiers = [tier for tier in range(action_mask.shape[0]) if action_mask[tier].any()]
    tier_idx = int(np.random.choice(tiers))

    #get dcs based on tier at mask stage 2
    dcs = np.where(action_mask[tier_idx])[0]
    dc_idx = int(np.random.choice(dcs))

    tier_name = "EDGE" if tier_idx == 0 else "CLOUD"

    #get vms from chosen data center
    cluster = state["cluster"]
    #pick vm
    if tier_idx == 0:
        vm_list = cluster.get("edgeVms", [])
    else:
        vm_list = cluster.get("cloudVms", [])
    
    dc_vms = [vm for vm in vm_list if vm["dcId"] == dc_idx]
    if not dc_vms:
        return jsonify({
            "error": f"No VM available for {tier_name} datacenter {dc_idx}"
        }), 400
    
    vm_id = int(dc_vms[0]["vmId"])

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
    print("data received: ", data)

    info = data.get("info", {})

    env.current_cost = info.get("actualCost", 0.0)
    env.current_time = info.get("actualLatency", 0.0)

    if data.get("done", False):
        env.completed_dags += 1

    reward = data.get("reward", env._get_reward())
    print("Reward: ", reward)

    #acknowledge reward was received
    return jsonify({
        "status": "ok",
        "reward": reward
    })


if __name__ == '__main__':
    app.run(port=8000, debug=True)