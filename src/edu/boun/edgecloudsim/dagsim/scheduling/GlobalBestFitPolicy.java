package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Global best-fit scheduling policy.
 * Scans all VMs across edge and cloud, selects the feasible VM
 * with the smallest remaining capacity slack.
 */
public class GlobalBestFitPolicy implements SchedulingPolicy {

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision decision = new PlacementDecision();

        ClusterState.VMInfo bestVm = null;
        int bestTier = PlacementDecision.TIER_EDGE;
        int bestDc = 0;
        int bestVmIdx = 0;
        double bestScore = Double.MAX_VALUE;

        if (state.vms != null) {
            for (int tier = 0; tier < state.vms.length; tier++) {
                if (state.vms[tier] == null) {
                    continue;
                }
                for (int dc = 0; dc < state.vms[tier].length; dc++) {
                    if (state.vms[tier][dc] == null) {
                        continue;
                    }
                    for (int vm = 0; vm < state.vms[tier][dc].length; vm++) {
                        ClusterState.VMInfo vmInfo = state.vms[tier][dc][vm];
                        if (vmInfo == null) {
                            continue;
                        }
                        if (!vmInfo.canFitTask(task.cpuMemoryMb, task.gpuMemoryMb)) {
                            continue;
                        }
                        double memSlack = Math.max(0.0, vmInfo.freeMemoryMb - task.cpuMemoryMb);
                        double gpuSlack = Math.max(0.0, vmInfo.freeGpuMemoryMb - task.gpuMemoryMb);
                        double score = memSlack + gpuSlack;
                        if (score < bestScore) {
                            bestScore = score;
                            bestVm = vmInfo;
                            bestTier = tier;
                            bestDc = dc;
                            bestVmIdx = vm;
                        }
                    }
                }
            }
        }

        if (bestVm != null) {
            decision.destTier = bestTier;
            decision.destDatacenterId = bestDc;
            decision.destVmId = bestVmIdx;
            return decision;
        }

        // Fallback: pick the first available VM anywhere (may overflow)
        if (state.vms != null) {
            for (int tier = 0; tier < state.vms.length; tier++) {
                if (state.vms[tier] == null) {
                    continue;
                }
                for (int dc = 0; dc < state.vms[tier].length; dc++) {
                    if (state.vms[tier][dc] != null && state.vms[tier][dc].length > 0) {
                        decision.destTier = tier;
                        decision.destDatacenterId = dc;
                        decision.destVmId = 0;
                        return decision;
                    }
                }
            }
        }

        return decision;
    }

    @Override
    public String getPolicyName() {
        return "GlobalBestFit";
    }
}
