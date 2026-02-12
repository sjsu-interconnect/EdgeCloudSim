# Cost Calculation Breakdown in EdgeCloudSim

## Overview

EdgeCloudSim calculates the operational cost of executing tasks in the edge-cloud environment. The total cost is composed of two primary components:

1. **Bandwidth Cost** - Cost incurred for data transfer between devices and servers
2. **CPU Cost** - Cost incurred for task computation on servers

## Cost Components

### 1. Bandwidth Cost

The bandwidth cost is calculated based on the total data transferred during task execution.

**Formula:**
```
Bandwidth Cost = Total Data Transferred (bytes) × Bandwidth Cost Per Byte
```

**Default Values:**
- Bandwidth Cost Per Byte: $0.00001 (can be configured)

**When Applied:**
- Network latency causes data transfer between mobile devices and edge/cloud servers
- Each task's input and output data is transferred based on the network path

**Calculation Location:**
- [DefaultMobileDeviceManager.java](../src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java) - Lines where task completion is processed

### 2. CPU Cost

The CPU cost is calculated based on the task execution time on the assigned server.

**Formula:**
```
CPU Cost = Task Service Time (seconds) × CPU Cost Per Second
```

**Default Values:**
- Edge Server CPU Cost: $0.02 per second
- Cloud Server CPU Cost: $0.05 per second
- Mobile Device CPU Cost: $0 (no cost)

**Configuration:**
- These values can be configured in the configuration files:
  - `config/default_config.properties`
  - `config/edge_devices.xml`
  - `config/applications.xml`

**Calculation Location:**
- [DefaultMobileDeviceManager.java](../src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java) - Lines where CPU cost is applied based on execution location

## Total Cost Calculation

The total cost for a task is the sum of bandwidth and CPU costs:

```
Total Task Cost = Bandwidth Cost + CPU Cost
```

### Average Cost Metric

The simulator reports the average cost per completed task:

```
Average Cost = Total Cost (all tasks) / Number of Completed Tasks
```

This metric provides a standard way to compare different scheduling strategies:
- **Lower average cost** = More efficient resource utilization and scheduling
- **Higher average cost** = More data transfer or computation on expensive servers

## Output and Logging

### Console Output (Summary)

When simulation completes, the console displays:

```
average cost: X.XXXXXX$ (bw: Y.XXXXXX$, cpu: Z.XXXXXX$)
```

Where:
- **X.XXXXXX**: Average total cost per task
- **Y.XXXXXX**: Average bandwidth cost per task
- **Z.XXXXXX**: Average CPU cost per task

### Detailed Logs

The detailed log files include per-application cost metrics:

**Log Format:**
```
[completed_tasks] [failed_tasks] [uncompleted_tasks] ... [avg_cost] [avg_bw_cost] [avg_cpu_cost] ...
```

Cost-related fields (in order):
1. **Average Total Cost**: Sum of all costs / number of completed tasks
2. **Average Bandwidth Cost**: Sum of all bandwidth costs / number of completed tasks
3. **Average CPU Cost**: Sum of all CPU costs / number of completed tasks

## Practical Implications

### Cost Optimization Strategies

1. **Reduce Bandwidth Cost:**
   - Choose edge servers close to mobile devices (reduce data transfer)
   - Use compression techniques to reduce data size
   - Minimize input/output data for tasks

2. **Reduce CPU Cost:**
   - Offload to cheaper edge servers instead of expensive cloud servers
   - Optimize task execution time
   - Distribute workload efficiently

3. **Overall Cost Reduction:**
   - Balance between edge and cloud resources
   - Consider task characteristics (computation vs. communication intensive)
   - Apply intelligent orchestration policies

## Example Calculation

### Scenario: Video Processing Task

**Assumptions:**
- Task input: 100 MB = 104,857,600 bytes
- Task output: 50 MB = 52,428,800 bytes
- Total data transfer: 150 MB = 157,286,400 bytes
- Task execution time: 5 seconds on edge server
- Bandwidth cost per byte: $0.00001
- Edge CPU cost: $0.02/sec
- Cloud CPU cost: $0.05/sec

**If executed on Edge Server:**
```
Bandwidth Cost = 157,286,400 × $0.00001 = $1.57
CPU Cost = 5 × $0.02 = $0.10
Total Cost = $1.57 + $0.10 = $1.67
```

**If executed on Cloud Server:**
```
Bandwidth Cost = 157,286,400 × $0.00001 = $1.57
CPU Cost = 5 × $0.05 = $0.25
Total Cost = $1.57 + $0.25 = $1.82
```

**Result:** Edge execution costs $0.15 less than cloud execution (9% savings)

## Configuration Files

### default_config.properties

```properties
# Bandwidth cost per byte
# Default: 0.00001
bandwidth_cost_per_byte=0.00001

# CPU cost per second (edge)
# Default: 0.02
edge_cpu_cost_per_second=0.02

# CPU cost per second (cloud)
# Default: 0.05
cloud_cpu_cost_per_second=0.05
```

### edge_devices.xml

```xml
<edge_device>
    <name>Edge_Server_1</name>
    <!-- Other configurations -->
    <!-- CPU cost inherited from default_config -->
</edge_device>
```

## Implementation Details

### Key Classes

1. **[SimLogger.java](../src/edu/boun/edgecloudsim/utils/SimLogger.java)**
   - Aggregates and logs cost metrics
   - Methods:
     - `setCost(taskId, bwCost, cpuCost)` - Records cost for a task
     - `simStopped()` - Outputs summary statistics including average costs

2. **[DefaultMobileDeviceManager.java](../src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java)**
   - Calculates bandwidth and CPU costs when tasks complete
   - Method: `taskExecuteMonitor()` - Monitors task completion and calls `setCost()`

3. **[SimSettings.java](../src/edu/boun/edgecloudsim/utils/SimSettings.java)**
   - Stores configuration parameters
   - Provides access to cost parameters throughout the simulation

### Data Structures

```java
// Arrays indexed by application type
double[] cost;          // Total cost per application
double[] bwCost;        // Bandwidth cost per application
double[] cpuCost;       // CPU cost per application

// Overall (all applications)
cost[numOfAppTypes];    // Total cost for all tasks
bwCost[numOfAppTypes];  // Total bandwidth cost for all tasks
cpuCost[numOfAppTypes]; // Total CPU cost for all tasks
```

## Cost Calculation Workflow

```
1. Task Created
   ├─ Store task information
   └─ No cost yet

2. Task Execution Starts
   ├─ Track data transfer size
   ├─ Monitor execution location (edge/cloud/mobile)
   └─ No cost yet

3. Task Completes
   ├─ Calculate bandwidth cost
   │  └─ Total bytes transferred × $0.00001/byte
   ├─ Calculate CPU cost
   │  └─ Service time × cost per second (by location)
   ├─ Call SimLogger.setCost(taskId, bwCost, cpuCost)
   └─ Store costs in task's LogItem

4. Simulation Ends
   ├─ Aggregate costs by application type
   ├─ Calculate averages
   ├─ Output summary and detailed logs
   └─ Display cost breakdown in console
```

## Considerations and Limitations

1. **Overhead Not Included:** Orchestration overhead is logged separately, not included in cost metrics
2. **Fixed Cost Model:** Uses simple linear cost model (may not reflect real-world pricing)
3. **No SLA Penalties:** Cost does not include penalties for SLA violations or failed tasks
4. **Simplified Network Costs:** Single bandwidth cost for all network types (GSM, WiFi, LAN, etc.)
5. **No Storage Costs:** Only data transfer costs are considered, not data storage

## Future Enhancements

1. Implement location-based bandwidth costs
2. Add network-type-specific pricing (WiFi cheaper than cellular)
3. Include task failure penalties in cost
4. Support tiered pricing (volume discounts)
5. Add real-time pricing variation
6. Include memory/storage costs

## Troubleshooting

### High Bandwidth Costs
- Check data transfer sizes in task configuration
- Verify network paths are optimized
- Consider using compression

### High CPU Costs
- Verify cost parameters in configuration
- Check if tasks are being executed on cloud (more expensive) vs. edge
- Review orchestration policy performance

### Inconsistent Cost Values
- Verify configuration files are properly loaded
- Check SimLogger initialization
- Ensure setCost() is called for all completed tasks

## References

- [DAG_SCHEDULING_FLOW.md](DAG_SCHEDULING_FLOW.md) - Task execution flow
- [Configuration Guide](CONFIGURATION.md) - Configuration file documentation
- [DefaultMobileDeviceManager.java](../src/edu/boun/edgecloudsim/edge_client/DefaultMobileDeviceManager.java) - Cost calculation implementation
