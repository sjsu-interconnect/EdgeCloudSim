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
    private Map<String, EstimatedReward> estimatedRewardsByTask = new HashMap<>();

    // Singleton instance for global callbacks
    private static DagRuntimeManager instance = null;

    private PrintWriter taskLogWriter;
    private PrintWriter dagLogWriter;
    private PrintWriter rewardLogWriter;
    private long totalDagRunTimeMs = 0; // Track total runtime across all DAGs
    private int dagsArrivedCount = 0; // DAG_SUBMIT events actually processed
    private int successfullyCompletedDagsCount = 0; // DAGs where all tasks completed without any failure
    private Set<String> failedDagIds = new HashSet<>(); // DAGs that had at least one task failure
    private final Set<String> dagsWithScheduledTasks = new HashSet<>(); // DAGs that reached scheduling path

    private long rewardSampleCount = 0;
    private double minLatencyTerm = Double.POSITIVE_INFINITY;
    private double maxLatencyTerm = Double.NEGATIVE_INFINITY;
    private double sumLatencyTerm = 0.0;
    private double minCostTerm = Double.POSITIVE_INFINITY;
    private double maxCostTerm = Double.NEGATIVE_INFINITY;
    private double sumCostTerm = 0.0;
    private double minReward = Double.POSITIVE_INFINITY;
    private double maxReward = Double.NEGATIVE_INFINITY;
    private double sumReward = 0.0;

    private boolean rlDecisionInFlight = false;
    private final java.util.Queue<TaskRecord> pendingReadyTasks = new java.util.LinkedList<>();

    private static class EstimatedReward {
        double latencyMs;
        double cost;
        double latencyTerm;
        double costTerm;
        double reward;
        double uploadDelayMs;
        double executionMs;
        double downloadDelayMs;
        int vmActiveCloudletsAtEstimate;
    }

    public DagRuntimeManager(String name, List<DagRecord> dags) {
        super(name);
        this.allDags = dags;
        this.activeDags = new HashMap<>();

        try {
            this.taskLogWriter = new PrintWriter(new FileWriter("task_log.csv"));
            this.dagLogWriter = new PrintWriter(new FileWriter("dag_summary.csv"));
            this.rewardLogWriter = new PrintWriter(new FileWriter("reward_log.csv"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to open DAG log files", e);
        }
        writeTaskLogHeader();
        writeDagLogHeader();
        writeRewardLogHeader();
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
        if (task == null) {
            if (!rlDecisionInFlight && !pendingReadyTasks.isEmpty()) {
                TaskRecord nextTask = pendingReadyTasks.poll();
                processTaskReady(nextTask);
            }
            return;
        }
        String dagId = findDagIdForTask(task);
        DagRecord dag = activeDags.get(dagId);

        if (dag == null) {
            System.err.println("ERROR: DAG not found for task " + task.getTaskId());
            return;
        }

        // If RL is busy → queue task
        if (rlDecisionInFlight) {
            boolean wasEmpty = pendingReadyTasks.isEmpty();
            pendingReadyTasks.add(task);

            // If queue was empty, ensure a future event will retry
            CloudSim.send(getId(), this.getId(), 0.01, TASK_READY, null);
            return;
        }

        rlDecisionInFlight = true;

        double readyTime = CloudSim.clock();
        task.setState(TaskRecord.TaskState.SCHEDULED);
        task.setScheduledTimeMs(readyTime * 1000.0);

        SimSettings ss = SimSettings.getInstance();

        long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
        if (lengthMi <= 0) lengthMi = 1;

        int taskTypeIdx = ss.getTaskTypeIndex(task.getTaskType());
        if (taskTypeIdx == -1) taskTypeIdx = 0;

        double[] appProps = ss.getTaskLookUpTable()[taskTypeIdx];
        long inputBytes = (long) (appProps[5] * 1024.0);
        long outputBytes = (long) (appProps[6] * 1024.0);

        if (inputBytes <= 0) inputBytes = 1024;
        if (outputBytes <= 0) outputBytes = 1024;

        int numDevices = ss.getMaxNumOfMobileDev();
        int mobileDeviceId = Math.abs(task.getTaskId().hashCode()) % numDevices;

        TaskProperty tp = new TaskProperty(
            readyTime, mobileDeviceId, taskTypeIdx, 1,
            lengthMi, inputBytes, outputBytes,
            dagId, task.getTaskId()
        );

        // Send to SimManager AFTER RL decision is triggered via /act
        CloudSim.send(getId(), SimManager.getInstance().getId(), 0.0, 0, tp);

        dagsWithScheduledTasks.add(dagId);
    }

    // private void processTaskReady(TaskRecord task) {
    //     String dagId = findDagIdForTask(task);
    //     DagRecord dag = activeDags.get(dagId);

    //     if (dag == null) {
    //         System.err.println("ERROR: DAG not found for task " + task.getTaskId());
    //         return;
    //     }

    //     double readyTime = CloudSim.clock();
    //     task.setState(TaskRecord.TaskState.SCHEDULED);
    //     task.setScheduledTimeMs(readyTime * 1000.0);

    //     // Convert TaskRecord to TaskProperty and send to SimManager so it follows
    //     // normal submission path
    //     SimSettings ss = SimSettings.getInstance();

    //     // Compute task length in MI using cloud VM MIPS as baseline (so MI is
    //     // independent of target)
    //     long lengthMi = (long) (task.getDurationMs() * ss.getMipsForCloudVM() / 1000.0);
    //     if (lengthMi <= 0)
    //         lengthMi = 1;

    //     // Projected execution times on edge and cloud (seconds)
    //     double execSecCloud = lengthMi / (double) ss.getMipsForCloudVM();
    //     double execSecEdge = lengthMi / (double) ss.getMipsForMobileVM();

    //     int taskTypeIdx = ss.getTaskTypeIndex(task.getTaskType());
    //     if (taskTypeIdx == -1) {
    //         System.err.println("WARNING: Task type " + task.getTaskType()
    //                 + " not found in applications XML. Using default index 0.");
    //         taskTypeIdx = 0;
    //     }

    //     // Get realistic input/output sizes from applications XML (KB to Bytes)
    //     double[] appProps = ss.getTaskLookUpTable()[taskTypeIdx];
    //     long inputBytes = (long) (appProps[5] * 1024.0);
    //     long outputBytes = (long) (appProps[6] * 1024.0);

    //     // Fallback for safety
    //     if (inputBytes <= 0)
    //         inputBytes = 1024;
    //     if (outputBytes <= 0)
    //         outputBytes = 1024;

    //     int pes = 1;

    //     // Map DAG task to a real mobile device (round-robin across 0 to numDevices-1)
    //     // This ensures the task is submitted with a valid device ID for mobility lookup
    //     int numDevices = SimSettings.getInstance().getMaxNumOfMobileDev();
    //     int taskHashCode = task.getTaskId().hashCode();
    //     int mobileDeviceId = Math.abs(taskHashCode) % numDevices;

    //     // Create TaskProperty with estimated sizes and MI, attach DAG identifiers
    //     TaskProperty tp = new TaskProperty(readyTime, mobileDeviceId, taskTypeIdx, pes, lengthMi, inputBytes,
    //             outputBytes, dagId, task.getTaskId());

    //     // Register this task in our DAG task registry so we can track it when it
    //     // completes
    //     dagTaskRegistry.computeIfAbsent(dagId, k -> new HashMap<>())
    //             .put(task.getTaskId(), new long[] { lengthMi, mobileDeviceId, (long) (readyTime * 1000.0) });

    //     // Send as CREATE_TASK event to SimManager (CREATE_TASK tag = 0)

    //     // Log scheduling estimate
    //     String appName = (dag != null) ? dag.getApplicationName() : "Unknown_App";
    //     System.out.println(String.format(
    //             "[%s] [%.2f] Task ready: %s of DAG %s — lengthMI=%d, execEdge=%.3fs, execCloud=%.3fs, in=%dB out=%dB",
    //             appName, CloudSim.clock(), task.getTaskId(), dagId, lengthMi, execSecEdge, execSecCloud, inputBytes,
    //             outputBytes));

    //     CloudSim.send(getId(), SimManager.getInstance().getId(), 0.0, 0, tp);
    //     dagsWithScheduledTasks.add(dagId);
    // }

    /**
     * Register mapping from CloudSim cloudlet id to DAG identifiers so we can
     * find the corresponding TaskRecord when the cloudlet finishes.
     */
    public void registerCloudletMapping(long cloudletId, String dagId, String taskId) {
        if (dagId != null && taskId != null) {
            cloudletToDagMap.put(cloudletId, new String[] { dagId, taskId });
        }
    }

    public void recordEstimatedReward(Task cloudlet, double vmMips, int vmActiveCloudletsAtEstimate,
            double uploadDelaySec, double downloadDelaySec) {
        if (cloudlet == null || cloudlet.getDagId() == null || cloudlet.getDagTaskId() == null) {
            return;
        }

        DagRecord dag = activeDags.get(cloudlet.getDagId());
        if (dag == null) {
            return;
        }
        TaskRecord task = dag.getTask(cloudlet.getDagTaskId());
        if (task == null) {
            return;
        }

        double elapsedBeforeVmMs = Math.max(0.0, CloudSim.clock() * 1000.0 - task.getScheduledTimeMs());
        double safeVmMips = Math.max(1e-9, vmMips);
        double baseExecutionSec = cloudlet.getCloudletLength() / safeVmMips;
        double timeSharedFactor = Math.max(1, vmActiveCloudletsAtEstimate + 1);
        double estimatedExecutionSec = baseExecutionSec * timeSharedFactor;
        double estimatedDownloadSec = Math.max(0.0, downloadDelaySec);

        EstimatedReward estimate = new EstimatedReward();
        estimate.uploadDelayMs = Math.max(0.0, uploadDelaySec) * 1000.0;
        estimate.executionMs = estimatedExecutionSec * 1000.0;
        estimate.downloadDelayMs = estimatedDownloadSec * 1000.0;
        estimate.latencyMs = elapsedBeforeVmMs + estimate.executionMs + estimate.downloadDelayMs;
        double[] estimatedCosts = estimateCloudletCost(cloudlet, estimatedExecutionSec);
        estimate.cost = estimatedCosts[0] + estimatedCosts[1];
        estimate.vmActiveCloudletsAtEstimate = vmActiveCloudletsAtEstimate;

        SimSettings ss = SimSettings.getInstance();
        estimate.latencyTerm = ss.getRlAlphaL() * normalizeRewardComponent(
                estimate.latencyMs,
                ss.getRlLatencyMinMs(),
                ss.getRlLatencyMaxMs(),
                ss.getRlLHat(),
                ss.getRlClipNormalizedReward());
        estimate.costTerm = ss.getRlAlphaC() * normalizeRewardComponent(
                estimate.cost,
                ss.getRlCostMin(),
                ss.getRlCostMax(),
                ss.getRlCHat(),
                ss.getRlClipNormalizedReward());
        estimate.reward = -1.0 * (estimate.latencyTerm + estimate.costTerm);
        if (dagCostSoFar.getOrDefault(cloudlet.getDagId(), 0.0) + estimate.cost > ss.getRlBudgetCost()) {
            estimate.reward += ss.getRlBudgetPenalty();
        }

        estimatedRewardsByTask.put(taskKey(cloudlet.getDagId(), cloudlet.getDagTaskId()), estimate);
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
        double bwCost = 0.0;
        double cpuCost = 0.0;
        String costSource = "missing_metrics";
        if (metrics != null) {
            task.setUploadDelayMs(metrics.getOrDefault("lanUploadDelay", 0.0) * 1000.0
                    + metrics.getOrDefault("wanUploadDelay", 0.0) * 1000.0);
            task.setDownloadDelayMs(metrics.getOrDefault("lanDownloadDelay", 0.0) * 1000.0
                    + metrics.getOrDefault("wanDownloadDelay", 0.0) * 1000.0);
            task.setNetworkDelayMs(metrics.getOrDefault("netDelay", 0.0) * 1000.0);
            bwCost = metrics.getOrDefault("bwCost", 0.0);
            cpuCost = metrics.getOrDefault("cpuCost", 0.0);
            actualCost = bwCost + cpuCost;
            costSource = "sim_logger";

            // double queueDelay = (task.getStartTimeMs() - task.getScheduledTimeMs());
            // task.setQueueDelayMs(Math.max(0, queueDelay));
        }
        double queueDelay = (task.getStartTimeMs() - task.getScheduledTimeMs());
        task.setQueueDelayMs(Math.max(0, queueDelay));
        if (actualCost <= 0.0) {
            double[] fallbackCosts = estimateCloudletCost(cloudlet);
            bwCost = fallbackCosts[0];
            cpuCost = fallbackCosts[1];
            actualCost = bwCost + cpuCost;
            costSource = "fallback_cloudlet";
        }

        // double actualLatency = Math.max(0.0, task.getFinishTimeMs() - task.getReadyTimeMs());
        
        //Timer for latency starts task scheduled using RL agent action
        double readyWaitMs = Math.max(0.0, task.getScheduledTimeMs() - task.getReadyTimeMs());
        double actualLatency = Math.max(0.0, task.getFinishTimeMs() - task.getScheduledTimeMs());
        double newCostSoFar = dagCostSoFar.getOrDefault(dagId, 0.0) + actualCost;
        dagCostSoFar.put(dagId, newCostSoFar);

        SimSettings ss = SimSettings.getInstance();
        double latencyTerm = ss.getRlAlphaL() * normalizeRewardComponent(
                actualLatency,
                ss.getRlLatencyMinMs(),
                ss.getRlLatencyMaxMs(),
                ss.getRlLHat(),
                ss.getRlClipNormalizedReward());
        double costTerm = ss.getRlAlphaC() * normalizeRewardComponent(
                actualCost,
                ss.getRlCostMin(),
                ss.getRlCostMax(),
                ss.getRlCHat(),
                ss.getRlClipNormalizedReward());
        double budgetPenaltyApplied = 0.0;
        double reward = -1.0 * (latencyTerm + costTerm);
        boolean budgetViolated = newCostSoFar > ss.getRlBudgetCost();
        if (budgetViolated) {
            budgetPenaltyApplied = ss.getRlBudgetPenalty();
            reward += budgetPenaltyApplied;
        }
        RemoteRLPolicy.DecisionTrace trace = RemoteRLPolicy.consumeTrace(dagId, taskId);
        EstimatedReward estimate = estimatedRewardsByTask.remove(taskKey(dagId, taskId));
        updateRewardSummary(latencyTerm, costTerm, reward);
        logRewardBreakdown(task, dag, actualLatency, readyWaitMs, actualCost, bwCost, cpuCost, costSource, trace, estimate,
                newCostSoFar, latencyTerm, costTerm, budgetPenaltyApplied, reward, budgetViolated);

        processTaskFinished(task);

        boolean done = activeDags.isEmpty() && (dagsArrivedCount >= allDags.size());

        // boolean done = !activeDags.containsKey(dagId);
        DagRecord nextDag = done ? null : findAnyActiveDagWithPendingTask();
        TaskRecord nextPendingTask = done ? null : findAnyPendingTaskAcrossActiveDags();
        
        // TaskContext nextTaskCtx = buildTaskContextForNextState(dag, done ? null : findAnyPendingTask(dag), task);
        TaskContext nextTaskCtx = buildTaskContextForNextState(nextDag, nextPendingTask, task);
        ClusterState nextClusterState = DagAwareOrchestrator.buildClusterStateSnapshot();
        double nextCostSoFar = 0.0;
        if (!done && nextDag != null) {
            nextCostSoFar = dagCostSoFar.getOrDefault(nextDag.getDagId(), 0.0);
        }

        JsonObject nextState = RemoteRLPolicy.buildStateJson(
                nextTaskCtx,
                nextClusterState,
                nextCostSoFar,
                // dagCostSoFar.getOrDefault(dagId, newCostSoFar),
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
                nextCostSoFar,
                // dagCostSoFar.getOrDefault(dagId, newCostSoFar),
                ss.getRlBudgetCost(),
                budgetViolated);
        
        rlDecisionInFlight = false;

        if (!pendingReadyTasks.isEmpty()) {
            TaskRecord nextTask = pendingReadyTasks.poll();
            processTaskReady(nextTask);
        }
        // cleanup mapping
        cloudletToDagMap.remove(cloudletId);
    }

    /**
     * Called by SimManager when a task fails (e.g. WLAN/mobility failure).
     * Without this, rlDecisionInFlight stays true forever and the simulation hangs.
     */
    public void onTaskFailed(Task cloudlet) {
        long cloudletId = cloudlet.getCloudletId();
        String[] ids = cloudletToDagMap.remove(cloudletId);
        if (ids == null) return; // not a DAG task
 
        String dagId = ids[0];
        String taskId = ids[1];
 
        DagRecord dag = activeDags.get(dagId);
        if (dag == null) {
            for (DagRecord d : allDags) {
                if (d.getDagId().equals(dagId)) { dag = d; break; }
            }
        }
        if (dag != null) {
            TaskRecord task = dag.getTask(taskId);
            if (task != null) {
                task.setState(TaskRecord.TaskState.DONE);
                dag.incrementCompletedTasks();
                System.out.println(String.format(
                    "[%s] [%.2f] Task FAILED (network): %s of %s (%d/%d)",
                    dag.getApplicationName(), CloudSim.clock(),
                    taskId, dagId,
                    dag.getCompletedTasks(), dag.getTotalTasks()));

                // Cascade failure to all downstream tasks that can no longer run
                cascadeFailure(dag, task);
                failedDagIds.add(dagId); // Mark this DAG as having at least one failure

                // If this failure (+ cascade) completes the DAG
                if (dag.isComplete()) {
                    dag.setState(DagRecord.DagState.COMPLETE);
                    dag.setCompleteTimeMs(CloudSim.clock() * 1000.0);
                    activeDags.remove(dagId);
                    dagCostSoFar.remove(dagId);
                }
            }
        }
 
        // Send penalty observation to Python if an RL trace was stored for this task
        RemoteRLPolicy.DecisionTrace trace = RemoteRLPolicy.consumeTrace(dagId, taskId);
        if (trace != null) {
            SimSettings ss = SimSettings.getInstance();
            boolean done = activeDags.isEmpty() && (dagsArrivedCount >= allDags.size());
            DagRecord nextDag = done ? null : findAnyActiveDagWithPendingTask();
            TaskRecord nextPending = done ? null : findAnyPendingTaskAcrossActiveDags();
            TaskContext nextCtx = buildTaskContextForNextState(nextDag, nextPending, null);
            ClusterState nextCluster = DagAwareOrchestrator.buildClusterStateSnapshot();
            double nextCost = (!done && nextDag != null)
                ? dagCostSoFar.getOrDefault(nextDag.getDagId(), 0.0) : 0.0;
            JsonObject nextState = RemoteRLPolicy.buildStateJson(
                nextCtx, nextCluster, nextCost, ss.getRlBudgetCost(), getActiveDagsCount());
 
            // Apply a large latency penalty for the failed task
            double penalty = ss.getRlBudgetPenalty();
            RemoteRLPolicy.postObservation(
                ss.getRlServiceUrl(), ss.getRlHttpTimeoutMs(),
                trace, nextState,
                penalty,   // reward: penalty for failure
                done,
                0.0, 0.0, // latency/cost unknown for failed task
                nextCost, ss.getRlBudgetCost(),
                false);
        }
 
        // Unblock the scheduling pipeline
        rlDecisionInFlight = false;
        if (!pendingReadyTasks.isEmpty()) {
            TaskRecord nextTask = pendingReadyTasks.poll();
            processTaskReady(nextTask);
        }
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
            if (!failedDagIds.contains(dagId)) {
                successfullyCompletedDagsCount++; // Only count if no tasks failed in this DAG
            }
            System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] DAG complete: " + dagId
                    + " Makespan: " + String.format("%.2f", (double) makespanMs) + " ms");
            logDagCompletion(dag);
            edu.boun.edgecloudsim.utils.SimLogger.getInstance().addCompletedDag(); // Track DAG completion for cost
                                                                                   // summary
            activeDags.remove(dagId);
            dagCostSoFar.remove(dagId);
        }
    }

    /**
    * helper function that marks all tasks in the dag with failed task as done to terminate
    */
    private void cascadeFailure(DagRecord dag, TaskRecord failedTask) {
        for (String childId : failedTask.getChildren()) {
            TaskRecord child = dag.getTask(childId);
            if (child != null && child.getState() != TaskRecord.TaskState.DONE) {
                child.setState(TaskRecord.TaskState.DONE);
                dag.incrementCompletedTasks();
                System.out.println(String.format(
                    "[%s] [%.2f] Task SKIPPED (upstream failed): %s of %s (%d/%d)",
                    dag.getApplicationName(), CloudSim.clock(),
                    childId, dag.getDagId(),
                    dag.getCompletedTasks(), dag.getTotalTasks()));
                cascadeFailure(dag, child); // recurse to grandchildren
            }
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

    private String taskKey(String dagId, String taskId) {
        return dagId + "::" + taskId;
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

    private DagRecord findAnyActiveDagWithPendingTask() {
        for (DagRecord dag : activeDags.values()) {
            TaskRecord pending = findAnyPendingTask(dag);
            if (pending != null) {
                return dag;
            }
        }
        return activeDags.values().stream().findFirst().orElse(null);
    }

    private TaskRecord findAnyPendingTaskAcrossActiveDags() {
        for (DagRecord dag : activeDags.values()) {
            TaskRecord pending = findAnyPendingTask(dag);
            if (pending != null) {
                return pending;
            }
        }
        return null;
    }

    private TaskContext buildTaskContextForNextState(DagRecord dag, TaskRecord candidate, TaskRecord fallbackTask) {
        TaskRecord base = (candidate != null) ? candidate : fallbackTask;
        TaskContext ctx = new TaskContext();
        // ctx.dagId = (dag != null) ? dag.getDagId() : (fallbackTask != null ? findDagIdForTask(fallbackTask) : "NA");
        String resolvedDagId;
        if (dag != null) {
            resolvedDagId = dag.getDagId();
        } else if (base != null) {
            resolvedDagId = findDagIdForTask(base);
        } else if (fallbackTask != null) {
            resolvedDagId = findDagIdForTask(fallbackTask);
        } else {
            resolvedDagId = "NA";
        }
        ctx.dagId = resolvedDagId;
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
            System.out.println("Total DAGs successfully completed: " + successfullyCompletedDagsCount);
            System.out.println("Total DAGs failed/incomplete: " + (dagsArrivedCount - successfullyCompletedDagsCount));
            System.out.println("Total DAG runtime (sum of makespans): " + totalDagRunTimeMs + " ms");
            if (successfullyCompletedDagsCount > 0) {
                System.out.println("Average DAG makespan (over completed DAGs): "
                        + (totalDagRunTimeMs / (double) successfullyCompletedDagsCount) + " ms");
            } else if (dagsWithScheduledTasks.size() > 0) {
                System.out.println("Average DAG makespan (over scheduled DAGs): "
                        + (totalDagRunTimeMs / (double) dagsWithScheduledTasks.size()) + " ms");
            }
            printRewardSummary();
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
            try {
                if (rewardLogWriter != null) {
                    rewardLogWriter.flush();
                    rewardLogWriter.close();
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

    private void writeRewardLogHeader() {
        rewardLogWriter.println(
                // "sim_time_ms,dag_id,task_id,task_type,tier,datacenter_id,vm_id,ready_ms,scheduled_ms,start_ms,finish_ms,latency_ms,queue_wait_ms,network_ms,bw_cost,cpu_cost,actual_cost,cost_source,cost_so_far,budget,latency_term,cost_term,budget_penalty,reward,budget_violated");
                "sim_time_ms,dag_id,task_id,task_type,tier,datacenter_id,vm_id,ready_ms,scheduled_ms,start_ms,finish_ms,ready_wait_ms,latency_ms,queue_wait_ms,network_ms,selected_dc_vm_count,selected_dc_queue_len,selected_dc_avg_queue_len,selected_dc_max_queue_len,selected_dc_avg_utilization,estimated_latency_ms,estimated_cost,estimated_reward,estimate_latency_error_ms,estimate_cost_error,estimate_reward_error,estimate_upload_ms,estimate_execution_ms,estimate_download_ms,estimate_vm_active_cloudlets,bw_cost,cpu_cost,actual_cost,cost_source,cost_so_far,budget,latency_term,cost_term,budget_penalty,reward,budget_violated");
        rewardLogWriter.flush();
    }

    private void updateRewardSummary(double latencyTerm, double costTerm, double reward) {
        rewardSampleCount++;
        minLatencyTerm = Math.min(minLatencyTerm, latencyTerm);
        maxLatencyTerm = Math.max(maxLatencyTerm, latencyTerm);
        sumLatencyTerm += latencyTerm;

        minCostTerm = Math.min(minCostTerm, costTerm);
        maxCostTerm = Math.max(maxCostTerm, costTerm);
        sumCostTerm += costTerm;

        minReward = Math.min(minReward, reward);
        maxReward = Math.max(maxReward, reward);
        sumReward += reward;
    }

    private void printRewardSummary() {
        System.out.println("\n========== RL REWARD SUMMARY ==========");
        System.out.println("Reward samples: " + rewardSampleCount);
        if (rewardSampleCount > 0) {
            System.out.println(String.format(
                    "Latency term min/avg/max (normalized): %.6f / %.6f / %.6f",
                    minLatencyTerm,
                    sumLatencyTerm / (double) rewardSampleCount,
                    maxLatencyTerm));
            System.out.println(String.format(
                    "Cost term min/avg/max (normalized): %.12f / %.12f / %.12f",
                    minCostTerm,
                    sumCostTerm / (double) rewardSampleCount,
                    maxCostTerm));
            System.out.println(String.format(
                    "Reward min/avg/max: %.6f / %.6f / %.6f",
                    minReward,
                    sumReward / (double) rewardSampleCount,
                    maxReward));
        }
        System.out.println("=======================================");
    }

    private double normalizeRewardComponent(double value, double minValue, double maxValue, double fallbackScale, boolean clip) {
        double normalized;
        if (maxValue > minValue) {
            normalized = (value - minValue) / Math.max(1e-9, maxValue - minValue);
        } else {
            normalized = value / Math.max(1e-9, fallbackScale);
        }

        if (clip) {
            normalized = Math.max(0.0, Math.min(1.0, normalized));
        }
        return normalized;
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

    private double[] estimateCloudletCost(Task cloudlet) {
        return estimateCloudletCost(cloudlet, cloudlet.getActualCPUTime());
    }

    private double[] estimateCloudletCost(Task cloudlet, double cpuTimeSec) {
        int datacenterId = cloudlet.getAssociatedDatacenterId();
        if (datacenterId == SimSettings.CLOUD_DATACENTER_ID) {
            datacenterId = SimManager.getInstance().getCloudServerManager().getDatacenter().getId();
        } else if (datacenterId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            datacenterId = SimManager.getInstance().getEdgeServerManager().getDatacenterList().get(0).getId();
        }

        Double[] costs = SimSettings.datacenterCosts.get(datacenterId);
        double costPerBw = (costs != null) ? costs[0] : 0.00000000009;
        double costPerSec = (costs != null) ? costs[1] : 0.0002083333;
        double costPerMem = (costs != null) ? costs[2] : 0.0;
        double costPerStorage = (costs != null) ? costs[3] : 0.0;

        double totalBytes = cloudlet.getCloudletFileSize() + cloudlet.getCloudletOutputSize();
        double bwCost = Math.max(0.0, totalBytes * costPerBw);
        double cpuCost = Math.max(0.0, cpuTimeSec * costPerSec);

        double vmRam = (cloudlet.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
                ? SimSettings.getInstance().getRamForCloudVM()
                : SimSettings.getInstance().getRamForMobileVM();
        cpuCost += Math.max(0.0, vmRam * costPerMem);
        cpuCost += Math.max(0.0, cloudlet.getCloudletOutputSize() * costPerStorage / 1024.0);

        return new double[] { bwCost, cpuCost };
    }

    // private void logRewardBreakdown(TaskRecord task, DagRecord dag, double actualLatency, double actualCost,
    //         double bwCost, double cpuCost, String costSource, double costSoFar, double latencyTerm, double costTerm,
    //         double budgetPenaltyApplied, double reward, boolean budgetViolated) {
    private void logRewardBreakdown(TaskRecord task, DagRecord dag, double actualLatency, double readyWaitMs,
            double actualCost, double bwCost, double cpuCost, String costSource, RemoteRLPolicy.DecisionTrace trace,
            EstimatedReward estimate,
            double costSoFar,
            double latencyTerm, double costTerm, double budgetPenaltyApplied, double reward, boolean budgetViolated) {
        if (rewardLogWriter == null || task == null || dag == null) {
            return;
        }

        SimSettings ss = SimSettings.getInstance();
        double estimatedLatency = estimate != null ? estimate.latencyMs : -1.0;
        double estimatedCost = estimate != null ? estimate.cost : -1.0;
        double estimatedReward = estimate != null ? estimate.reward : 0.0;
        double latencyError = estimate != null ? actualLatency - estimate.latencyMs : 0.0;
        double costError = estimate != null ? actualCost - estimate.cost : 0.0;
        double rewardError = estimate != null ? reward - estimate.reward : 0.0;
        rewardLogWriter.println(String.join(",",
                String.format("%.2f", CloudSim.clock() * 1000.0),
                dag.getDagId(),
                task.getTaskId(),
                task.getTaskType(),
                String.valueOf(task.getAssignedTier()),
                String.valueOf(task.getAssignedDatacenterId()),
                String.valueOf(task.getAssignedVmId()),
                String.format("%.2f", task.getReadyTimeMs()),
                String.format("%.2f", task.getScheduledTimeMs()),
                String.format("%.2f", task.getStartTimeMs()),
                String.format("%.2f", task.getFinishTimeMs()),
                String.format("%.2f", readyWaitMs),
                String.format("%.2f", actualLatency),
                String.format("%.2f", task.getQueueDelayMs()),
                String.format("%.2f", task.getNetworkDelayMs()),
                String.valueOf(trace != null ? trace.selectedDcVmCount : -1),
                String.valueOf(trace != null ? trace.selectedDcQueueLen : -1),
                String.format("%.6f", trace != null ? trace.selectedDcAvgQueueLen : -1.0),
                String.format("%.6f", trace != null ? trace.selectedDcMaxQueueLen : -1.0),
                String.format("%.6f", trace != null ? trace.selectedDcAvgUtilization : -1.0),
                String.format("%.2f", estimatedLatency),
                String.format("%.12f", estimatedCost),
                String.format("%.12f", estimatedReward),
                String.format("%.2f", latencyError),
                String.format("%.12f", costError),
                String.format("%.12f", rewardError),
                String.format("%.2f", estimate != null ? estimate.uploadDelayMs : -1.0),
                String.format("%.2f", estimate != null ? estimate.executionMs : -1.0),
                String.format("%.2f", estimate != null ? estimate.downloadDelayMs : -1.0),
                String.valueOf(estimate != null ? estimate.vmActiveCloudletsAtEstimate : -1),
                String.format("%.12f", bwCost),
                String.format("%.12f", cpuCost),
                String.format("%.12f", actualCost),
                costSource,
                String.format("%.12f", costSoFar),
                String.format("%.12f", ss.getRlBudgetCost()),
                String.format("%.12f", latencyTerm),
                String.format("%.12f", costTerm),
                String.format("%.12f", budgetPenaltyApplied),
                String.format("%.12f", reward),
                String.valueOf(budgetViolated)));
        rewardLogWriter.flush();
    }

}
