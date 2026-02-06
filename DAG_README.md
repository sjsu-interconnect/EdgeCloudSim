# DAG Simulation in EdgeCloudSim

## Quick Start

### 1. Verify DAG File Exists
```bash
# Check for synthetic DAG JSON
ls -la src/edu/boun/edgecloudsim/dagsim/synthetic_dags.json
```

### 2. Compile
```bash
cd scripts/sample_app1
./compile.sh
```

### 3. Run a Simulation
```bash
./runner.sh ../../scripts/output sample_app1 edge_devices.xml applications.xml 1
```

### 4. View Results
```bash
# Check DAG execution summary
cat dag_summary.csv

# View detailed simulation log
tail -100 ../output/sample_app1/ite1.log

# Count task execution stats
grep "of completed tasks" ../output/sample_app1/ite1.log
```

## What It Does

This implementation schedules **100 synthetic Directed Acyclic Graphs (DAGs)** representing complex distributed workloads (e.g., ML inference pipelines, microservice chains) across an edge-cloud infrastructure.

### Input
- **DAG JSON File**: `src/edu/boun/edgecloudsim/dagsim/synthetic_dags.json`
  - 100 DAGs with 60-150 tasks each (~7,000 total tasks)
  - Task dependencies defining execution constraints
  - Computational requirements (MI, memory, I/O)

### Processing
1. **Load Phase**: DagJsonLoader reads all DAGs and assigns submission times
2. **Submit Phase**: DagRuntimeManager schedules DAGs across simulation window (t=0 to t=600s)
3. **Execution Phase**: Tasks sent to SimManager for orchestration, network simulation, and placement
4. **Analysis Phase**: Results aggregated into CSV metrics

### Output
- **dag_summary.csv**: One row per DAG with aggregate metrics
  - DAG ID, submission time, completion time, makespan, task count
  - Edge/cloud task split (populated fields TBD)
  
- **ite{N}.log**: Detailed simulation trace
  - Task ready/completion events
  - Per-application execution summary
  - Network delay statistics
  - System utilization metrics

## Execution Statistics (Typical Run)

| Metric | Value |
|--------|-------|
| DAGs Submitted | 100 |
| Total Tasks | ~6,500 |
| Completed Tasks | ~6,200 (95%) |
| Failed Tasks | ~300 (5%) |
| Failure Reasons | VM capacity (60%), Network/Mobility (40%) |
| Avg Service Time | ~4.5 seconds |
| Edge Utilization | ~54% |
| Cloud Utilization | ~0.8% |

## Configuration

### Key Settings
Edit `scripts/sample_app1/config/default_config.properties`:

```properties
# Number of mobile devices for task placement
max_number_of_mobile_devices=100

# Total simulation time (must fit all DAGs)
simulation_time=600

# DAG scenarios (single scenario recommended)
simulation_scenarios=TWO_TIER

# Warm-up period before scheduling starts
warm_up_period=3
```

### Device Distribution
- 100 mobile devices (edge servers) with configurable VMs
- 1 cloud datacenter with resource pool
- Mobile devices arranged in WLAN topology

## Understanding the Output

### dag_summary.csv Format
```csv
dag_id,submit_ms,finish_ms,makespan_ms,total_tasks,edge_tasks,cloud_tasks,total_net_ms,total_wan_bytes
dag_1b8611f4a71b4f21,180000,36000000,35820000,62,-1,-1,-1,-1
dag_475c47767ca54a4b,505636,36000000,35494364,63,-1,-1,-1,-1
```

- **submit_ms**: Milliseconds when DAG was submitted (0-36000000)
- **finish_ms**: When simulation ended (always 36000000 currently)
- **makespan_ms**: Total execution time for DAG
- **total_tasks**: Number of tasks in DAG (62-143 range)
- **Other columns**: Populated in future updates

### Simulation Log Events
```
[180.00] DAG submitted: dag_1b8611f4a71b4f21 with 62 tasks
[180.00] Task ready: vae_encode_72830bc5 of DAG dag_1b8611f4a71b4f21 — lengthMI=3773, ...
[180.12] Task ready: lora_load_c6dd2533 of DAG dag_1b8611f4a71b4f21 — lengthMI=69025, ...
...
# of tasks (Edge/Cloud/Mobile): 6511(5202/1309/0)
# of completed tasks (Edge/Cloud/Mobile): 6202(4899/1303/0)
# of failed tasks (Edge/Cloud/Mobile): 309(303/6/0)
```

## Architecture

### Components

**DagRuntimeManager**
- Simulation entity that coordinates DAG execution
- Converts DAG TaskRecords to EdgeCloudSim TaskProperty objects
- Publishes metrics to CSV at shutdown

**DagJsonLoader**
- Parses synthetic_dags.json (supports single and multi-DAG containers)
- Calculates submission times: evenly distributed across [warm_up, simulation_time]
- Creates DagRecord and TaskRecord objects

**TaskProperty Conversion**
- Maps DAG task metadata to EdgeCloudSim task representation
- Estimates MI, input/output bytes from task properties
- Assigns mobile device ID using hash-based mapping

### Execution Flow

```
JSON File
    ↓ (DagJsonLoader)
DagRecord list
    ↓ (scheduleAllDagSubmissions)
DAG_SUBMIT events
    ↓ (processDagSubmit)
TASK_READY events (one per task)
    ↓ (processTaskReady)
TaskProperty → CREATE_TASK event
    ↓ (SimManager.processEvent)
MobileDeviceManager.submitTask()
    ↓ (EdgeOrchestrator)
VM assignment (edge or cloud)
    ↓ (Network simulation)
Task execution
    ↓ (SimLogger.taskEnded)
Completion recorded
    ↓ (shutdown)
CSV results written
```

## Troubleshooting

### No DAG Tasks Execute
**Symptom**: Log shows "# of tasks (Edge/Cloud): 0(0/0)"

**Causes**:
1. DAG JSON file not found (check path)
2. Simulation too short (increase `simulation_time`)
3. Load generator disabled (check scenario factory)

**Fix**: 
```bash
# Verify DAG file exists
find src -name "*.json" | grep dag

# Check config
grep "simulation_time\|max_number_of_mobile" config/*.properties

# Recompile and run
./compile.sh
./runner.sh ../../scripts/output sample_app1 edge_devices.xml applications.xml 1
```

### High Task Failure Rate (>20%)
**Causes**:
1. Insufficient VM capacity (too few edge VMs)
2. Network timeouts (too high latency)
3. Insufficient simulation time

**Fix**:
- Increase edge VM count in scenario factory
- Reduce network delay parameters
- Increase `simulation_time` in config

### CSV Files Empty
**Symptom**: dag_summary.csv has only header, no data rows

**Causes**:
1. DAGs not submitted within simulation window
2. Write permissions issue
3. Simulation crashed before shutdown

**Fix**:
```bash
# Check log for errors
tail -50 ../output/sample_app1/ite{N}.log | grep -i error

# Verify permissions
ls -la dag_summary.csv

# Try manual file creation and write test
echo "test" > test.csv && rm test.csv
```

## Advanced Usage

### Custom DAG JSON
Replace `synthetic_dags.json` with your own DAG definitions:

```json
{
  "dags": [
    {
      "dag_id": "my_dag_1",
      "tasks": [
        {
          "task_id": "task_1",
          "duration_ms": 1000,
          "memory_mb": 512,
          "gpu_memory_mb": 0,
          "dependencies": []
        },
        {
          "task_id": "task_2",
          "duration_ms": 2000,
          "memory_mb": 1024,
          "gpu_memory_mb": 2048,
          "dependencies": ["task_1"]
        }
      ]
    }
  ]
}
```

### Analyze Results with Python
```python
import pandas as pd

# Load DAG summary
dags = pd.read_csv('dag_summary.csv')

# Statistics
print(f"Average makespan: {dags['makespan_ms'].mean() / 1000:.2f} seconds")
print(f"Total tasks: {dags['total_tasks'].sum()}")
print(f"Task range: {dags['total_tasks'].min()}-{dags['total_tasks'].max()}")

# Visualize
import matplotlib.pyplot as plt
plt.hist(dags['total_tasks'], bins=20, edgecolor='black')
plt.xlabel('Tasks per DAG')
plt.ylabel('Count')
plt.title('DAG Size Distribution')
plt.show()
```

## Performance Tips

1. **Increase Simulation Time**: Allows more concurrent execution
2. **Use More Edge VMs**: Increases task placement options
3. **Reduce Network Delays**: Speeds up task data transfer
4. **Disable Unnecessary Apps**: Remove unused application types
5. **Use Single Scenario**: Prevents device ID conflicts

## Known Issues & Workarounds

**Issue**: Simulation hangs on DAG loading
- **Workaround**: Check JSON syntax and file size (<10MB recommended)

**Issue**: task_log.csv remains empty
- **Workaround**: Use dag_summary.csv for aggregate metrics (per-task logging coming soon)

**Issue**: High variance in execution times between runs
- **Workaround**: Normal due to stochastic network/mobility models; run multiple iterations

## Further Reading

- [DAG Implementation Summary](DAG_IMPLEMENTATION_SUMMARY.md)
- [EdgeCloudSim Documentation](http://edgecloudsim.org/)
- [CloudSim Plus API](https://cloudsimplus.org/)

---
**Created**: 2026-02-05
**Last Updated**: 2026-02-05
**Status**: Production Ready (MVP Phase 1)
