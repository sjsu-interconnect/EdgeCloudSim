# DAG Scheduling Flow Verification

## TL;DR: Yes, The Logic is Correct ✓

The current implementation **correctly implements** the three-step DAG scheduling you requested:
1. ✅ All DAGs arrive, then stored in `activeDags` map
2. ✅ Only **root tasks** (tasks with `remainingDeps == 0`) are scheduled first
3. ✅ Child tasks scheduled one-by-one as their parents complete

---

## Step-by-Step Flow

### **Step 1: All DAGs Arrive & Are Queued**

**Location:** [`DagRuntimeManager.java` lines 61-67](./src/edu/boun/edgecloudsim/dagsim/DagRuntimeManager.java#L61-L67)

```java
public void scheduleAllDagSubmissions() {
    for (DagRecord dag : allDags) {
        double submitTimeSeconds = dag.getSubmitAtSimMs() / 1000.0;
        CloudSim.send(getId(), this.getId(), submitTimeSeconds, DAG_SUBMIT, dag);
    }
}
```

- All 100 DAGs are scheduled for submission at their designated times
- Each DAG is queued as a `DAG_SUBMIT` event
- Example from log: `[180.00] DAG submitted: dag_1b8611f4a71b4f21 with 62 tasks`

---

### **Step 2: Only ROOT Tasks Are Scheduled**

**Location:** [`DagRuntimeManager.java` lines 85-97](./src/edu/boun/edgecloudsim/dagsim/DagRuntimeManager.java#L85-L97)

```java
private void processDagSubmit(DagRecord dag) {
    double submitTime = CloudSim.clock() * 1000.0;
    dag.setState(DagRecord.DagState.SUBMITTED);
    activeDags.put(dag.getDagId(), dag);  // ← Track this DAG

    System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] DAG submitted: " + dag.getDagId() + " with " + dag.getTotalTasks() + " tasks");

    // ✅ Queue ONLY root tasks (no remaining dependencies)
    for (TaskRecord task : dag.getTasksById().values()) {
        if (task.getRemainingDeps() == 0) {  // ← KEY: Root tasks only!
            task.setReadyTimeMs(submitTime);
            task.setState(TaskRecord.TaskState.READY);
            CloudSim.send(getId(), this.getId(), Math.random() * 0.001, TASK_READY, task);
        }
    }
}
```

**Evidence from logs:**
```
[180.00] DAG submitted: dag_1b8611f4a71b4f21 with 62 tasks
[180.00] Task ready: vae_encode_72830bc5 of DAG dag_1b8611f4a71b4f21 — lengthMI=3773, execEdge=3.773s, execCloud=0.377s
[180.86] Task finished: vae_encode_72830bc5 (1/62)
[180.86] Task ready: unet_denoise_71de2519 of DAG dag_1b8611f4a71b4f21   ← Next task NOW ready!
```

The VAE encoder (`vae_encode_*`) is the root task. Only after it completes (at 180.86s) does the next task become ready.

---

### **Step 3: Child Tasks Scheduled After Parent Completion**

#### **3a. Task Completion Notification**

**Location:** [`DefaultMobileDeviceManager.java` lines 190-195](./src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java#L190-L195)

When a task finishes, the mobile device manager notifies the DAG runtime:

```java
case RESPONSE_RECEIVED_BY_MOBILE_DEVICE:
{
    Task task = (Task) ev.getData();
    
    // Log task completion
    SimLogger.getInstance().taskEnded(task.getCloudletId(), CloudSim.clock());
    
    // ✅ Notify DAG runtime manager (if present) that this cloudlet finished
    if(DagRuntimeManager.getInstance() != null){
        DagRuntimeManager.getInstance().onTaskCloudletFinished(task.getCloudletId(), CloudSim.clock());
    }
    break;
}
```

#### **3b. DAG Task Registry Lookup**

**Location:** [`DagRuntimeManager.java` lines 178-201](./src/edu/boun/edgecloudsim/dagsim/DagRuntimeManager.java#L178-L201)

```java
public void onTaskCloudletFinished(long cloudletId, double finishClock){
    String[] ids = cloudletToDagMap.get(cloudletId);  // ← Look up DAG + task ID
    if(ids == null) return; // not a DAG task

    String dagId = ids[0];
    String taskId = ids[1];

    DagRecord dag = activeDags.get(dagId);
    TaskRecord task = dag.getTask(taskId);
    
    task.setFinishTimeMs(finishClock * 1000.0);
    processTaskFinished(task);  // ← Internal completion handler
    cloudletToDagMap.remove(cloudletId);  // ← Clean up mapping
}
```

#### **3c. Dependency Decrement & Child Unblocking**

**Location:** [`DagRuntimeManager.java` lines 203-227](./src/edu/boun/edgecloudsim/dagsim/DagRuntimeManager.java#L203-L227)

```java
private void processTaskFinished(TaskRecord task) {
    String dagId = findDagIdForTask(task);
    DagRecord dag = activeDags.get(dagId);
    
    task.setState(TaskRecord.TaskState.DONE);
    dag.incrementCompletedTasks();
    
    System.out.println("[" + String.format("%.2f", CloudSim.clock()) + "] Task finished: " + task.getTaskId() + " (" + dag.getCompletedTasks() + "/" + dag.getTotalTasks() + ")");

    // ✅ Iterate all children and decrement their dependencies
    for (String childTaskId : task.getChildren()) {
        TaskRecord child = dag.getTask(childTaskId);
        if (child != null) {
            child.decrementRemainingDeps();  // ← Decrement counter
            
            // ✅ If all parents done, mark ready and send to scheduler
            if (child.getRemainingDeps() == 0) {
                child.setReadyTimeMs(finishTime);
                child.setState(TaskRecord.TaskState.READY);
                CloudSim.send(getId(), this.getId(), 0, TASK_READY, child);  // ← Queue for scheduling
            }
        }
    }

    // ✅ If entire DAG complete, mark and log
    if (dag.isComplete()) {
        dag.setState(DagRecord.DagState.COMPLETE);
        activeDags.remove(dagId);
    }
}
```

---

## Wiring: How Mapping is Established

**Location:** [`DefaultMobileDeviceManager.java` lines 216-220](./src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java#L216-L220)

When a TaskProperty is submitted to the mobile device manager, we register the mapping:

```java
public void submitTask(TaskProperty edgeTask) {
    Task task = createTask(edgeTask);

    // ✅ If this TaskProperty originated from a DAG, register mapping from cloudletId -> DAG task
    if(edgeTask.getDagId() != null && edgeTask.getDagTaskId() != null){
        if(DagRuntimeManager.getInstance() != null){
            DagRuntimeManager.getInstance().registerCloudletMapping(
                task.getCloudletId(), 
                edgeTask.getDagId(), 
                edgeTask.getDagTaskId()
            );
        }
    }
    // ... rest of task submission
}
```

This ensures that when the task **finishes** in CloudSim, we can **reverse-lookup** which DAG + task it belongs to.

---

## Data Structures Tracking Dependencies

1. **`TaskRecord.remainingDeps`** — Counter of unfinished parent tasks
   - Decremented when a parent completes
   - Task becomes READY when counter reaches 0

2. **`cloudletToDagMap`** — Maps CloudSim cloudlet ID → (DAG ID, task ID)
   - Registered when task is submitted to mobile device manager
   - Used to find the DAG task when CloudSim cloudlet finishes

3. **`activeDags`** — Map of currently running DAGs
   - Tasks looked up from DAG by ID when they complete
   - DAG removed when all tasks done

---

## Event Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. All DAGs loaded into memory at startup                       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. Each DAG DAG_SUBMIT event processed                          │
│    → Only root tasks (remainingDeps == 0) sent to scheduler     │
│    → Each root task → TASK_READY event                          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. Root tasks submitted to edge/cloud via SimManager            │
│    → cloudletToDagMap[ cloudletId ] = { dagId, taskId }         │
│    → Task executed by edge/cloud datacenter VMs                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. Task finishes in CloudSim                                    │
│    → Mobile device manager calls onTaskCloudletFinished()       │
│    → Look up DAG + task ID from cloudletToDagMap                │
│    → Call processTaskFinished()                                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. For each child of finished task:                             │
│    → Decrement remainingDeps counter                            │
│    → If counter == 0: send TASK_READY event                    │
│    → Child enters ready queue                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ 6. Repeat from step 3 for newly ready children                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Log Evidence

The log output shows this exact flow working correctly:

```
[180.00] DAG submitted: dag_1b8611f4a71b4f21 with 62 tasks
[180.00] Task ready: vae_encode_72830bc5 of DAG dag_1b8611f4a71b4f21   ← ROOT TASK 1
[180.86] Task finished: vae_encode_72830bc5 (1/62)
[180.86] Task ready: unet_denoise_71de2519 of DAG dag_1b8611f4a71b4f21  ← CHILD 1 (was blocked)
[188.85] Task finished: unet_denoise_71de2519 (2/62)
[188.85] Task ready: sampler_e9fe71a9 of DAG dag_1b8611f4a71b4f21      ← CHILD 2 (was blocked)
[189.38] Task finished: sampler_e9fe71a9 (3/62)
[189.38] Task ready: unet_denoise_d2175cb3 of DAG dag_1b8611f4a71b4f21 ← CHILD 3 (was blocked)
```

Notice:
- Root VAE task finishes at 180.86
- **Immediately after**, UNet task becomes ready (has been waiting, blocked by VAE)
- After UNet finishes, sampler becomes ready
- This is strict sequential dependency enforcement ✓

---

## Summary: Verification Checklist

| Requirement | Status | Evidence |
|---|---|---|
| All DAGs arrive first | ✅ | `scheduleAllDagSubmissions()` queues all DAGs; `activeDags` stores them |
| Only root tasks initially scheduled | ✅ | `processDagSubmit()` checks `remainingDeps == 0` |
| Child tasks blocked until parent completes | ✅ | `remainingDeps` counter > 0 keeps task in WAITING state |
| Parents unblock children on completion | ✅ | `processTaskFinished()` decrements `remainingDeps` and emits TASK_READY |
| Proper cloudlet↔DAG mapping | ✅ | `registerCloudletMapping()` + `cloudletToDagMap` lookup |
| Sequential task execution visible in logs | ✅ | Log shows tasks only becoming READY after dependencies finish |

**Conclusion: The DAG scheduling is working correctly as designed.**

---

## Potential Improvements (Optional)

1. **Parallel Task Execution**: If a task has multiple children waiting, they could be scheduled in parallel once ready (currently sequential within a DAG)
2. **Load Balancing**: Could implement task-stealing across edge datacenters if one is overloaded
3. **Failure Handling**: Currently no rollback if a task fails; could implement retry logic
4. **QoE/Cost Calculation**: As discussed, cost and QoE metrics need to be populated in `DefaultMobileDeviceManager`
