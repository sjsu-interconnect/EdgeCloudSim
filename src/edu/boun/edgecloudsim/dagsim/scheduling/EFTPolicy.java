package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Earliest Finish Time (EFT) scheduling policy.
 * Schedules each task to the VM that minimizes estimated finish time.
 */
public class EFTPolicy implements SchedulingPolicy {
    
    private static final double NET_PENALTY_MS = 1.0; // Base network penalty in ms
    
    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision bestDecision = new PlacementDecision();
        double bestFinishTime = Double.MAX_VALUE;
        
        // Evaluate all candidate VMs
        for (int tier = 0; tier < state.vms.length; tier++) {
            if (state.vms[tier] == null) continue;
            
            for (int dc = 0; dc < state.vms[tier].length; dc++) {
                if (state.vms[tier][dc] == null) continue;
                
                for (int vm = 0; vm < state.vms[tier][dc].length; vm++) {
                    ClusterState.VMInfo vmInfo = state.vms[tier][dc][vm];
                    if (vmInfo == null) continue;
                    
                    // Check feasibility
                    if (!vmInfo.canFitTask(task.cpuMemoryMb, task.gpuMemoryMb)) {
                        continue;
                    }
                    
                    // Estimate finish time on this VM
                    double execTime = (task.lengthMI / vmInfo.mips) * 1000.0; // Convert to ms
                    double queueTime = vmInfo.totalQueueWaitTimeMs;
                    double netPenalty = (tier == PlacementDecision.TIER_CLOUD) ? NET_PENALTY_MS : 0;
                    double finishTime = state.currentTimeMs + queueTime + execTime + netPenalty;
                    
                    if (finishTime < bestFinishTime) {
                        bestFinishTime = finishTime;
                        bestDecision.destTier = tier;
                        bestDecision.destDatacenterId = dc;
                        bestDecision.destVmId = vm;
                        bestDecision.estimatedFinishTimeMs = finishTime;
                        bestDecision.estimatedNetworkDelayMs = netPenalty;
                    }
                }
            }
        }
        
        // If no feasible VM found, use first available
        if (bestFinishTime == Double.MAX_VALUE) {
            if (state.vms.length > 0 && state.vms[0] != null && state.vms[0].length > 0 &&
                state.vms[0][0] != null && state.vms[0][0].length > 0) {
                bestDecision.destTier = 0;
                bestDecision.destDatacenterId = 0;
                bestDecision.destVmId = 0;
            } else if (state.vms.length > 1 && state.vms[1] != null && state.vms[1].length > 0 &&
                       state.vms[1][0] != null && state.vms[1][0].length > 0) {
                bestDecision.destTier = 1;
                bestDecision.destDatacenterId = 0;
                bestDecision.destVmId = 0;
            }
        }
        
        return bestDecision;
    }
    
    @Override
    public String getPolicyName() {
        return "EFT";
    }
}
