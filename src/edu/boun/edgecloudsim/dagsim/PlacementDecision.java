package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Represents the result of a scheduling decision.
 * Specifies which tier and VM a task should be scheduled to.
 */
public class PlacementDecision {
    public static final int TIER_EDGE = 0;
    public static final int TIER_CLOUD = 1;
    
    public int destTier; // TIER_EDGE or TIER_CLOUD
    public int destDatacenterId; // Specific datacenter index within tier
    public int destVmId; // Specific VM index within datacenter
    
    // Optional cost/latency estimate for logging
    public double estimatedFinishTimeMs;
    public double estimatedNetworkDelayMs;
    
    public PlacementDecision() {
        this.destTier = TIER_EDGE;
        this.destDatacenterId = 0;
        this.destVmId = 0;
        this.estimatedFinishTimeMs = -1;
        this.estimatedNetworkDelayMs = 0;
    }
    
    public PlacementDecision(int tier, int dcId, int vmId) {
        this.destTier = tier;
        this.destDatacenterId = dcId;
        this.destVmId = vmId;
        this.estimatedFinishTimeMs = -1;
        this.estimatedNetworkDelayMs = 0;
    }
    
    public String getTierName() {
        return (destTier == TIER_EDGE) ? "EDGE" : "CLOUD";
    }
    
    @Override
    public String toString() {
        return String.format("PlacementDecision[%s dc=%d vm=%d, est_finish=%.0f ms]",
            getTierName(), destDatacenterId, destVmId, estimatedFinishTimeMs);
    }
}
