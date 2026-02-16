package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Round-robin scheduling policy.
 * Cycles through all VMs for basic load balancing.
 */
public class RoundRobinPolicy implements SchedulingPolicy {
    
    private int nextVmIndex = 0;
    
    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision decision = new PlacementDecision();
        
        // Prefer edge if has VMs
        if (state.vms.length > 0 && state.vms[PlacementDecision.TIER_EDGE] != null) {
            decision.destTier = PlacementDecision.TIER_EDGE;
        } else {
            decision.destTier = PlacementDecision.TIER_CLOUD;
        }
        
        // Round-robin through datacenters and VMs
        int tierIdx = decision.destTier;
        int totalVms = countVmsInTier(state, tierIdx);
        if (totalVms > 0) {
            int selectedVm = nextVmIndex % totalVms;
            nextVmIndex++;
            
            int vmCounter = 0;
            for (int dc = 0; dc < state.vms[tierIdx].length; dc++) {
                if (state.vms[tierIdx][dc] != null) {
                    for (int vm = 0; vm < state.vms[tierIdx][dc].length; vm++) {
                        if (vmCounter == selectedVm) {
                            decision.destDatacenterId = dc;
                            decision.destVmId = vm;
                            return decision;
                        }
                        vmCounter++;
                    }
                }
            }
        }
        
        return decision;
    }
    
    private int countVmsInTier(ClusterState state, int tier) {
        int count = 0;
        if (state.vms.length > tier && state.vms[tier] != null) {
            for (ClusterState.VMInfo[] dcVms : state.vms[tier]) {
                if (dcVms != null) {
                    count += dcVms.length;
                }
            }
        }
        return count;
    }
    
    @Override
    public String getPolicyName() {
        return "RoundRobin";
    }
}
