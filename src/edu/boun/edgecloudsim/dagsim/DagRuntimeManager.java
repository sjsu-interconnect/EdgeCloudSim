package edu.boun.edgecloudsim.dagsim;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * Concrete DagRuntimeManager that integrates DAG tasks into EdgeCloudSim
 * by converting ready tasks to `TaskProperty` and sending them to `SimManager`.
 */
public class DagRuntimeManager extends SimEntity {

    public static final int DAG_SUBMIT = 7001;
    public static final int TASK_READY = 7002;
    public static final int TASK_FINISHED = 7003;

    private List<DagRecord> allDags;
    private Map<String, DagRecord> activeDags;
    
    // Registry to track which DAG tasks we've sent to SimManager
    // Maps (dagId, taskId) -> (lengthMi, mobileDeviceId, startTime) for reverse lookup
    private Map<String, Map<String, long[]>> dagTaskRegistry = new HashMap<>();

    private PrintWriter taskLogWriter;
    private PrintWriter dagLogWriter;

    public DagRuntimeManager(String name, List<DagRecord> dags) {
        super(name);
        this.allDags = dags;
        this.activeDags = new HashMap<>();

        try {
            this.taskLogWriter = new PrintWriter(new FileWriter("task_log.csv"));
            this.dagLogWriter = new PrintWriter(new FileWriter("dag_summary.csv"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to open DAG log files", e);
        }
        writeTaskLogHeader();
        writeDagLogHeader();
    }

    @Override
    public void startEntity() {
        // No initialization actions required at start; entity ready to receive events.
    }

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

    private void processDagSubmit(DagRecord dag) {
        double submitTime = CloudSim.clock() * 1000.0;
        dag.setState(DagRecord.DagState.SUBMITTED);
        activeDags.put(dag.getDagId(), dag);

        System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] DAG submitted: " + dag.getDagId() + " with " + dag.getTotalTasks() + " tasks");

        // Queue all tasks that have no dependencies as ready
        // (In a real DAG scheduler, we'd respect dependencies, but for MVP we'll send all tasks immediately)
        for (TaskRecord task : dag.getTasksById().values()) {
            task.setReadyTimeMs(submitTime);
            task.setState(TaskRecord.TaskState.READY);
            // Send TASK_READY event with a slight delay to avoid event ordering issues
            CloudSim.send(getId(), this.getId(), Math.random() * 0.001, TASK_READY, task);
        }
    }

    private void processTaskReady(TaskRecord task) {
        String dagId = findDagIdForTask(task);
        DagRecord dag = activeDags.get(dagId);

        if (dag == null) {
            System.err.println("ERROR: DAG not found for task " + task.getTaskId());
            return;
        }

        double readyTime = CloudSim.clock();
        task.setState(TaskRecord.TaskState.SCHEDULED);
        task.setScheduledTimeMs(readyTime * 1000.0);

        // Convert TaskRecord to TaskProperty and send to SimManager so it follows normal submission path
        SimSettings ss = SimSettings.getInstance();

        // Compute task length in MI using cloud VM MIPS as baseline (so MI is independent of target)
        long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
        if (lengthMi <= 0) lengthMi = 1;

        // Projected execution times on edge and cloud (seconds)
        double execSecCloud = lengthMi / (double) ss.getMipsForCloudVM();
        double execSecEdge = lengthMi / (double) ss.getMipsForMobileVM();

        // Estimate input/output sizes from DAG metadata heuristically
        long inputBytes;
        if (task.getGpuMemoryMb() > 0) {
            inputBytes = (long) (task.getGpuMemoryMb() * 1024.0 * 1024.0 * 0.5); // 50% of GPU memory as proxy
        } else {
            inputBytes = (long) (task.getMemoryMb() * 1024.0 * 1024.0 * 0.2); // 20% of memory as proxy
        }
        if (inputBytes <= 0) inputBytes = 1024; // at least 1KB
        long outputBytes = Math.max(1024L, (long) (inputBytes * 0.1));

        int pes = 1;
        int taskTypeIdx = 0; // default
        // Map DAG task to a real mobile device (round-robin across 0 to numDevices-1)
        // This ensures the task is submitted with a valid device ID for mobility lookup
        int numDevices = SimSettings.getInstance().getMaxNumOfMobileDev();
        int taskHashCode = task.getTaskId().hashCode();
        int mobileDeviceId = Math.abs(taskHashCode) % numDevices;

        // Create TaskProperty with estimated sizes and MI
        TaskProperty tp = new TaskProperty(readyTime, mobileDeviceId, taskTypeIdx, pes, lengthMi, inputBytes, outputBytes);

        // Register this task in our DAG task registry so we can track it when it completes
        dagTaskRegistry.computeIfAbsent(dagId, k -> new HashMap<>())
            .put(task.getTaskId(), new long[]{lengthMi, mobileDeviceId, (long)(readyTime * 1000.0)});

        // Log scheduling estimate
        System.out.println(String.format("[%.2f] Task ready: %s of DAG %s — lengthMI=%d, execEdge=%.3fs, execCloud=%.3fs, in=%dB out=%dB",
            CloudSim.clock(), task.getTaskId(), dagId, lengthMi, execSecEdge, execSecCloud, inputBytes, outputBytes));

        // Send as CREATE_TASK event to SimManager (CREATE_TASK tag = 0)
        CloudSim.send(getId(), SimManager.getInstance().getId(), 0.0, 0, tp);
    }

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

        System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] Task finished: " + task.getTaskId() + " (" + dag.getCompletedTasks() + "/" + dag.getTotalTasks() + ")");

        logTaskCompletion(task, dagId);

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

        if (dag.isComplete()) {
            dag.setState(DagRecord.DagState.COMPLETE);
            dag.setCompleteTimeMs(finishTime);
            System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] DAG complete: " + dagId + " Makespan: " + String.format("%.2f", dag.getMakespanMs()) + " ms");
            logDagCompletion(dag);
            activeDags.remove(dagId);
        }
    }

    private String findDagIdForTask(TaskRecord task) {
        for (DagRecord dag : allDags) {
            if (dag.getTask(task.getTaskId()) != null) {
                return dag.getDagId();
            }
        }
        return null;
    }

    @Override
    public void shutdownEntity() {
        // Log all DAGs that were submitted at shutdown
        try {
            for (DagRecord dag : allDags) {
                try {
                    if (dag.getState() != DagRecord.DagState.CREATED) {
                        dag.setCompleteTimeMs(CloudSim.clock() * 1000.0);
                        logDagCompletion(dag);
                    }
                } catch (Exception e) {
                    System.err.println("Error logging DAG " + dag.getDagId() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error in DAG shutdown: " + e.getMessage());
        } finally {
            // Always close files
            try { if (taskLogWriter != null) { taskLogWriter.flush(); taskLogWriter.close(); } } catch (Exception e) {}
            try { if (dagLogWriter != null) { dagLogWriter.flush(); dagLogWriter.close(); } } catch (Exception e) {}
        }
    }

    private void writeTaskLogHeader() {
        taskLogWriter.println("dag_id,task_id,task_type,dag_submit_ms,task_ready_ms,scheduled_ms,start_ms,finish_ms,tier,datacenter_id,vm_id,duration_ms,length_mi,proj_edge_sec,proj_cloud_sec,input_bytes,output_bytes,gpu_mem_mb,gpu_util,queue_wait_ms,net_propagation_ms,net_tx_ms,net_total_ms");
        taskLogWriter.flush();
    }

    private void writeDagLogHeader() {
        dagLogWriter.println("dag_id,submit_ms,finish_ms,makespan_ms,total_tasks,edge_tasks,cloud_tasks,total_net_ms,total_wan_bytes");
        dagLogWriter.flush();
    }

    private void logTaskCompletion(TaskRecord task, String dagId) {
        // Compute same derived fields as scheduling time for logging
        SimSettings ss = SimSettings.getInstance();
        long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
        if (lengthMi <= 0) lengthMi = 1;
        double projCloud = lengthMi / (double) ss.getMipsForCloudVM();
        double projEdge = lengthMi / (double) ss.getMipsForMobileVM();

        long inputBytes;
        if (task.getGpuMemoryMb() > 0) {
            inputBytes = (long) (task.getGpuMemoryMb() * 1024.0 * 1024.0 * 0.5);
        } else {
            inputBytes = (long) (task.getMemoryMb() * 1024.0 * 1024.0 * 0.2);
        }
        if (inputBytes <= 0) inputBytes = 1024;
        long outputBytes = Math.max(1024L, (long) (inputBytes * 0.1));

        taskLogWriter.println(String.join(",",
            dagId,
            task.getTaskId(),
            task.getTaskType(),
            "-1",
            String.format("%.2f", task.getReadyTimeMs()),
            String.format("%.2f", task.getScheduledTimeMs()),
            "-1",
            String.format("%.2f", task.getFinishTimeMs()),
            String.valueOf(task.getAssignedTier()),
            String.valueOf(task.getAssignedDatacenterId()),
            String.valueOf(task.getAssignedVmId()),
            String.format("%.2f", task.getDurationMs()),
            String.valueOf(lengthMi),
            String.format("%.3f", projEdge),
            String.format("%.3f", projCloud),
            String.valueOf(inputBytes),
            String.valueOf(outputBytes),
            String.format("%.2f", task.getGpuMemoryMb()),
            String.format("%.2f", task.getGpuUtilization()),
            "-1",
            "-1",
            "-1"
        ));
    }

    private void logDagCompletion(DagRecord dag) {
        // Compute simple metrics from DAG structure
        int numTasks = dag.getTotalTasks();
        long submitMs = dag.getSubmitAtSimMs();
        long completeMs = (long) (dag.getCompleteTimeMs());
        long makespan = completeMs - submitMs;
        
        // Write CSV row with collected data
        dagLogWriter.println(String.join(",",
            dag.getDagId(),
            String.valueOf(submitMs),
            String.valueOf(completeMs),
            String.valueOf(Math.max(0L, makespan)),
            String.valueOf(numTasks),
            "-1",  // edge_tasks (not computed)
            "-1",  // cloud_tasks (not computed)
            "-1",  // total_net_ms (not computed)
            "-1"   // total_wan_bytes (not computed)
        ));
        dagLogWriter.flush();  // Flush after each DAG
    }

}
