package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Earliest Finish Time (EFT) scheduling policy.
 * Schedules each task to the VM that minimizes estimated finish time.
 */
public class EFTPolicy implements SchedulingPolicy {
    private static final int DEBUG_TASK_LIMIT = 5;
    private int debugTaskCount = 0;

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision bestDecision = new PlacementDecision();
        double bestResponseDoneTimeMs = Double.MAX_VALUE;
        boolean logDebug = debugTaskCount < DEBUG_TASK_LIMIT;
        StringBuilder debugLog = logDebug ? new StringBuilder() : null;

        if (logDebug) {
            debugLog.append(String.format("[EFT_DEBUG] task=%s currentTimeMs=%.2f%n",
                    task.taskId, state.currentTimeMs));
        }
        
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
                    
                    double processingTimeMs = (task.lengthMI / vmInfo.mips) * 1000.0;
                    double taskReadyAtDcMs = state.currentTimeMs + vmInfo.estimatedUploadDelayMs;
                    double vmStartMs = Math.max(taskReadyAtDcMs, vmInfo.estimatedAvailableTimeMs);
                    double vmFinishMs = vmStartMs + processingTimeMs;
                    double responseDoneMs = vmFinishMs + vmInfo.estimatedDownloadDelayMs;

                    if (logDebug) {
                        debugLog.append(String.format(
                                "[EFT_DEBUG] candidate tier=%s dc=%d vm=%d uploadMs=%.2f vmAvailableMs=%.2f vmStartMs=%.2f processingMs=%.2f downloadMs=%.2f responseDoneMs=%.2f%n",
                                tierName(tier),
                                vmInfo.datacenterId,
                                vmInfo.vmId,
                                vmInfo.estimatedUploadDelayMs,
                                vmInfo.estimatedAvailableTimeMs,
                                vmStartMs,
                                processingTimeMs,
                                vmInfo.estimatedDownloadDelayMs,
                                responseDoneMs));
                    }
                    //find best done time, whichever vm finishes earliest time gets dc + vm saved
                    if (responseDoneMs < bestResponseDoneTimeMs) {
                    bestResponseDoneTimeMs = responseDoneMs;
                    bestDecision.destTier = tier;
                    bestDecision.destDatacenterId = dc;
                    bestDecision.destVmId = vm;
                    bestDecision.estimatedFinishTimeMs = responseDoneMs;
                    bestDecision.estimatedNetworkDelayMs = vmInfo.estimatedUploadDelayMs + vmInfo.estimatedDownloadDelayMs;
                    }
                }
            }
        }
        
        // If no feasible VM found, use first available
        if (bestResponseDoneTimeMs == Double.MAX_VALUE) {
            if (state.vms.length > 0 && state.vms[0] != null && state.vms[0].length > 0 &&
                state.vms[0][0] != null && state.vms[0][0].length > 0) {
                bestDecision.destTier = 0;
                bestDecision.destDatacenterId = 0;
                bestDecision.destVmId = 0;
            } else if (state.vms.length > 1 && state.vms[1] != null && state.vms[1].length > 0 && state.vms[1][0] != null && state.vms[1][0].length > 0) {
                bestDecision.destTier = 1;
                bestDecision.destDatacenterId = 0;
                bestDecision.destVmId = 0;
            }
        }

        if (logDebug) {
            debugLog.append(String.format("[EFT_DEBUG] selected tier=%s dc=%d vm=%d responseDoneMs=%.2f%n",
                    tierName(bestDecision.destTier),
                    bestDecision.destDatacenterId,
                    bestDecision.destVmId,
                    bestResponseDoneTimeMs));
            System.out.print(debugLog.toString());
            debugTaskCount++;
        }
        return bestDecision;
	}

    private String tierName(int tier) {
        if (tier == PlacementDecision.TIER_EDGE) {
            return "EDGE";
        }
        if (tier == PlacementDecision.TIER_CLOUD) {
            return "CLOUD";
        }
        return "UNKNOWN";
    }

    @Override
    public String getPolicyName() {
        return "EFT";
    }
}
