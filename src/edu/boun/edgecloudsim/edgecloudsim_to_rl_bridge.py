# class BridgeState:
#     def __init__(self):
#         #first state of an episode, used by env.reset()
#         self.initial_state = None

#         #action chosen inside env.step(action), used by /act
#         self.pending_action_json = None

#         #transition received by /observe, used by env.step(action)
#         self.pending_transition = None

#         #episode flag
#         self.episode_started = False


# bridge_state = BridgeState()
from __future__ import annotations

import time
from threading import Condition, RLock
from typing import Any, Dict, Optional


class BridgeState:
    def __init__(self) -> None:
        self._lock = RLock()
        self._cv = Condition(self._lock)

        # First state of a new episode, consumed by env.reset()
        self.initial_state: Optional[Dict[str, Any]] = None

        # Current act request payload
        self.current_state: Optional[Dict[str, Any]] = None
        self.current_action_mask: Optional[list] = None

        # Action produced by env.step(action), consumed by /act
        self.pending_action_json: Optional[Dict[str, Any]] = None

        # Transition produced by /observe, consumed by env.step(action)
        self.pending_transition: Optional[Dict[str, Any]] = None

        # Episode / handshake flags
        self.episode_started: bool = False
        self.waiting_for_action: bool = False
        self.waiting_for_transition: bool = False
        self.episode_done: bool = False

        # Debug counters
        self.act_request_id: int = 0
        self.observe_request_id: int = 0

    def reset_for_new_episode(self) -> None:
        with self._cv:
            self.initial_state = None
            self.current_state = None
            self.current_action_mask = None
            self.pending_action_json = None
            self.pending_transition = None
            self.episode_started = False
            self.waiting_for_action = False
            self.waiting_for_transition = False
            self.episode_done = False
            self._cv.notify_all()

    def publish_act_request(self, state: Dict[str, Any], action_mask: Optional[list]) -> None:
        with self._cv:
            self.current_state = state
            self.current_action_mask = action_mask
            self.pending_action_json = None
            self.waiting_for_action = True

            if not self.episode_started and self.initial_state is None:
                self.initial_state = state

            self.act_request_id += 1
            self._cv.notify_all()

    def wait_for_initial_state(self, timeout_seconds: float) -> Dict[str, Any]:
        deadline = time.time() + timeout_seconds
        with self._cv:
            while self.initial_state is None:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError("Timed out waiting for initial state from EdgeCloudSim")
                self._cv.wait(timeout=remaining)

            state = self.initial_state
            self.initial_state = None
            self.episode_started = True
            return state

    def submit_action(self, action_json: Dict[str, Any]) -> None:
        with self._cv:
            self.pending_action_json = action_json
            self._cv.notify_all()

    def wait_for_action(self, timeout_seconds: float) -> Dict[str, Any]:
        deadline = time.time() + timeout_seconds
        with self._cv:
            while self.pending_action_json is None:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError("Timed out waiting for action from env.step()/agent")
                self._cv.wait(timeout=remaining)

            action = self.pending_action_json
            self.pending_action_json = None
            self.waiting_for_action = False
            self.waiting_for_transition = True
            self._cv.notify_all()
            return action

    def publish_transition(
        self,
        reward: float,
        next_state: Dict[str, Any],
        done: bool,
        info: Optional[Dict[str, Any]] = None,
    ) -> None:
        with self._cv:
            self.pending_transition = {
                "reward": float(reward),
                "next_state": next_state,
                "done": bool(done),
                "info": info or {},
            }
            self.waiting_for_transition = False
            self.episode_done = bool(done)
            self.observe_request_id += 1
            self._cv.notify_all()

    def wait_for_transition(self, timeout_seconds: float) -> Dict[str, Any]:
        deadline = time.time() + timeout_seconds
        with self._cv:
            while self.pending_transition is None:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError("Timed out waiting for transition from EdgeCloudSim")
                self._cv.wait(timeout=remaining)

            transition = self.pending_transition
            self.pending_transition = None

            if transition["done"]:
                self.episode_started = False
                self.current_state = None
                self.current_action_mask = None

            return transition

    def get_current_state(self) -> Optional[Dict[str, Any]]:
        with self._cv:
            return self.current_state

    def get_current_action_mask(self) -> Optional[list]:
        with self._cv:
            return self.current_action_mask
        
    def get_act_request_id(self) -> int:
        with self._cv:
            return self.act_request_id
        
    def wait_for_next_act_request(self, previous_request_id: int, timeout_seconds: float) -> Dict[str, Any]:
        deadline = time.time() + timeout_seconds
        with self._cv:
            while self.act_request_id <= previous_request_id:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError("Timed out waiting for next /act request from EdgeCloudSim")
                self._cv.wait(timeout=remaining)
            return self.current_state


bridge_state = BridgeState()