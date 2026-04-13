package edu.boun.edgecloudsim.dagsim.scheduling;

import edu.boun.edgecloudsim.core.SimSettings;

/**
 * Network-aware Earliest Finish Time (EFT) scheduling policy.
 * Estimates finish time using execution, queue, and network transfer delays.
 */
public class NetAwareEFTPolicy implements SchedulingPolicy {

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        PlacementDecision bestDecision = new PlacementDecision();
        double bestFinishTime = Double.MAX_VALUE;

        SimSettings ss = SimSettings.getInstance();
        double dataBytes = estimateDataBytes(task, ss);

        // Evaluate all candidate VMs
        for (int tier = 0; tier < state.vms.length; tier++) {
            if (state.vms[tier] == null) continue;

            for (int dc = 0; dc < state.vms[tier].length; dc++) {
                if (state.vms[tier][dc] == null) continue;

                for (int vm = 0; vm < state.vms[tier][dc].length; vm++) {
                    ClusterState.VMInfo vmInfo = state.vms[tier][dc][vm];
                    if (vmInfo == null) continue;

                    if (!vmInfo.canFitTask(task.cpuMemoryMb, task.gpuMemoryMb)) {
                        continue;
                    }

                    double execTime = (task.lengthMI / vmInfo.mips) * 1000.0; // ms
                    double queueTime = vmInfo.totalQueueWaitTimeMs;
                    double netDelay = estimateNetworkDelayMs(tier, dataBytes, ss);
                    double finishTime = state.currentTimeMs + queueTime + execTime + netDelay;

                    if (finishTime < bestFinishTime) {
                        bestFinishTime = finishTime;
                        bestDecision.destTier = tier;
                        bestDecision.destDatacenterId = dc;
                        bestDecision.destVmId = vm;
                        bestDecision.estimatedFinishTimeMs = finishTime;
                        bestDecision.estimatedNetworkDelayMs = netDelay;
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

    private double estimateNetworkDelayMs(int tier, double dataBytes, SimSettings ss) {
        double bandwidthKbps;
        double propagationSec;
        if (tier == PlacementDecision.TIER_CLOUD) {
            bandwidthKbps = Math.max(1.0, ss.getWanBandwidth());
            propagationSec = ss.getWanPropagationDelay();
        } else {
            bandwidthKbps = Math.max(1.0, ss.getWlanBandwidth());
            propagationSec = ss.getEdgePropagationDelay();
        }

        double bps = (bandwidthKbps * 1000.0) / 8.0;
        double transferSec = dataBytes / bps;
        return (propagationSec + transferSec) * 1000.0;
    }

    private double estimateDataBytes(TaskContext task, SimSettings ss) {
        double[] props = ss.getTaskProperties(task.taskType);
        if (props != null && props.length > 6) {
            double uploadKb = props[5];
            double downloadKb = props[6];
            return Math.max(1.0, (uploadKb + downloadKb)) * 1024.0;
        }
        // Fallback: approximate using memory footprint
        return Math.max(1.0, task.cpuMemoryMb) * 1024.0 * 1024.0;
    }

    @Override
    public String getPolicyName() {
        return "NetAwareEFT";
    }
}
