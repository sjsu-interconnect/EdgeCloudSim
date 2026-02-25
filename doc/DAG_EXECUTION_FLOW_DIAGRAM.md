# DAG Task Execution Flow Diagram

## Complete Task Execution Time Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                  YOUR JSON DAG FILES                                │
│                  (synthetic_dags.json)                              │
│                                                                      │
│  {                                                                   │
│    "task_id": "vae_encode_72830bc5",                               │
│    "duration_ms": 377.34863367660614,  ← THIS IS YOUR VALUE      │
│    "memory_mb": 256,                                                │
│    ...                                                              │
│  }                                                                   │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ↓
                    ┌─────────────────────────┐
                    │  DagJsonLoader.java     │
                    │  Line 189:              │
                    │  setDurationMs(        │
                    │    taskJson.get(       │
                    │      "duration_ms"     │
                    │    )                   │
                    │  )                     │
                    └─────────────────────────┘
                                  │
                                  ↓
                    ┌─────────────────────────┐
                    │  TaskRecord.durationMs  │
                    │  = 377.34863... ms      │
                    └─────────────────────────┘
                                  │
                                  ↓
        ┌─────────────────────────────────────────────────┐
        │ DagRuntimeManager.java (Line 124)               │
        │                                                  │
        │ lengthMI = duration_ms × cloud_MIPS / 1000      │
        │ lengthMI = 377.34 × 10000 / 1000                │
        │ lengthMI = 3773 MI                              │
        │                                                  │
        │ Default Cloud MIPS: 10,000                       │
        └─────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ DagRuntimeManager.java (Lines 128-129)                   │
        │                                                           │
        │ execSecEdge = lengthMI / mobile_MIPS                     │
        │ execSecEdge = 3773 / 1000 = 3.773 seconds                │
        │                                                           │
        │ execSecCloud = lengthMI / cloud_MIPS                     │
        │ execSecCloud = 3773 / 10000 = 0.377 seconds              │
        │                                                           │
        │ Default Mobile MIPS: 1,000                               │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ Console Output (Log File)                                │
        │                                                           │
        │ "[180.00] Task ready: vae_encode_72830bc5 of DAG ...    │
        │  — lengthMI=3773, execEdge=3.773s, execCloud=0.377s"    │
        │                                                           │
        │ These are PROJECTED times, actual depends on:            │
        │ - Server availability                                    │
        │ - Network delays                                         │
        │ - Task dependencies (parallelism)                        │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ Task Execution Phase                                     │
        │                                                           │
        │ Actual Execution Time = Network Delay + Processing Time  │
        │                       + Queuing Delay                    │
        │                       (Depends on resource contention)   │
        │                                                           │
        │ NOT just "execEdge" or "execCloud"!                      │
        │ (Those are projections for scheduling, not actual)       │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ Task Completion Event                                    │
        │                                                           │
        │ All child tasks have dependencies updated                │
        │ Ready tasks move to READY state                          │
        │ Cycle continues until all tasks complete                 │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ DAG Completion (DagRuntimeManager.java, Line 235)        │
        │                                                           │
        │ if (dag.isComplete()) {                                  │
        │   long makespanMs = dag.getMakespanMs()                  │
        │   makespanMs = completeTimeMs - submitAtSimMs            │
        │ }                                                         │
        │                                                           │
        │ Example: 1020000 - 831270 = 98730 ms = 98.73 sec        │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ↓
        ┌──────────────────────────────────────────────────────────┐
        │ DAG Summary                                              │
        │                                                           │
        │ Average Makespan (100 DAGs) = 98740.27 ms               │
        │ = ~98.7 seconds per DAG                                  │
        │ = NOT 9 minutes (540 seconds)                           │
        └──────────────────────────────────────────────────────────┘
```

## Key Differences Explained

### Individual Task Duration vs DAG Makespan

```
INDIVIDUAL TASK (from log):
┌────────────────────────────────────┐
│ Task: vae_encode_72830bc5          │
│ Your Duration (JSON): 377.34 ms    │
│ Scheduled Duration:                │
│   - Edge: 3773 / 1000 = 3.77s      │
│   - Cloud: 3773 / 10000 = 0.38s    │
│                                    │
│ BUT actual execution includes:     │
│ + Network transfer delay           │
│ + Queuing delay                    │
│ + Processing (might be parallel)   │
└────────────────────────────────────┘

DAG TOTAL MAKESPAN (Critical Path):
┌─────────────────────────────────────────────────┐
│ Example: 62 Tasks in DAG                        │
│                                                  │
│ If all serial:                                   │
│   Total = sum of all task durations             │
│   = ~30 seconds of tasks × overhead             │
│   = could be 100+ seconds                       │
│                                                  │
│ What you're seeing:                              │
│   Critical Path Duration = 98.7 seconds         │
│                                                  │
│ This means:                                      │
│   - Longest chain of dependent tasks ≈ 98.7 sec│
│   - Most other tasks run in parallel            │
│   - NOT the sum of all task durations           │
└─────────────────────────────────────────────────┘
```

## Configuration Impact

```
┌──────────────────────────────────────────────────────────┐
│ SimSettings.getInstance()                               │
│                                                          │
│ getMipsForCloudVM()    = 10000 (default)                │
│ getMipsForMobileVM()   = 1000  (default)                │
│                                                          │
│ These affect:                                            │
│ execTime = lengthMI / targetMIPS                         │
│                                                          │
│ If you change these, execution times will scale:        │
│ - Double MIPS → Half the execution time                │
│ - Half MIPS → Double the execution time                │
└──────────────────────────────────────────────────────────┘
```

## Where Task Duration Gets Used

```
Flow Through Code:
┌─────────────────────────────────────────────────────────┐
│ 1. JSON Load                                            │
│    task.setDurationMs(taskJson.get("duration_ms"))     │
│    ↓                                                    │
│ 2. Task Scheduling (DagRuntimeManager)                 │
│    lengthMI = duration_ms × cloudMIPS / 1000           │
│    ↓                                                    │
│ 3. Execution Projection (for logging/scheduling)       │
│    execEdge = lengthMI / edgeMIPS                      │
│    execCloud = lengthMI / cloudMIPS                    │
│    ↓                                                    │
│ 4. Task Sent to CloudSim (as TaskProperty)            │
│    - lengthMI used by CloudSim for execution time      │
│    ↓                                                    │
│ 5. Actual Execution (depends on resources)            │
│    - May be faster/slower than projected               │
│    - Depends on contention and delays                  │
│    ↓                                                    │
│ 6. Task Completion Tracked                             │
│    - Actual time recorded (not just projected)         │
│    ↓                                                    │
│ 7. DAG Makespan Calculated                             │
│    - Completion time - Submission time                 │
│    - Critical path determines this                     │
└─────────────────────────────────────────────────────────┘
```

## Summary

| Aspect | Source | How It's Used |
|--------|--------|---------------|
| **Task Duration** | Your JSON `duration_ms` | Converted to lengthMI |
| **lengthMI** | duration_ms × cloud_MIPS / 1000 | Used by CloudSim for execution time |
| **execEdge/execCloud** | lengthMI / target_MIPS | Projected time (logging only) |
| **Actual Execution Time** | CloudSim simulation | Includes delays, queuing, parallelism |
| **DAG Makespan** | Last task completion - First task submission | Critical path |
| **Your Result** | 98.7 seconds | Critical path ≠ sum of all tasks |
