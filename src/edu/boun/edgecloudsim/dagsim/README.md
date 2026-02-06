# EdgeCloudSim DAG Scheduling Integration

A complete Java implementation for loading and scheduling AI/ML workload DAGs in EdgeCloudSim.

## What's Included

### Part A: DAG Ingestion & Dependency Engine (Complete ✅)

Core data models and JSON loader for synthetic DAGs:

- **TaskRecord.java** - Individual task with dependencies and runtime state
- **DagRecord.java** - Complete DAG with task graph
- **DagJsonLoader.java** - JSON parser for DAG files (uses Gson)

**Features:**
- Loads DAGs from JSON directory
- Builds task dependency graphs automatically
- Tracks task states (CREATED → READY → SCHEDULED → RUNNING → DONE)
- Computes relative submission times for simulation

### Part B: Scheduling Policy API & Baselines (Complete ✅)

Pluggable scheduling system with baseline implementations:

- **SchedulingPolicy.java** - Interface for custom policies
- **TaskContext.java** - Input: task properties and readiness
- **PlacementDecision.java** - Output: tier, datacenter, VM selection
- **ClusterState.java** - Input: current cluster resources and queues

**Baseline Policies:**
1. **RoundRobinPolicy** - Cycle through all VMs evenly
2. **EdgeFirstFeasiblePolicy** - Prefer edge if resources exist
3. **EFTPolicy** - Earliest Finish Time (minimizes task completion)

**To add custom policy:**
```java
public class MyPolicy implements SchedulingPolicy {
    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        // Your logic here
        return decision;
    }
    
    @Override
    public String getPolicyName() { return "MyPolicy"; }
}
```

## Integration Steps

### 1. Copy Files to EdgeCloudSim

```bash
cp *.java /path/to/EdgeCloudSim/src/edu/boun/edgecloudsim/dagsim/
```

### 2. Add Maven Dependency

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.10.1</version>
</dependency>
```

### 3. Create DagRuntimeManager

Extends SimEntity to manage DAG lifecycle and task dependencies. See INTEGRATION_GUIDE.md for pseudocode.

### 4. Modify EdgeOrchestrator

Integrate SchedulingPolicy into task placement decisions. See INTEGRATION_GUIDE.md for code pattern.

### 5. Load DAGs in Scenario

```java
List<DagRecord> dags = DagJsonLoader.loadAllDags("path/to/synthetic_dags");
DagRuntimeManager manager = new DagRuntimeManager(dags, new EFTPolicy());
```

## File Structure

```
edgecloudsim-dagsim-integration/
├── Part A: DAG Ingestion
│   ├── TaskRecord.java
│   ├── DagRecord.java
│   └── DagJsonLoader.java
├── Part B: Scheduling Policies
│   ├── SchedulingPolicy.java
│   ├── TaskContext.java
│   ├── PlacementDecision.java
│   ├── ClusterState.java
│   ├── RoundRobinPolicy.java
│   ├── EdgeFirstFeasiblePolicy.java
│   └── EFTPolicy.java
├── INTEGRATION_GUIDE.md (detailed integration)
└── README.md (this file)
```

## What's NOT Included (Coming in Part C & D)

- **DagRuntimeManager** - You'll implement this by extending SimEntity
- **Empirical Latency Model** - Optional feature using Charyyev dataset
- **CSV Logging** - Implement during scenario completion

See INTEGRATION_GUIDE.md for pseudocode and patterns.

## DAG JSON Schema (Expected Input)

```json
{
  "dag_id": "dag_123abc",
  "submission_time": 1704067200.5,
  "total_duration_ms": 26619.95,
  "total_gpu_memory_mb": 10135.55,
  "num_inference_steps": 30,
  "prompt_length": 42,
  "num_images": 8,
  "has_lora": false,
  "has_controlnet": false,
  "tasks": [
    {
      "task_id": "vae_encode_4dd8deee",
      "task_type": "vae_encode",
      "duration_ms": 425.07,
      "memory_mb": 2059.12,
      "gpu_memory_mb": 2331.49,
      "gpu_utilization": 0.622,
      "depends_on": []
    },
    {
      "task_id": "unet_denoise_50ec8f58",
      "task_type": "unet_denoise",
      "duration_ms": 843.75,
      "memory_mb": 6112.19,
      "gpu_memory_mb": 5401.78,
      "gpu_utilization": 0.438,
      "depends_on": ["vae_encode_4dd8deee"]
    }
  ]
}
```

## Example Usage

```java
// Load DAGs
List<DagRecord> dags = DagJsonLoader.loadAllDags("synthetic_dags/");

// Print summary
for (DagRecord dag : dags) {
    System.out.println("DAG: " + dag.getDagId());
    System.out.println("  Tasks: " + dag.getTotalTasks());
    System.out.println("  Submit (relative): " + dag.getSubmitAtSimMs() + " ms");
    
    for (TaskRecord task : dag.getTasksById().values()) {
        System.out.println("  - " + task.getTaskId() + 
                          " (" + task.getTaskType() + ")" +
                          " deps=" + task.getDependsOn().size());
    }
}

// Use in scheduling
SchedulingPolicy policy = new EFTPolicy();
TaskContext ctx = new TaskContext();
ctx.taskId = "some_task";
ctx.lengthMI = 10000;
ctx.cpuMemoryMb = 2000;
ctx.gpuMemoryMb = 2300;

ClusterState state = buildClusterState(); // Your implementation
PlacementDecision decision = policy.decide(ctx, state);

System.out.println("Scheduled to: " + decision.getTierName() + 
                  " DC " + decision.destDatacenterId +
                  " VM " + decision.destVmId);
```

## Key Design Principles

1. **Modularity**: DAG models, policies, and runtime are independent
2. **Extensibility**: SchedulingPolicy interface allows custom implementations
3. **Stateless Policies**: Decisions based only on task + cluster state
4. **Type Safety**: Strong typing for tier constants and states

## Next Steps

1. **Read INTEGRATION_GUIDE.md** for detailed integration patterns
2. **Copy Java files** to your EdgeCloudSim fork
3. **Implement DagRuntimeManager** - see pseudocode in guide
4. **Integrate with EdgeOrchestrator** - see code patterns in guide
5. **(Optional) Add Part C:** Empirical latency model
6. **(Optional) Add Part D:** CSV logging and metrics

## Dependencies

- **Gson 2.10+** - JSON parsing
- **CloudSim 5.0+** - Core simulation framework
- **EdgeCloudSim** - Edge-cloud infrastructure simulation

## License

Same as your EdgeCloudSim fork.

## Support

Refer to inline JavaDoc in source files. Refer to INTEGRATION_GUIDE.md for architecture and patterns.

---

**Status**: Part A & B Complete ✅ | Part C & D Template in Guide 📋
