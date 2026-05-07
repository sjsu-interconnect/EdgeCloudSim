"""
redis_bridge.py  —  replaces edgecloudsim_to_rl_bridge.py

Uses Redis blocking queues instead of threading Condition/RLock.
Flask and training.py run as two separate processes with no shared
memory, no locks, no threads.

Three queues:
rl:act_queue     — Flask pushes /act state here, training.py pops
rl:action_queue  — training.py pushes chosen action here, Flask pops
rl:observe_queue — Flask pushes /observe reward here, training.py pops
"""

import json
import redis

REDIS_HOST = "localhost"
REDIS_PORT = 6379

ACT_QUEUE     = "rl:act_queue"
ACTION_QUEUE  = "rl:action_queue"
OBSERVE_QUEUE = "rl:observe_queue"
REQUEST_ID    = "rl:request_id"


class RedisBridge:
    def __init__(self, host=REDIS_HOST, port=REDIS_PORT):
        self.r = redis.Redis(host=host, port=port, decode_responses=True)

    def flush(self):
        """Clear all Redis data — call once at flask_server.py startup."""
        self.r.flushall()
        print("[RedisBridge] All Redis data flushed.")

    # ── Flask side ─────────────────────────────────────────────────────────────

    def push_act_request(self, state, action_mask):
        """Flask: push incoming /act state onto queue, return request id."""
        request_id = self.r.incr(REQUEST_ID)
        payload = json.dumps({
            "state":      state,
            "action_mask": action_mask,
            "request_id": int(request_id),
        })
        self.r.lpush(ACT_QUEUE, payload)
        return int(request_id)

    def pop_action(self, timeout_seconds):
        """Flask: block-wait for action from training.py."""
        result = self.r.brpop(ACTION_QUEUE, timeout=int(timeout_seconds))
        if result is None:
            raise TimeoutError(
                f"Timed out after {timeout_seconds}s waiting for action from training.py"
            )
        _, value = result
        return json.loads(value)

    def push_observe(self, reward, next_state, done, info):
        """Flask: push incoming /observe payload onto queue."""
        payload = json.dumps({
            "reward":     reward,
            "next_state": next_state,
            "done":       done,
            "info":       info or {},
        })
        self.r.lpush(OBSERVE_QUEUE, payload)

    def get_request_id(self):
        val = self.r.get(REQUEST_ID)
        return int(val) if val else 0

    # ── Training / environment side ────────────────────────────────────────────

    def pop_act_request(self, timeout_seconds):
        """training.py: block-wait for next /act from EdgeCloudSim via Flask.
        Returns {"state": ..., "action_mask": ..., "request_id": ...}"""
        result = self.r.brpop(ACT_QUEUE, timeout=int(timeout_seconds))
        if result is None:
            raise TimeoutError(
                f"Timed out after {timeout_seconds}s waiting for /act request"
            )
        _, value = result
        return json.loads(value)

    def push_action(self, action_json):
        """training.py: push chosen action — Flask is blocking on pop_action()."""
        self.r.lpush(ACTION_QUEUE, json.dumps(action_json))

    def pop_observe(self, timeout_seconds):
        """training.py: block-wait for /observe reward from EdgeCloudSim via Flask.
        Returns {"reward": ..., "next_state": ..., "done": ..., "info": ...}"""
        result = self.r.brpop(OBSERVE_QUEUE, timeout=int(timeout_seconds))
        if result is None:
            raise TimeoutError(
                f"Timed out after {timeout_seconds}s waiting for /observe"
            )
        _, value = result
        return json.loads(value)


# Singleton — imported by both edgecloudsim_to_rl_server.py and rl_environment.py
redis_bridge = RedisBridge()