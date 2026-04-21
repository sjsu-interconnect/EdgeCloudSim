import time
from flask import Flask, request, jsonify
from edgecloudsim_to_rl_bridge import bridge_state

app = Flask(__name__)

poll_interval = 0.01
timeout_seconds = 120

@app.route('/act', methods=['POST'])
def get_action():
    data = request.get_json(force=True)
    #get state from edgecloudsim
    state = data["state"]

    bridge_state.latest_act_state = state

    #set first state for episode to send to env reset
    if not bridge_state.episode_started and bridge_state.initial_state is None:
        bridge_state.initial_state = state

    #get action json from step
    start = time.time()
    while bridge_state.pending_action_json is None:
        if time.time() - start > timeout_seconds:
            return jsonify({"error": "Timed out waiting for action from env.step()"}), 500
        time.sleep(poll_interval)
    
    action_json = bridge_state.pending_action_json
    bridge_state.pending_action_json = None

    task = state.get("task", {})
    print(
        f"[ACT] dag={task.get('dagId', 'NA')} "
        f"task={task.get('taskId', 'NA')} "
        f"tier={action_json['tier']} "
        f"dc={action_json['datacenterId']} "
        f"vm={action_json['vmId']}"
    )

    return jsonify(action_json)

@app.route('/observe', methods=['POST'])
def send_result():
    data = request.get_json(force=True)

    reward = float(data["reward"])
    done = bool(data["done"])
    info = data.get("info", {})

    bridge_state.pending_transition = {
        "reward": reward,
        "done": done,
        "info": info
    }

    print(
        f"[OBSERVE] reward={reward:.4f} "
        f"latency={info.get('actualLatency', 0.0):.2f} "
        f"cost={info.get('actualCost', 0.0):.4f} "
        f"done={done} "
        f"budgetViolated={info.get('budgetViolated', False)}"
    )

    return jsonify({
        "status": "ok"
    })


if __name__ == '__main__':
    app.run(port=8000, debug=True, use_reloader=False)