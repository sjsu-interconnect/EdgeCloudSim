from flask import Flask, request, jsonify
from redis_bridge import redis_bridge

app = Flask(__name__)

timeout_seconds = 120


@app.route("/act", methods=["POST"])
def get_action():
    data = request.get_json(force=True)

    state = data["state"]
    action_mask = data.get("actionMask")

    request_id = redis_bridge.push_act_request(state, action_mask)

    try:
        action_json = redis_bridge.pop_action(timeout_seconds)
    except TimeoutError as e:
        return jsonify({"error": str(e)}), 500

    task = state.get("task", {})
    print(
        f"[ACT] req={request_id} "
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

    redis_bridge.push_observe(reward, next_state, done, info)
    request_id = redis_bridge.get_request_id()

    print(
        f"[OBSERVE] req={request_id} "
        f"reward={reward:.4f} "
        f"latency={info.get('actualLatency', 0.0):.2f} "
        f"cost={info.get('actualCost', 0.0):.4f} "
        f"done={done} "
        f"budgetViolated={info.get('budgetViolated', False)}"
    )

    return jsonify({"status": "ok"})


if __name__ == "__main__":
    redis_bridge.flush()
    print("[Server] Flask server starting on port 8000")
    print("[Server] Waiting for training.py and EdgeCloudSim...")

    app.run(host="0.0.0.0", port=8000, debug=False, use_reloader=False)