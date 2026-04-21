class BridgeState:
    def __init__(self):
        #first state of an episode, used by env.reset()
        self.initial_state = None

        #most recent state received by /act
        self.latest_act_state = None

        #action chosen inside env.step(action), used by /act
        self.pending_action_json = None

        #transition received by /observe, used by env.step(action)
        self.pending_transition = None

        #episode flag
        self.episode_started = False


bridge_state = BridgeState()