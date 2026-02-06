package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Interface for pluggable scheduling policies.
 * Implementations decide where and on which VM to schedule a task.
 */
public interface SchedulingPolicy {
    
    /**
     * Decide where to schedule a task.
     * 
     * @param task Context information about the task
     * @param state Current cluster state (read-only)
     * @return PlacementDecision specifying tier, datacenter, and VM
     */
    PlacementDecision decide(TaskContext task, ClusterState state);
    
    /**
     * Get human-readable policy name.
     */
    String getPolicyName();
}
