package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Edge-first global policy.
 * Scans all edge VMs for a feasible placement; if none fits,
 * scans all cloud VMs. Falls back to the first available VM.
 */
public class EdgeFirstGlobalPolicy implements SchedulingPolicy {

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision decision = new PlacementDecision();

        if (tryTier(state, PlacementDecision.TIER_EDGE, task, decision)) {
            return decision;
        }

        decision.destTier = PlacementDecision.TIER_CLOUD;
        tryTier(state, PlacementDecision.TIER_CLOUD, task, decision);
        return decision;
    }

    private boolean tryTier(ClusterState state, int tier, TaskContext task, PlacementDecision decision) {
        if (state.vms == null || state.vms.length <= tier || state.vms[tier] == null) {
            return false;
        }

        for (int dc = 0; dc < state.vms[tier].length; dc++) {
            if (state.vms[tier][dc] == null) {
                continue;
            }
            for (int vm = 0; vm < state.vms[tier][dc].length; vm++) {
                ClusterState.VMInfo vmInfo = state.vms[tier][dc][vm];
                if (vmInfo != null && vmInfo.canFitTask(task.cpuMemoryMb, task.gpuMemoryMb)) {
                    decision.destTier = tier;
                    decision.destDatacenterId = dc;
                    decision.destVmId = vm;
                    return true;
                }
            }
        }

        if (state.vms[tier] != null && state.vms[tier].length > 0 &&
                state.vms[tier][0] != null && state.vms[tier][0].length > 0) {
            decision.destTier = tier;
            decision.destDatacenterId = 0;
            decision.destVmId = 0;
            return true;
        }

        return false;
    }

    @Override
    public String getPolicyName() {
        return "EdgeFirstGlobal";
    }
}
