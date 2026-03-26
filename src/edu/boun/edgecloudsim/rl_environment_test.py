# from gymnasium.utils.env_checker import check_env
# from rl_environment import SchedulingEnvironment

# env = SchedulingEnvironment(num_edge_dc=8, num_cloud_dc=1)

# check_env(env)

# obv, info = env.reset()
# print(obv.shape)
# print("observation: ", obv)
# print("info: ", info)

# for step in range(5):
#     action = env.action_space.sample()
#     obs, reward, terminated, truncated, info = env.step(action)
#     print(f"step {step}: action={action}, reward={reward:.3f}, terminated={terminated}")


# env.close()

import numpy as np

from rl_environment import SchedulingEnvironment
from edgecloudsim_to_rl_connection import app


def build_sample_state():
    return {
        "task": {
            "dagId": "dag-1",
            "taskId": "task-1",
            "taskType": "test",
            "mi": 500.0,
            "dataSizeBytes": 1024.0 * 1024.0,
        },
        "cluster": {
            "edgeVms": [
                {
                    "dcId": 0,
                    "vmId": 11,
                    "availableMips": 4000.0,
                    "utilization": 0.2,
                    "queueLen": 1,
                },
                {
                    "dcId": 2,
                    "vmId": 12,
                    "availableMips": 4500.0,
                    "utilization": 0.0,
                    "queueLen": 0,
                },
            ],
            "cloudVms": [
                {
                    "dcId": 0,
                    "vmId": 21,
                    "availableMips": 10000.0,
                    "utilization": 0.1,
                    "queueLen": 0,
                }
            ],
            "edge": {
                "availableMips": 8500.0,
                "utilization": 0.1,
                "queueLen": 1,
            },
            "cloud": {
                "availableMips": 10000.0,
                "utilization": 0.1,
                "queueLen": 0,
            },
        },
        "budget": {
            "costSoFar": 0.0,
            "remainingBudget": 10.0,
            "budgetFractionUsed": 0.0,
        },
        "queue": {
            "activeDagCount": 1,
            "totalQueueLen": 1,
        },
        "time": {
            "simTime": 1234.0,
        },
    }


def test_env_observation_and_mask():
    env = SchedulingEnvironment(num_edge_dc=8, num_cloud_dc=1)
    state = build_sample_state()

    obs = env._get_obs(state)
    expected_size = 2 + (6 * env.total_dc)

    assert obs.shape == (expected_size,), f"unexpected obs shape: {obs.shape}"
    assert obs.dtype == np.float32, f"unexpected obs dtype: {obs.dtype}"
    assert np.all(obs >= 0.0) and np.all(obs <= 1.0), "observation values must be in [0, 1]"

    mask = env._get_action_mask(state)
    assert mask.shape == (2, env.max_dc), f"unexpected mask shape: {mask.shape}"
    assert bool(mask[0, 0]) is True, "edge dc 0 should be selectable"
    assert bool(mask[0, 2]) is True, "edge dc 2 should be selectable"
    assert bool(mask[1, 0]) is True, "cloud dc 0 should be selectable"
    assert bool(mask[0, 1]) is False, "edge dc 1 should not be selectable"
    assert bool(mask[1, 1]) is False, "cloud dc 1 should not be selectable"

    print("env observation and mask test passed")


def test_bridge_endpoints():
    state = build_sample_state()

    with app.test_client() as client:
        act_resp = client.post(
            "/act",
            json={
                "state": state,
                "trainingMode": True,
                "actionMask": [1, 1, 1],
            },
        )

        assert act_resp.status_code == 200, act_resp.get_data(as_text=True)
        act_json = act_resp.get_json()
        assert act_json["tier"] in {"EDGE", "CLOUD"}
        assert isinstance(act_json["datacenterId"], int)
        assert isinstance(act_json["vmId"], int)

        if act_json["tier"] == "EDGE":
            valid_vm_ids = {
                vm["vmId"]
                for vm in state["cluster"]["edgeVms"]
                if vm["dcId"] == act_json["datacenterId"]
            }
        else:
            valid_vm_ids = {
                vm["vmId"]
                for vm in state["cluster"]["cloudVms"]
                if vm["dcId"] == act_json["datacenterId"]
            }
        assert act_json["vmId"] in valid_vm_ids, "returned vmId does not belong to selected datacenter"

        observe_resp = client.post(
            "/observe",
            json={
                "state": state,
                "action": act_json,
                "reward": -0.5,
                "next_state": state,
                "done": False,
                "info": {
                    "actualLatency": 25.0,
                    "actualCost": 2.5,
                    "costSoFar": 2.5,
                    "budget": 10.0,
                    "budgetViolated": False,
                },
            },
        )

        assert observe_resp.status_code == 200, observe_resp.get_data(as_text=True)
        observe_json = observe_resp.get_json()
        assert observe_json["status"] == "ok"
        assert observe_json["reward"] == -0.5

    print("bridge endpoint test passed")


if __name__ == "__main__":
    test_env_observation_and_mask()
    test_bridge_endpoints()
    print("all tests passed")
