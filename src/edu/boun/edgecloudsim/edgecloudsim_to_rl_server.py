from flask import Flask, request, jsonify
from edgecloudsim_to_rl_bridge import bridge_state

app = Flask(__name__)

timeout_seconds = 120


@app.route("/act", methods=["POST"])
def get_action():
    data = request.get_json(force=True)

    state = data["state"]
    action_mask = data.get("actionMask")

    try:
        bridge_state.publish_act_request(state, action_mask)
        action_json = bridge_state.wait_for_action(timeout_seconds)
    except TimeoutError as e:
        return jsonify({"error": str(e)}), 500

    task = state.get("task", {})
    print(
        f"[ACT] req={bridge_state.act_request_id} "
        f"dag={task.get('dagId', 'NA')} "
        f"task={task.get('taskId', 'NA')} "
        f"tier={action_json.get('tier', 'NA')} "
        f"dc={action_json.get('datacenterId', 'NA')} "
        f"vm={action_json.get('vmId', 'NA')}"
    )

    return jsonify(action_json)


@app.route("/observe", methods=["POST"])
def send_result():
    data = request.get_json(force=True)

    reward = float(data["reward"])
    next_state = data["next_state"]
    done = bool(data["done"])
    info = data.get("info", {})

    bridge_state.publish_transition(
        reward=reward,
        next_state=next_state,
        done=done,
        info=info,
    )

    print(
        f"[OBSERVE] req={bridge_state.observe_request_id} "
        f"reward={reward:.4f} "
        f"latency={info.get('actualLatency', 0.0):.2f} "
        f"cost={info.get('actualCost', 0.0):.4f} "
        f"done={done} "
        f"budgetViolated={info.get('budgetViolated', False)}"
    )

    return jsonify({"status": "ok"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=False, use_reloader=False)