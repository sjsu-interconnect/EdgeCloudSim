package edu.boun.edgecloudsim.dagsim;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single task within a DAG.
 * Tracks task properties, dependencies, and runtime state.
 */
public class TaskRecord {
    private String taskId;
    private String taskType;
    
    // Task characteristics
    private double durationMs;
    private double memoryMb;
    private double gpuMemoryMb;
    private double gpuUtilization;
    
    // Dependencies
    private List<String> dependsOn;
    private List<String> children;
    
    // Runtime state
    private int remainingDeps;
    private TaskState state;
    private double readyTimeMs;
    private double scheduledTimeMs;
    private double startTimeMs;
    private double finishTimeMs;
    
    // Placement info
    private int assignedTier; // 0=edge, 1=cloud
    private int assignedDatacenterId;
    private int assignedVmId;
    
    // CloudSim/EdgeCloudSim cloudlet ID
    private long cloudletId;
    
    public enum TaskState {
        CREATED, READY, SCHEDULED, RUNNING, DONE
    }
    
    public TaskRecord() {
        this.dependsOn = new ArrayList<>();
        this.children = new ArrayList<>();
        this.state = TaskState.CREATED;
        this.remainingDeps = 0;
        this.assignedTier = -1;
        this.assignedDatacenterId = -1;
        this.assignedVmId = -1;
        this.cloudletId = -1;
    }
    
    // Getters and Setters
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    
    public double getDurationMs() { return durationMs; }
    public void setDurationMs(double durationMs) { this.durationMs = durationMs; }
    
    public double getMemoryMb() { return memoryMb; }
    public void setMemoryMb(double memoryMb) { this.memoryMb = memoryMb; }
    
    public double getGpuMemoryMb() { return gpuMemoryMb; }
    public void setGpuMemoryMb(double gpuMemoryMb) { this.gpuMemoryMb = gpuMemoryMb; }
    
    public double getGpuUtilization() { return gpuUtilization; }
    public void setGpuUtilization(double gpuUtilization) { this.gpuUtilization = gpuUtilization; }
    
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    
    public List<String> getChildren() { return children; }
    public void setChildren(List<String> children) { this.children = children; }
    
    public int getRemainingDeps() { return remainingDeps; }
    public void setRemainingDeps(int remainingDeps) { this.remainingDeps = remainingDeps; }
    public void decrementRemainingDeps() { this.remainingDeps--; }
    
    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }
    
    public double getReadyTimeMs() { return readyTimeMs; }
    public void setReadyTimeMs(double readyTimeMs) { this.readyTimeMs = readyTimeMs; }
    
    public double getScheduledTimeMs() { return scheduledTimeMs; }
    public void setScheduledTimeMs(double scheduledTimeMs) { this.scheduledTimeMs = scheduledTimeMs; }
    
    public double getStartTimeMs() { return startTimeMs; }
    public void setStartTimeMs(double startTimeMs) { this.startTimeMs = startTimeMs; }
    
    public double getFinishTimeMs() { return finishTimeMs; }
    public void setFinishTimeMs(double finishTimeMs) { this.finishTimeMs = finishTimeMs; }
    
    public int getAssignedTier() { return assignedTier; }
    public void setAssignedTier(int tier) { this.assignedTier = tier; }
    
    public int getAssignedDatacenterId() { return assignedDatacenterId; }
    public void setAssignedDatacenterId(int dcId) { this.assignedDatacenterId = dcId; }
    
    public int getAssignedVmId() { return assignedVmId; }
    public void setAssignedVmId(int vmId) { this.assignedVmId = vmId; }
    
    public long getCloudletId() { return cloudletId; }
    public void setCloudletId(long cloudletId) { this.cloudletId = cloudletId; }
    
    @Override
    public String toString() {
        return String.format("Task[%s, type=%s, dur=%.2fms, state=%s]", 
            taskId, taskType, durationMs, state);
    }
}
