package edu.boun.edgecloudsim.dagsim.scheduling;

/**
 * Context for a task scheduling decision.
 * Contains all relevant information about the task and its readiness state.
 */
public class TaskContext {
    public String dagId;
    public String taskId;
    public String taskType;
    
    // Task resources
    public double lengthMI; // Length in million instructions (for CloudSim)
    public double gpuMemoryMb;
    public double gpuUtilizationPercent;
    public double cpuMemoryMb;
    
    // Timing
    public double readyTimeMs; // When task became ready
    public double currentTimeMs; // Current simulation time
    
    // Task graph properties
    public int numDependencies;
    public int numDependents;
    
    public TaskContext() {
    }
    
    @Override
    public String toString() {
        return String.format("TaskContext[%s@%s, len=%.0f MI, gpu=%.0f MB, ready=%.0f ms]",
            taskId, taskType, lengthMI, gpuMemoryMb, readyTimeMs);
    }
}
