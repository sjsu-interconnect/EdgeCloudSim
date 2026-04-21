package edu.boun.edgecloudsim.dagsim;

import com.google.gson.JsonObject;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.dagsim.scheduling.ClusterState;
import edu.boun.edgecloudsim.dagsim.scheduling.RemoteRLPolicy;
import edu.boun.edgecloudsim.dagsim.scheduling.TaskContext;
import edu.boun.edgecloudsim.edge_orchestrator.DagAwareOrchestrator;
import edu.boun.edgecloudsim.utils.TaskProperty;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.edge_client.Task;

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
    // Maps (dagId, taskId) -> (lengthMi, mobileDeviceId, startTime) for reverse
    // lookup
    private Map<String, Map<String, long[]>> dagTaskRegistry = new HashMap<>();
    // Map from CloudSim cloudlet id -> { dagId, taskId }
    private Map<Long, String[]> cloudletToDagMap = new HashMap<>();
    private Map<String, Double> dagCostSoFar = new HashMap<>();

    // Singleton instance for global callbacks
    private static DagRuntimeManager instance = null;

    private PrintWriter taskLogWriter;
    private PrintWriter dagLogWriter;
    private long totalDagRunTimeMs = 0; // Track total runtime across all DAGs
    private int dagsArrivedCount = 0; // DAG_SUBMIT events actually processed
    private final Set<String> dagsWithScheduledTasks = new HashSet<>(); // DAGs that reached scheduling path

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
        instance = this;
    }

    public static DagRuntimeManager getInstance() {
        return instance;
    }

    @Override
    public void startEntity() {
        // No initialization actions required at start; entity ready to receive events.
    }

    public int getActiveDagsCount() {
        return activeDags.size();
    }

    public Map<String, DagRecord> getActiveDags() {
        return activeDags;
    }

    public double getDagCostSoFar(String dagId) {
        return dagCostSoFar.getOrDefault(dagId, 0.0);
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
        dagCostSoFar.put(dag.getDagId(), 0.0);
        dagsArrivedCount++;

        System.out.println(String.format("[%s] [%.2f] DAG submitted: %s with %d tasks",
                dag.getApplicationName(),
                CloudSim.clock(),
                dag.getDagId(),
                dag.getTotalTasks()));

        // Queue only root tasks (no remaining dependencies) as READY
        for (TaskRecord task : dag.getTasksById().values()) {
            if (task.getRemainingDeps() == 0) {
                task.setReadyTimeMs(submitTime);
                task.setState(TaskRecord.TaskState.READY);
                // Use zero delay for task readiness to avoid nondeterministic jitter
                // (ordering differences cause different queueing outcomes).
                CloudSim.send(getId(), this.getId(), 0.0, TASK_READY, task);
            }
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

        // Convert TaskRecord to TaskProperty and send to SimManager so it follows
        // normal submission path
        SimSettings ss = SimSettings.getInstance();

        // Compute task length in MI using cloud VM MIPS as baseline (so MI is
        // independent of target)
        long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
        if (lengthMi <= 0)
            lengthMi = 1;

        // Projected execution times on edge and cloud (seconds)
        double execSecCloud = lengthMi / (double) ss.getMipsForCloudVM();
        double execSecEdge = lengthMi / (double) ss.getMipsForMobileVM();

        int taskTypeIdx = ss.getTaskTypeIndex(task.getTaskType());
        if (taskTypeIdx == -1) {
            System.err.println("WARNING: Task type " + task.getTaskType()
                    + " not found in applications XML. Using default index 0.");
            taskTypeIdx = 0;
        }

        // Get realistic input/output sizes from applications XML (KB to Bytes)
        double[] appProps = ss.getTaskLookUpTable()[taskTypeIdx];
        long inputBytes = (long) (appProps[5] * 1024.0);
        long outputBytes = (long) (appProps[6] * 1024.0);

        // Fallback for safety
        if (inputBytes <= 0)
            inputBytes = 1024;
        if (outputBytes <= 0)
            outputBytes = 1024;

        int pes = 1;

        // Map DAG task to a real mobile device (round-robin across 0 to numDevices-1)
        // This ensures the task is submitted with a valid device ID for mobility lookup
        int numDevices = SimSettings.getInstance().getMaxNumOfMobileDev();
        int taskHashCode = task.getTaskId().hashCode();
        int mobileDeviceId = Math.abs(taskHashCode) % numDevices;

        // Create TaskProperty with estimated sizes and MI, attach DAG identifiers
        TaskProperty tp = new TaskProperty(readyTime, mobileDeviceId, taskTypeIdx, pes, lengthMi, inputBytes,
                outputBytes, dagId, task.getTaskId());

        // Register this task in our DAG task registry so we can track it when it
        // completes
        dagTaskRegistry.computeIfAbsent(dagId, k -> new HashMap<>())
                .put(task.getTaskId(), new long[] { lengthMi, mobileDeviceId, (long) (readyTime * 1000.0) });

        // Send as CREATE_TASK event to SimManager (CREATE_TASK tag = 0)

        // Log scheduling estimate
        String appName = (dag != null) ? dag.getApplicationName() : "Unknown_App";
        System.out.println(String.format(
                "[%s] [%.2f] Task ready: %s of DAG %s — lengthMI=%d, execEdge=%.3fs, execCloud=%.3fs, in=%dB out=%dB",
                appName, CloudSim.clock(), task.getTaskId(), dagId, lengthMi, execSecEdge, execSecCloud, inputBytes,
                outputBytes));

        CloudSim.send(getId(), SimManager.getInstance().getId(), 0.0, 0, tp);
        dagsWithScheduledTasks.add(dagId);
    }

    /**
     * Register mapping from CloudSim cloudlet id to DAG identifiers so we can
     * find the corresponding TaskRecord when the cloudlet finishes.
     */
    public void registerCloudletMapping(long cloudletId, String dagId, String taskId) {
        if (dagId != null && taskId != null) {
            cloudletToDagMap.put(cloudletId, new String[] { dagId, taskId });
        }
    }

    /**
     * Called by external components when a cloudlet finishes. This looks up the
     * DAG task and forwards to the internal completion handler.
     */
    public void onTaskCloudletFinished(Task cloudlet) {
        long cloudletId = cloudlet.getCloudletId();
        String[] ids = cloudletToDagMap.get(cloudletId);
        if (ids == null)
            return; // not a DAG task

        String dagId = ids[0];
        String taskId = ids[1];

        DagRecord dag = activeDags.get(dagId);
        if (dag == null) {
            // maybe it was moved to completed list; try allDags
            for (DagRecord d : allDags) {
                if (d.getDagId().equals(dagId)) {
                    dag = d;
                    break;
                }
            }
        }
        if (dag == null)
            return;

        TaskRecord task = dag.getTask(taskId);
        if (task == null)
            return;

        // Extract timing and split-up info from Task and SimLogger
        double finishClock = CloudSim.clock();
        task.setFinishTimeMs(finishClock * 1000.0);
        task.setStartTimeMs(cloudlet.getExecStartTime() * 1000.0);
        task.setAssignedVmId(cloudlet.getAssociatedVmId());
        task.setAssignedDatacenterId(cloudlet.getAssociatedDatacenterId());
        int tier = (cloudlet.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
                ? SimSettings.VM_TYPES.CLOUD_VM.ordinal()
                : SimSettings.VM_TYPES.EDGE_VM.ordinal();
        task.setAssignedTier(tier);

        // Fetch deep metrics from SimLogger
        Map<String, Double> metrics = SimLogger.getInstance().getTaskMetrics((int) cloudletId);
        double actualCost = 0.0;
        if (metrics != null) {
            task.setUploadDelayMs(metrics.getOrDefault("lanUploadDelay", 0.0) * 1000.0
                    + metrics.getOrDefault("wanUploadDelay", 0.0) * 1000.0);
            task.setDownloadDelayMs(metrics.getOrDefault("lanDownloadDelay", 0.0) * 1000.0
                    + metrics.getOrDefault("wanDownloadDelay", 0.0) * 1000.0);
            task.setNetworkDelayMs(metrics.getOrDefault("netDelay", 0.0) * 1000.0);
            actualCost = metrics.getOrDefault("bwCost", 0.0) + metrics.getOrDefault("cpuCost", 0.0);

            double queueDelay = (task.getStartTimeMs() - task.getScheduledTimeMs());
            task.setQueueDelayMs(Math.max(0, queueDelay));
        }

        double actualLatency = Math.max(0.0, task.getFinishTimeMs() - task.getReadyTimeMs());
        double newCostSoFar = dagCostSoFar.getOrDefault(dagId, 0.0) + actualCost;
        dagCostSoFar.put(dagId, newCostSoFar);

        SimSettings ss = SimSettings.getInstance();
        double reward = -1.0 * ((ss.getRlAlphaL() * (actualLatency / Math.max(1e-9, ss.getRlLHat())))
                + (ss.getRlAlphaC() * (actualCost / Math.max(1e-9, ss.getRlCHat()))));
        boolean budgetViolated = newCostSoFar > ss.getRlBudgetCost();
        if (budgetViolated) {
            reward += ss.getRlBudgetPenalty();
        }

        RemoteRLPolicy.DecisionTrace trace = RemoteRLPolicy.consumeTrace(dagId, taskId);

        processTaskFinished(task);

        boolean done = !activeDags.containsKey(dagId);
        TaskContext nextTaskCtx = buildTaskContextForNextState(dag, done ? null : findAnyPendingTask(dag), task);
        ClusterState nextClusterState = DagAwareOrchestrator.buildClusterStateSnapshot();
        JsonObject nextState = RemoteRLPolicy.buildStateJson(
                nextTaskCtx,
                nextClusterState,
                dagCostSoFar.getOrDefault(dagId, newCostSoFar),
                ss.getRlBudgetCost(),
                getActiveDagsCount());

        RemoteRLPolicy.postObservation(
                ss.getRlServiceUrl(),
                ss.getRlHttpTimeoutMs(),
                trace,
                nextState,
                reward,
                done,
                actualLatency,
                actualCost,
                dagCostSoFar.getOrDefault(dagId, newCostSoFar),
                ss.getRlBudgetCost(),
                budgetViolated);

        // cleanup mapping
        cloudletToDagMap.remove(cloudletId);
    }

    private void processTaskFinished(TaskRecord task) {
        String dagId = findDagIdForTask(task);
        DagRecord dag = activeDags.get(dagId);

        if (dag == null) {
            System.err.println("ERROR: DAG not found for completed task " + task.getTaskId());
            return;
        }

        // Task Record is already updated with finish time in onTaskCloudletFinished
        task.setState(TaskRecord.TaskState.DONE);
        dag.incrementCompletedTasks();

        System.out.println(String.format("[%s] [%.2f] Task finished: %s of %s (%d/%d)",
                dag.getApplicationName(),
                CloudSim.clock(),
                task.getTaskId(),
                dag.getDagId(),
                dag.getCompletedTasks(),
                dag.getTotalTasks()));

        logTaskCompletion(task, dag);

        for (String childTaskId : task.getChildren()) {
            TaskRecord child = dag.getTask(childTaskId);
            if (child != null) {
                child.decrementRemainingDeps();
                if (child.getRemainingDeps() == 0) {
                    child.setReadyTimeMs(task.getFinishTimeMs());
                    child.setState(TaskRecord.TaskState.READY);
                    CloudSim.send(getId(), this.getId(), 0, TASK_READY, child);
                }
            }
        }

        if (dag.isComplete()) {
            dag.setState(DagRecord.DagState.COMPLETE);
            dag.setCompleteTimeMs(task.getFinishTimeMs());
            long makespanMs = (long) dag.getMakespanMs();
            totalDagRunTimeMs += makespanMs; // Accumulate total runtime
            System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] DAG complete: " + dagId
                    + " Makespan: " + String.format("%.2f", (double) makespanMs) + " ms");
            logDagCompletion(dag);
            edu.boun.edgecloudsim.utils.SimLogger.getInstance().addCompletedDag(); // Track DAG completion for cost
                                                                                   // summary
            activeDags.remove(dagId);
            dagCostSoFar.remove(dagId);
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

    private TaskRecord findAnyPendingTask(DagRecord dag) {
        if (dag == null) {
            return null;
        }
        for (TaskRecord t : dag.getTasksById().values()) {
            if (t.getState() == TaskRecord.TaskState.READY || t.getState() == TaskRecord.TaskState.SCHEDULED) {
                return t;
            }
        }
        for (TaskRecord t : dag.getTasksById().values()) {
            if (t.getState() != TaskRecord.TaskState.DONE) {
                return t;
            }
        }
        return null;
    }

    private TaskContext buildTaskContextForNextState(DagRecord dag, TaskRecord candidate, TaskRecord fallbackTask) {
        TaskRecord base = (candidate != null) ? candidate : fallbackTask;
        TaskContext ctx = new TaskContext();
        ctx.dagId = (dag != null) ? dag.getDagId() : (fallbackTask != null ? findDagIdForTask(fallbackTask) : "NA");
        ctx.taskId = (base != null) ? base.getTaskId() : "NA";
        ctx.taskType = (base != null) ? base.getTaskType() : "NA";

        SimSettings ss = SimSettings.getInstance();
        double lengthMi = 1.0;
        if (base != null) {
            lengthMi = Math.max(1.0, base.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
            ctx.cpuMemoryMb = Math.max(base.getMemoryMb(), 1.0);
        } else {
            ctx.cpuMemoryMb = Math.max(ss.getRamForMobileVM(), 1.0);
        }
        ctx.lengthMI = lengthMi;
        ctx.gpuMemoryMb = (base != null) ? base.getGpuMemoryMb() : 0.0;
        ctx.gpuUtilizationPercent = (base != null) ? base.getGpuUtilization() : 0.0;
        ctx.readyTimeMs = (base != null) ? base.getReadyTimeMs() : CloudSim.clock() * 1000.0;
        ctx.currentTimeMs = CloudSim.clock() * 1000.0;
        return ctx;
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

            // Print total DAG runtime summary
            System.out.println("\n========== DAG EXECUTION SUMMARY ==========");
            System.out.println("Total DAGs configured: " + allDags.size());
            System.out.println("Total DAGs arrived (DAG_SUBMIT processed): " + dagsArrivedCount);
            System.out.println("Total DAGs with >=1 task scheduled: " + dagsWithScheduledTasks.size());
            System.out.println("Total DAG runtime (sum of makespans): " + totalDagRunTimeMs + " ms");
            if (dagsWithScheduledTasks.size() > 0) {
                System.out.println("Average DAG makespan (over scheduled DAGs): "
                        + (totalDagRunTimeMs / (double) dagsWithScheduledTasks.size()) + " ms");
            }
            System.out.println("==========================================");
        } catch (Exception e) {
            System.err.println("Error in DAG shutdown: " + e.getMessage());
        } finally {
            // Always close files
            try {
                if (taskLogWriter != null) {
                    taskLogWriter.flush();
                    taskLogWriter.close();
                }
            } catch (Exception e) {
            }
            try {
                if (dagLogWriter != null) {
                    dagLogWriter.flush();
                    dagLogWriter.close();
                }
            } catch (Exception e) {
            }
        }
    }

    private void writeTaskLogHeader() {
        taskLogWriter.println(
                "dag_id,task_id,task_type,dag_submit_ms,task_ready_ms,scheduled_ms,start_ms,finish_ms,tier,datacenter_id,vm_id,duration_ms,length_mi,proj_edge_sec,proj_cloud_sec,input_bytes,output_bytes,gpu_mem_mb,gpu_util,queue_wait_ms,net_propagation_ms,net_tx_ms,net_total_ms");
        taskLogWriter.flush();
    }

    private void writeDagLogHeader() {
        dagLogWriter.println(
                "dag_id,submit_ms,finish_ms,makespan_ms,total_tasks,edge_tasks,cloud_tasks,total_net_ms,total_wan_bytes");
        dagLogWriter.flush();
    }

    private void logTaskCompletion(TaskRecord task, DagRecord dag) {
        // Compute same derived fields as scheduling time for logging
        SimSettings ss = SimSettings.getInstance();
        long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
        if (lengthMi <= 0)
            lengthMi = 1;
        double projCloud = lengthMi / (double) ss.getMipsForCloudVM();
        double projEdge = lengthMi / (double) ss.getMipsForMobileVM();

        long inputBytes;
        if (task.getGpuMemoryMb() > 0) {
            inputBytes = (long) (task.getGpuMemoryMb() * 1024.0 * 1024.0 * 0.5);
        } else {
            inputBytes = (long) (task.getMemoryMb() * 1024.0 * 1024.0 * 0.2);
        }
        if (inputBytes <= 0)
            inputBytes = 1024;
        long outputBytes = Math.max(1024L, (long) (inputBytes * 0.1));

        taskLogWriter.println(String.join(",",
                dag.getDagId(),
                task.getTaskId(),
                task.getTaskType(),
                String.valueOf(dag.getSubmitAtSimMs()),
                String.format("%.2f", task.getReadyTimeMs()),
                String.format("%.2f", task.getScheduledTimeMs()),
                String.format("%.2f", task.getStartTimeMs()),
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
                String.format("%.2f", task.getQueueDelayMs()),
                "-1", // net_propagation_ms
                "-1", // net_tx_ms
                String.format("%.2f", task.getNetworkDelayMs())));
        taskLogWriter.flush();
    }

    private void logDagCompletion(DagRecord dag) {
        // Compute metrics from DAG tasks
        int numTasks = dag.getTotalTasks();
        long submitMs = dag.getSubmitAtSimMs();
        long completeMs = (long) (dag.getCompleteTimeMs());
        long makespan = completeMs - submitMs;

        int edgeTasks = 0;
        int cloudTasks = 0;
        double totalNetMs = 0;
        for (TaskRecord task : dag.getTasksById().values()) {
            if (task.getAssignedTier() == SimSettings.VM_TYPES.EDGE_VM.ordinal())
                edgeTasks++;
            else if (task.getAssignedTier() == SimSettings.VM_TYPES.CLOUD_VM.ordinal())
                cloudTasks++;

            totalNetMs += task.getNetworkDelayMs();
        }

        // Write CSV row with collected data
        dagLogWriter.println(String.join(",",
                dag.getDagId(),
                String.valueOf(submitMs),
                String.valueOf(completeMs),
                String.valueOf(Math.max(0L, makespan)),
                String.valueOf(numTasks),
                String.valueOf(edgeTasks),
                String.valueOf(cloudTasks),
                String.format("%.2f", totalNetMs),
                "-1" // total_wan_bytes (still not computed)
        ));
        dagLogWriter.flush(); // Flush after each DAG
    }

}
