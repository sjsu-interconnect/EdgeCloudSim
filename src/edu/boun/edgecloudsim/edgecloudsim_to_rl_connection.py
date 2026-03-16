from flask import Flask, request, jsonify
from rl_environment import SchedulingEnvironment

app = Flask(__name__)
env = SchedulingEnvironment()
obv, info = env.reset()

@app.route('/get_action', methods=['POST'])
def get_action():
    data = request.json
    print("data received: ", data)

    action = 0
    return jsonify({"action": action})

@app.route('/send_reward_info', methods=['POST'])
def send_reward_info():
    data = request.json
    print("data received: ", data)

    cost = data['cost']
    time_taken = data['time_taken']

    return jsonify({"status": "ok"})


if __name__ == '__main__':