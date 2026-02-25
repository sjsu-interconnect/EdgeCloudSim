package edu.boun.edgecloudsim.dagsim;

import org.cloudbus.cloudsim.*;
import java.util.*;
import java.io.*;

/**
 * TEMPLATE for DagRuntimeManager - Manages DAG lifecycle and task dependencies.
 * 
 * This is a skeleton you should adapt to your specific EdgeCloudSim version.
 * Key responsibilities:
 * - Schedule DAG submissions at correct simulation times
 * - Release tasks when dependencies are satisfied
 * - Track task and DAG completion times
 * - Log scheduling events
 * 
 * Integration points to customize:
 * 1. CloudSim event IDs and scheduling API (may differ in your version)
 * 2. Task/Cloudlet creation (adapt to your EdgeCloudSim classes)
 * 3. Orchestrator integration (where to submit scheduled tasks)
 * 4. Logging output format (may need to match your schema)
 */
public class DagRuntimeManagerTemplate extends SimEntity {
    
    // Event constants
    public static final int DAG_SUBMIT = 7001;
    public static final int TASK_READY = 7002;
    public static final int TASK_FINISHED = 7003;
    
    private List<DagRecord> allDags;
    private Map<String, DagRecord> activeDags;
    private Map<Long, String> cloudletToDagTaskId; // cloudletId -> "dagId:taskId"
    
    // Logging
    private PrintWriter taskLogWriter;
    private PrintWriter dagLogWriter;
    
    public DagRuntimeManagerTemplate(String name, List<DagRecord> dags) 
            throws FileNotFoundException {
        super(name);
        this.allDags = dags;
        this.activeDags = new HashMap<>();
        this.cloudletToDagTaskId = new HashMap<>();
        
        // Initialize log writers
        this.taskLogWriter = new PrintWriter(new FileWriter("task_log.csv"));
        this.dagLogWriter = new PrintWriter(new FileWriter("dag_summary.csv"));
        
        // Write CSV headers
        writeTaskLogHeader();
        writeDagLogHeader();
    }
    
    /**
     * Should be called from your scenario's initialize() method.
     * Schedules all DAG submissions at their appropriate times.
     */
    public void scheduleAllDagSubmissions() {
        for (DagRecord dag : allDags) {
            double submitTimeSeconds = dag.getSubmitAtSimMs() / 1000.0;
            CloudSim.send(getId(), this.getId(), submitTimeSeconds, DAG_SUBMIT, dag);
        }
    }
    
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case DAG_SUBMIT:
                processDagSubmit((DagRecord) ev.getData());
                break;
            case TASK_READY:
                processTaskReady((TaskRecord) ev.getData());
                break;
            case TASK_FINISHED:
                processTaskFinished((TaskRecord) ev.getData());
                break;
            default:
                break;
        }
    }
    
    /**
     * Process DAG submission event.
     * Initialize task states and emit TASK_READY for source tasks.
     */
    private void processDagSubmit(DagRecord dag) {
        double submitTime = CloudSim.clock() * 1000.0;
        dag.setState(DagRecord.DagState.SUBMITTED);
        activeDags.put(dag.getDagId(), dag);
        
        System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] " +
                          "DAG submitted: " + dag.getDagId() + " with " + 
                          dag.getTotalTasks() + " tasks");
        
        // Find and emit TASK_READY for all source tasks (no dependencies)
        for (TaskRecord task : dag.getTasksById().values()) {
            if (task.getDependsOn().isEmpty()) {
                task.setReadyTimeMs(submitTime);
                task.setState(TaskRecord.TaskState.READY);
                
                // Schedule TASK_READY event immediately
                CloudSim.send(getId(), this.getId(), 0, TASK_READY, task);
            }
        }
    }
    
    /**
     * Process TASK_READY event.
     * Submit task to orchestrator for placement decision.
     */
    private void processTaskReady(TaskRecord task) {
        String dagId = findDagIdForTask(task);
        DagRecord dag = activeDags.get(dagId);
        
        if (dag == null) {
            System.err.println("ERROR: DAG not found for task " + task.getTaskId());
            return;
        }
        
        double readyTime = CloudSim.clock() * 1000.0;
        task.setState(TaskRecord.TaskState.SCHEDULED);
        
        // TODO: ADAPT THIS SECTION TO YOUR EDGECLOUDSIM VERSION
        // This is pseudocode - you'll need to:
        // 1. Create a Cloudlet or Task object
        // 2. Submit to your orchestrator
        // 3. Track the cloudlet ID
        
        /*
        // Example (adapt to your classes):
        Cloudlet cloudlet = new Cloudlet(
            getNextCloudletId(),
            (long)(task.getDurationMs() / 1000.0 * 1000),  // MIPS-scaled length
            1,  // number of PEs
            0,  // file size
            0   // output size
        );
        
        cloudlet.setUserId(this.getId());
        
        // Attach task metadata to cloudlet
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dagId", dagId);
        metadata.put("taskId", task.getTaskId());
        metadata.put("taskType", task.getTaskType());
        metadata.put("gpuMemoryMb", task.getGpuMemoryMb());
        cloudlet.setTaskMetadata(metadata);
        
        // Get placement decision (you'll implement orchestrator integration)
        PlacementDecision decision = getPlacementDecision(task);
        
        // Submit to datacenter
        sendCloudletToDatacenter(cloudlet, decision);
        
        // Track mapping
        cloudletToDagTaskId.put(cloudlet.getCloudletId(), dagId + ":" + task.getTaskId());
        */
        
        System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] " +
                          "Task ready: " + task.getTaskId() + " of DAG " + dagId);
    }
    
    /**
     * Process TASK_FINISHED event.
     * Update task state, decrement dependent counts, release children.
     */
    private void processTaskFinished(TaskRecord task) {
        String dagId = findDagIdForTask(task);
        DagRecord dag = activeDags.get(dagId);
        
        if (dag == null) {
            System.err.println("ERROR: DAG not found for completed task " + task.getTaskId());
            return;
        }
        
        double finishTime = CloudSim.clock() * 1000.0;
        task.setFinishTimeMs(finishTime);
        task.setState(TaskRecord.TaskState.DONE);
        
        dag.incrementCompletedTasks();
        
        System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] " +
                          "Task finished: " + task.getTaskId() + 
                          " (" + dag.getCompletedTasks() + "/" + dag.getTotalTasks() + ")");
        
        // Log task completion
        logTaskCompletion(task, dagId);
        
        // Release dependent tasks
        for (String childTaskId : task.getChildren()) {
            TaskRecord child = dag.getTask(childTaskId);
            if (child != null) {
                child.decrementRemainingDeps();
                
                if (child.getRemainingDeps() == 0) {
                    child.setReadyTimeMs(finishTime);
                    child.setState(TaskRecord.TaskState.READY);
                    CloudSim.send(getId(), this.getId(), 0, TASK_READY, child);
                }
            }
        }
        
        // Check if DAG is complete
        if (dag.isComplete()) {
            dag.setState(DagRecord.DagState.COMPLETE);
            dag.setCompleteTimeMs(finishTime);
            
            System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] " +
                              "DAG complete: " + dagId + 
                              " Makespan: " + String.format("%.2f", dag.getMakespanMs()) + " ms");
            
            logDagCompletion(dag);
            activeDags.remove(dagId);
        }
    }
    
    /**
     * Find which DAG a task belongs to.
     * TODO: Optimize with task->dag mapping if needed.
     */
    private String findDagIdForTask(TaskRecord task) {
        for (DagRecord dag : allDags) {
            if (dag.getTask(task.getTaskId()) != null) {
                return dag.getDagId();
            }
        }
        return null;
    }
    
    @Override
    public void shutDown() {
        System.out.println("Shutting down DagRuntimeManager...");
        
        // Close log files
        if (taskLogWriter != null) {
            taskLogWriter.flush();
            taskLogWriter.close();
        }
        if (dagLogWriter != null) {
            dagLogWriter.flush();
            dagLogWriter.close();
        }
        
        // Print final stats
        printSchedulingStats();
    }
    
    // ============ Logging Methods ============
    
    private void writeTaskLogHeader() {
        taskLogWriter.println(
            "dag_id,task_id,task_type,dag_submit_ms,task_ready_ms," +
            "scheduled_ms,start_ms,finish_ms,tier,datacenter_id,vm_id," +
            "duration_ms,length_mi,gpu_mem_mb,gpu_util,queue_wait_ms," +
            "net_propagation_ms,net_tx_ms,net_total_ms"
        );
    }
    
    private void writeDagLogHeader() {
        dagLogWriter.println(
            "dag_id,submit_ms,finish_ms,makespan_ms,total_tasks," +
            "edge_tasks,cloud_tasks,total_net_ms,total_wan_bytes"
        );
    }
    
    private void logTaskCompletion(TaskRecord task, String dagId) {
        // TODO: Implement logging with placement info
        // For now, basic placeholder
        taskLogWriter.println(String.join(",",
            dagId,
            task.getTaskId(),
            task.getTaskType(),
            "-1", // dag_submit_ms - populate from dag
            String.format("%.2f", task.getReadyTimeMs()),
            String.format("%.2f", task.getScheduledTimeMs()),
            "-1", // start_ms - populate from CloudSim
            String.format("%.2f", task.getFinishTimeMs()),
            String.valueOf(task.getAssignedTier()),
            String.valueOf(task.getAssignedDatacenterId()),
            String.valueOf(task.getAssignedVmId()),
            String.format("%.2f", task.getDurationMs()),
            "-1", // length_mi
            String.format("%.2f", task.getGpuMemoryMb()),
            String.format("%.2f", task.getGpuUtilization()),
            "-1" // Other fields...
        ));
    }
    
    private void logDagCompletion(DagRecord dag) {
        dagLogWriter.println(String.join(",",
            dag.getDagId(),
            String.format("%.2f", dag.getSubmitAtSimMs()),
            String.format("%.2f", dag.getCompleteTimeMs()),
            String.format("%.2f", dag.getMakespanMs()),
            String.valueOf(dag.getTotalTasks()),
            "-1", // edge_tasks - compute from tasks
            "-1", // cloud_tasks - compute from tasks
            "-1", // total_net_ms
            "-1"  // total_wan_bytes
        ));
    }
    
    private void printSchedulingStats() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("SCHEDULING STATISTICS");
        System.out.println("=".repeat(50));
        
        double totalMakespan = 0;
        int completeDags = 0;
        
        for (DagRecord dag : allDags) {
            if (dag.getState() == DagRecord.DagState.COMPLETE) {
                totalMakespan += dag.getMakespanMs();
                completeDags++;
            }
        }
        
        double avgMakespan = (completeDags > 0) ? totalMakespan / completeDags : 0;
        System.out.println("Total DAGs: " + allDags.size());
        System.out.println("Completed DAGs: " + completeDags);
        System.out.println("Average Makespan: " + String.format("%.2f", avgMakespan) + " ms");
        System.out.println("=".repeat(50) + "\n");
    }
    
    // ============ Helper Methods ============
    
    private static long nextCloudletId = 0;
    private synchronized long getNextCloudletId() {
        return nextCloudletId++;
    }
}
