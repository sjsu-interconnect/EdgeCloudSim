package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Edge-first feasible scheduling policy.
 * Tries to place on edge if feasible, falls back to cloud.
 */
public class EdgeFirstFeasiblePolicy implements SchedulingPolicy {
    
    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision decision = new PlacementDecision();
        
        // Try edge tier first
        boolean edgeFeasible = tryTier(state, PlacementDecision.TIER_EDGE, task, decision);
        if (edgeFeasible) {
            return decision;
        }
        
        // Fall back to cloud
        decision.destTier = PlacementDecision.TIER_CLOUD;
        tryTier(state, PlacementDecision.TIER_CLOUD, task, decision);
        
        return decision;
    }
    
    /**
     * Try to find a feasible VM in the given tier.
     * Returns true if found, updates decision in place.
     */
    private boolean tryTier(ClusterState state, int tier, TaskContext task, PlacementDecision decision) {
        if (state.vms.length <= tier || state.vms[tier] == null) {
            return false;
        }
        
        // Find first VM with enough memory
        for (int dc = 0; dc < state.vms[tier].length; dc++) {
            if (state.vms[tier][dc] != null) {
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
        }
        
        // No feasible VM found, place on first available (may overflow)
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
        return "EdgeFirstFeasible";
    }
}
