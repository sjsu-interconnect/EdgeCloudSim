# EdgeCloudSim DAG Scheduling Integration Guide

## Overview

This package provides end-to-end DAG scheduling support for EdgeCloudSim. It enables:
1. Loading synthetic AI/ML workload DAGs from JSON
2. Enforcing task dependencies and scheduling policies
3. Metrics collection (makespan, WAN usage, resource utilization)
4. Optional empirical latency modeling

## Part A: DAG Ingestion & Dependency Engine

### Files Included

- **TaskRecord.java**: Represents a single task with dependencies, duration, memory requirements, and runtime state
- **DagRecord.java**: Represents a complete DAG (inference request) with task graph
- **DagJsonLoader.java**: Loads DAG JSON files from directory, parses, and constructs data models

### Usage

```java
// Load all DAGs from directory
List<DagRecord> dags = DagJsonLoader.loadAllDags("path/to/synthetic_dags");

// Access DAG properties
for (DagRecord dag : dags) {
    System.out.println(dag.getDagId() + ": " + dag.getTotalTasks() + " tasks");
    for (TaskRecord task : dag.getTasksById().values()) {
        System.out.println("  Task: " + task.getTaskId() + 
                          " depends on: " + task.getDependsOn());
    }
}
```

### Key Design Patterns

1. **Task State Machine**: Each task progresses CREATED → READY → SCHEDULED → RUNNING → DONE
2. **Dependency Tracking**: 
   - `remainingDeps`: Count of unfinished parent tasks
   - `children`: List of dependent tasks (bidirectional graph)
3. **Relative Time Calculation**: All submission times converted to simulation time (ms from min submission)

### Integration with EdgeCloudSim

In your EdgeCloudSim scenario initialization:

```java
public class DagSchedulingScenario extends Scenario {
    private List<DagRecord> dags;
    
    @Override
    public void initialize() {
        super.initialize();
        
        // Load DAGs
        dags = DagJsonLoader.loadAllDags(SimSettings.getInstance().getDagDirectory());
        
        // Create DAG runtime manager (see below)
        DagRuntimeManager dagManager = new DagRuntimeManager(dags, policy);
        
        // Schedule initial DAG submissions
        for (DagRecord dag : dags) {
            CloudSim.schedule(SimEntity.CLOUD_TOPOLOGY,
                dag.getSubmitAtSimMs() / 1000.0, // Convert to seconds for CloudSim
                DAG_SUBMIT_EVENT,
                dag);
        }
    }
}
```

---

## Part B: Scheduling Policy API

### Files Included

- **SchedulingPolicy.java**: Interface for pluggable policies
- **TaskContext.java**: Input context for scheduling decision
- **PlacementDecision.java**: Output of scheduling decision
- **ClusterState.java**: Current cluster resource snapshot
- **RoundRobinPolicy.java**: Baseline round-robin policy
- **EdgeFirstFeasiblePolicy.java**: Edge-first with fallback
- **EFTPolicy.java**: Earliest Finish Time optimization

### Policy Interface

```java
public interface SchedulingPolicy {
    PlacementDecision decide(TaskContext task, ClusterState state);
    String getPolicyName();
}
```

### Integration with EdgeCloudSim Orchestrator

Modify EdgeCloudSim's `EdgeOrchestrator` class:

```java
public class DagAwareOrchestrator extends EdgeOrchestrator {
    
    private SchedulingPolicy policy;
    
    public DagAwareOrchestrator(SchedulingPolicy policy) {
        super();
        this.policy = policy;
    }
    
    @Override
    public int[] getNextVmToPlace(Task task, List<Integer> dcIds) {
        // Convert task to TaskContext
        TaskContext taskCtx = buildTaskContext(task);
        
        // Build current cluster state
        ClusterState clusterState = buildClusterState();
        
        // Get policy decision
        PlacementDecision decision = policy.decide(taskCtx, clusterState);
        
        // Convert to EdgeCloudSim format
        int[] placement = new int[] {
            decision.destDatacenterId,
            decision.destVmId
        };
        
        return placement;
    }
    
    private TaskContext buildTaskContext(Task task) {
        TaskContext ctx = new TaskContext();
        ctx.taskId = extractDagTaskId(task);
        ctx.dagId = extractDagId(task);
        ctx.lengthMI = task.getLength();
        ctx.gpuMemoryMb = extractGpuMemory(task);
        ctx.cpuMemoryMb = extractCpuMemory(task);
        // ... populate other fields
        return ctx;
    }
    
    private ClusterState buildClusterState() {
        ClusterState state = new ClusterState(CloudSim.clock() * 1000.0);
        // Populate with current VM status, queue lengths, etc.
        return state;
    }
}
```

### Baseline Policies

1. **RoundRobinPolicy**: Cycles through all VMs evenly
   - Use case: Load testing, baseline
   
2. **EdgeFirstFeasiblePolicy**: Prefers edge if resources available
   - Use case: Minimize WAN usage
   
3. **EFTPolicy**: Minimizes estimated task finish time
   - Use case: Minimize makespan
   
4. **To implement custom policy**: Extend `SchedulingPolicy` interface

---

## Part C: DAG Runtime Manager (Pseudocode)

Create `DagRuntimeManager.java` in your scenario:

```java
public class DagRuntimeManager extends SimEntity {
    
    private List<DagRecord> allDags;
    private Map<String, DagRecord> activeDags;
    private SchedulingPolicy policy;
    private EdgeOrchestrator orchestrator;
    
    // Event IDs
    private static final int DAG_SUBMIT = 1;
    private static final int TASK_READY = 2;
    private static final int TASK_FINISHED = 3;
    
    public DagRuntimeManager(List<DagRecord> dags, SchedulingPolicy policy) {
        super("DagRuntimeManager");
        this.allDags = dags;
        this.activeDags = new HashMap<>();
        this.policy = policy;
    }
    
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case DAG_SUBMIT:
                handleDagSubmit((DagRecord) ev.getData());
                break;
            case TASK_READY:
                handleTaskReady((TaskRecord) ev.getData());
                break;
            case TASK_FINISHED:
                handleTaskFinished((TaskRecord) ev.getData());
                break;
        }
    }
    
    private void handleDagSubmit(DagRecord dag) {
        dag.setState(DagRecord.DagState.SUBMITTED);
        activeDags.put(dag.getDagId(), dag);
        
        // Emit TASK_READY for all source tasks (no dependencies)
        for (TaskRecord task : dag.getTasksById().values()) {
            if (task.getRemainingDeps() == 0) {
                task.setReadyTimeMs(CloudSim.clock() * 1000.0);
                task.setState(TaskRecord.TaskState.READY);
                send(this, 0, TASK_READY, task);
            }
        }
    }
    
    private void handleTaskReady(TaskRecord task) {
        // Submit to orchestrator for placement
        PlacementDecision decision = orchestrator.scheduleTask(task);
        
        // Create CloudSim task (cloudlet)
        long cloudletId = createCloudlet(task);
        task.setCloudletId(cloudletId);
        task.setState(TaskRecord.TaskState.SCHEDULED);
        
        // Log scheduling event
        logTaskScheduled(task, decision);
    }
    
    private void handleTaskFinished(TaskRecord task) {
        double finishTime = CloudSim.clock() * 1000.0;
        task.setFinishTimeMs(finishTime);
        task.setState(TaskRecord.TaskState.DONE);
        
        // Get parent DAG
        String dagId = extractDagId(task);
        DagRecord dag = activeDags.get(dagId);
        dag.incrementCompletedTasks();
        
        // Release children
        for (String childTaskId : task.getChildren()) {
            TaskRecord child = dag.getTask(childTaskId);
            child.decrementRemainingDeps();
            if (child.getRemainingDeps() == 0) {
                child.setReadyTimeMs(finishTime);
                child.setState(TaskRecord.TaskState.READY);
                send(this, 0, TASK_READY, child);
            }
        }
        
        // Check if DAG complete
        if (dag.isComplete()) {
            dag.setState(DagRecord.DagState.COMPLETE);
            dag.setCompleteTimeMs(finishTime);
            logDagComplete(dag);
            activeDags.remove(dagId);
        }
    }
}
```

---

## Part D: Logging & Metrics

### Per-Task CSV Log

Create `TaskLogEntry.csv`:

```csv
dag_id,task_id,task_type,dag_submit_ms,task_ready_ms,scheduled_ms,start_ms,finish_ms,tier,datacenter_id,vm_id,duration_ms,length_mi,gpu_mem_mb,gpu_util,queue_wait_ms,net_propagation_ms,net_tx_ms,net_total_ms
dag_123,vae_encode_456,vae_encode,1000.0,1050.0,1050.0,1050.0,1475.0,edge,0,1,425.07,425070,2331.49,0.622,0.0,0.0,0.0,0.0
```

### Per-DAG CSV Summary

Create `DagSummary.csv`:

```csv
dag_id,submit_ms,finish_ms,makespan_ms,total_tasks,edge_tasks,cloud_tasks,total_net_ms,total_wan_bytes
dag_123,1000.0,28500.0,27500.0,62,45,17,0.0,0.0
```

### Validation Checks

```java
public class SchedulingValidator {
    
    public static void validateSchedule(List<DagRecord> dags) {
        for (DagRecord dag : dags) {
            if (dag.getState() != DagRecord.DagState.COMPLETE) {
                continue;
            }
            
            // Check dependency order
            for (TaskRecord task : dag.getTasksById().values()) {
                for (String depTaskId : task.getDependsOn()) {
                    TaskRecord parent = dag.getTask(depTaskId);
                    if (parent.getFinishTimeMs() > task.getStartTimeMs()) {
                        System.err.println("ERROR: Dependency violation! " + 
                            depTaskId + " finishes after " + task.getTaskId());
                    }
                }
            }
        }
    }
}
```

---

## Implementation Roadmap

### Phase 1 (MUST-HAVE)
- [x] TaskRecord, DagRecord, DagJsonLoader
- [x] SchedulingPolicy interface + 3 baselines
- [ ] DagRuntimeManager integration
- [ ] Task→Cloudlet conversion

### Phase 2 (MUST-HAVE)
- [ ] Per-task and per-DAG CSV logging
- [ ] Validation checks
- [ ] Metrics computation

### Phase 3 (NICE-TO-HAVE)
- [ ] Empirical latency model (Part C)
- [ ] RL-based scheduling policy
- [ ] Visualization/dashboards

---

## Dependencies

Add to your `pom.xml`:

```xml
<!-- Gson for JSON parsing -->
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.10.1</version>
</dependency>
```

---

## Configuration

Add to `SimSettings.properties`:

```properties
# DAG Configuration
DAG_DIRECTORY=path/to/synthetic_dags
SCHEDULING_POLICY=EFT
# Options: RoundRobin, EdgeFirstFeasible, EFT, Custom

# Latency Model (optional)
LATENCY_MODEL=DEFAULT
# Options: DEFAULT, EMPIRICAL

# If EMPIRICAL:
LATENCY_DATA_DIR=path/to/cloud-edge-latency
```

---

## Next Steps

1. **Copy Java files** to `src/edu/boun/edgecloudsim/dagsim/` in your EdgeCloudSim fork
2. **Implement DagRuntimeManager** by extending pseudo code above
3. **Modify EdgeOrchestrator** to use SchedulingPolicy
4. **Run scenario** and validate logs
5. **Add Part C** (empirical latency) when needed

For questions, refer to inline JavaDoc and class comments.
