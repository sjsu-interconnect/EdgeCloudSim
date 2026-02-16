package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Cluster state snapshot for scheduling decisions.
 * Provides read-only view of current cluster resources and task queues.
 */
public class ClusterState {

    public static class VMInfo {
        public int vmId;
        public int datacenterId;
        public int tier; // 0=edge, 1=cloud
        public double mips;
        public double freeMemoryMb;
        public double freeGpuMemoryMb;
        public int queuedTaskCount;
        public double totalQueueWaitTimeMs; // Estimate for pending tasks

        public VMInfo(int vmId, int dcId, int tier, double mips) {
            this.vmId = vmId;
            this.datacenterId = dcId;
            this.tier = tier;
            this.mips = mips;
            this.freeMemoryMb = Double.MAX_VALUE;
            this.freeGpuMemoryMb = Double.MAX_VALUE;
            this.queuedTaskCount = 0;
            this.totalQueueWaitTimeMs = 0;
        }

        public boolean canFitTask(double requiredMemoryMb, double requiredGpuMemoryMb) {
            return freeMemoryMb >= requiredMemoryMb && freeGpuMemoryMb >= requiredGpuMemoryMb;
        }

        @Override
        public String toString() {
            return String.format("VM[id=%d, dc=%d, tier=%d, mips=%.0f, mem=%.0f/gpu=%.0f]",
                    vmId, datacenterId, tier, mips, freeMemoryMb, freeGpuMemoryMb);
        }
    }

    // Current simulation time
    public double currentTimeMs;

    // All available VMs organized by tier
    public VMInfo[][][] vms; // [tier][dc_index][vm_index]

    // Tier-level stats
    public int edgeTierCount;
    public int cloudTierCount;

    public ClusterState(double currentTimeMs) {
        this.currentTimeMs = currentTimeMs;
    }

    /**
     * Get average MIPS for a tier (for estimation).
     */
    public double getAverageMipsForTier(int tier) {
        if (tier < 0 || tier >= vms.length || vms[tier] == null) {
            return 1000; // Default fallback
        }

        double totalMips = 0;
        int count = 0;
        for (VMInfo[] dcVms : vms[tier]) {
            if (dcVms != null) {
                for (VMInfo vm : dcVms) {
                    if (vm != null) {
                        totalMips += vm.mips;
                        count++;
                    }
                }
            }
        }
        return count > 0 ? totalMips / count : 1000;
    }

    @Override
    public String toString() {
        return String.format("ClusterState[time=%.0f ms, edges=%d, clouds=%d]",
                currentTimeMs, edgeTierCount, cloudTierCount);
    }
}
