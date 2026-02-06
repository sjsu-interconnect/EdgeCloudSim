package edu.boun.edgecloudsim.dagsim;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Directed Acyclic Graph (DAG) corresponding to a single inference request.
 * Contains task records, metadata, and runtime state.
 */
public class DagRecord {
    private String dagId;
    private double submissionTimeEpochSec;
    private long submitAtSimMs; // Relative to simulation start
    
    // Request metadata
    private int numInferenceSteps;
    private int promptLength;
    private int numImages;
    private boolean hasLora;
    private boolean hasControlnet;
    
    // Tasks indexed by ID
    private Map<String, TaskRecord> tasksById;
    
    // Runtime state
    private DagState state;
    private double completeTimeMs;
    private int totalTasks;
    private int completedTasks;
    
    public enum DagState {
        CREATED, SUBMITTED, RUNNING, COMPLETE
    }
    
    public DagRecord() {
        this.tasksById = new HashMap<>();
        this.state = DagState.CREATED;
        this.completedTasks = 0;
        this.totalTasks = 0;
    }
    
    // Getters and Setters
    public String getDagId() { return dagId; }
    public void setDagId(String dagId) { this.dagId = dagId; }
    
    public double getSubmissionTimeEpochSec() { return submissionTimeEpochSec; }
    public void setSubmissionTimeEpochSec(double time) { this.submissionTimeEpochSec = time; }
    
    public long getSubmitAtSimMs() { return submitAtSimMs; }
    public void setSubmitAtSimMs(long submitAtSimMs) { this.submitAtSimMs = submitAtSimMs; }
    
    public int getNumInferenceSteps() { return numInferenceSteps; }
    public void setNumInferenceSteps(int steps) { this.numInferenceSteps = steps; }
    
    public int getPromptLength() { return promptLength; }
    public void setPromptLength(int length) { this.promptLength = length; }
    
    public int getNumImages() { return numImages; }
    public void setNumImages(int numImages) { this.numImages = numImages; }
    
    public boolean isHasLora() { return hasLora; }
    public void setHasLora(boolean hasLora) { this.hasLora = hasLora; }
    
    public boolean isHasControlnet() { return hasControlnet; }
    public void setHasControlnet(boolean hasControlnet) { this.hasControlnet = hasControlnet; }
    
    public Map<String, TaskRecord> getTasksById() { return tasksById; }
    
    public TaskRecord getTask(String taskId) { return tasksById.get(taskId); }
    public void addTask(String taskId, TaskRecord task) { 
        tasksById.put(taskId, task);
        this.totalTasks++;
    }
    
    public DagState getState() { return state; }
    public void setState(DagState state) { this.state = state; }
    
    public double getCompleteTimeMs() { return completeTimeMs; }
    public void setCompleteTimeMs(double completeTimeMs) { this.completeTimeMs = completeTimeMs; }
    
    public int getTotalTasks() { return totalTasks; }
    
    public int getCompletedTasks() { return completedTasks; }
    public void incrementCompletedTasks() { this.completedTasks++; }
    
    public double getMakespanMs() {
        if (state == DagState.COMPLETE) {
            return completeTimeMs - submitAtSimMs;
        }
        return -1; // Not yet complete
    }
    
    public boolean isComplete() {
        return completedTasks == totalTasks;
    }
    
    @Override
    public String toString() {
        return String.format("DAG[%s, submit=%.1fEpoch, tasks=%d, state=%s]",
            dagId, submissionTimeEpochSec, totalTasks, state);
    }
}
