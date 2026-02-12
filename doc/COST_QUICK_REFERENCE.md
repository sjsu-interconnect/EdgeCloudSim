# Cost Calculation Quick Reference

## Formula Summary

### Per-Task Cost
```
Task Cost = Bandwidth Cost + CPU Cost

Where:
  Bandwidth Cost = Data Transferred (bytes) × $0.00001/byte
  
  CPU Cost = Service Time (seconds) × CPU Rate
    - Edge Server: $0.02/second
    - Cloud Server: $0.05/second
    - Mobile Device: $0/second
```

### Average Cost
```
Average Cost = Total Cost (all tasks) / Completed Tasks Count
Average BW Cost = Total BW Cost / Completed Tasks Count
Average CPU Cost = Total CPU Cost / Completed Tasks Count
```

## Cost Breakdown Interpretation

### Example Output
```
average cost: 1.671267$ (bw: 1.570000$, cpu: 0.101267$)
```

**Interpretation:**
- Average cost per task: **$1.67**
  - 94% from bandwidth: **$1.57** (data transfer)
  - 6% from CPU: **$0.10** (computation)

### Cost Ratio Analysis

| BW % | CPU % | Meaning | Optimization |
|------|-------|---------|--------------|
| >80% | <20% | Communication intensive | Reduce data transfer |
| <20% | >80% | Computation intensive | Use cheaper servers |
| 50-50 | 50-50 | Balanced | Optimize both aspects |

## Configuration Quick Start

### Default Costs
```
Bandwidth:     $0.00001 per byte
Edge CPU:      $0.02 per second
Cloud CPU:     $0.05 per second
```

### Modify Costs (in default_config.properties)
```properties
bandwidth_cost_per_byte=0.00001        # Adjust data transfer cost
edge_cpu_cost_per_second=0.02          # Adjust edge computing cost
cloud_cpu_cost_per_second=0.05         # Adjust cloud computing cost
```

## Cost Impact Examples

### Scenario 1: Data Transfer Heavy
- Input: 500 MB, Output: 500 MB
- Total transfer: 1 GB = 1,073,741,824 bytes
- Execution time: 1 second on edge

```
BW Cost = 1,073,741,824 × $0.00001 = $10.74 (99%)
CPU Cost = 1 × $0.02 = $0.02 (1%)
Total = $10.76
```
**Action:** Compress data or use local processing

### Scenario 2: CPU Intensive
- Input: 1 MB, Output: 1 MB  
- Total transfer: 2 MB = 2,097,152 bytes
- Execution time: 10 seconds

```
On Edge:
  BW Cost = 2,097,152 × $0.00001 = $0.02 (1%)
  CPU Cost = 10 × $0.02 = $0.20 (99%)
  Total = $0.22

On Cloud:
  BW Cost = $0.02 (1%)
  CPU Cost = 10 × $0.05 = $0.50 (99%)
  Total = $0.52

Savings by using Edge = $0.30 (57%)
```
**Action:** Prefer edge servers for computation

## Log File Columns (Generic Results)

The output files contain these columns related to cost:

```
[Index] Column Name                Description
...
[N]     Average Cost              Total cost / completed tasks
[N+1]   Average Bandwidth Cost    BW cost / completed tasks
[N+2]   Average CPU Cost          CPU cost / completed tasks
...
```

## Console Output Interpretation

### Full Summary Example
```
simulation: X
scenario: Y
Execution Time: Z minutes

average task service time: A ms
average task processing time: B ms
average network delay: C ms
average cost: $D (bw: $E, cpu: $F)
```

### What Each Cost Tells You

| Metric | What It Means |
|--------|---------------|
| High BW Cost | Tasks involve large data transfers |
| High CPU Cost | Tasks run on expensive servers or long execution |
| Total Cost | Overall efficiency of orchestration policy |

## Performance Metrics Integration

The cost calculation is independent of but complements these metrics:

- **Service Time**: Wall-clock time from task creation to completion
  - Cost: ✓ (CPU and BW components)
  - Latency: ✓ (network + processing delays)

- **Processing Time**: CPU execution time only
  - Cost: ✓ (CPU component)
  - Latency: ✗ (excludes network delays)

- **Network Delay**: Data transfer time
  - Cost: ✓ (BW component)
  - Latency: ✓ (direct network delay)

## Comparing Strategies Using Cost

### Strategy A vs Strategy B

```
Strategy A:
  Average Cost: $2.50 (bw: $1.00, cpu: $1.50)
  Success Rate: 95%
  
Strategy B:
  Average Cost: $2.10 (bw: $0.80, cpu: $1.30)
  Success Rate: 92%
  
Result: Strategy B is 16% cheaper ($0.40 savings)
But 3% lower success rate - choose based on priorities
```

## Cost Sensitivity Analysis

### Changing Bandwidth Cost

```
Scenario: 100 MB task, 5 second execution on edge
  Current: BW=$1.00, CPU=$0.10, Total=$1.10
  
If BW cost doubles ($0.00002):
  New Total = $2.00 (80% increase)
  
If BW cost halves ($0.000005):
  New Total = $0.55 (50% decrease)
```

### Changing CPU Cost

```
Scenario: 10 MB task, 5 second execution on edge
  Current: BW=$0.0001, CPU=$0.10, Total=$0.1001
  
If CPU cost doubles (edge=$0.04):
  New Total = $0.2001 (100% increase)
  
Cloud execution becomes 2.5x more expensive
```

## Troubleshooting High Costs

### High Bandwidth Cost?
1. Check data sizes in task configuration
2. Verify network links aren't congested
3. Look for unnecessary data transfer
4. Consider data compression techniques

### High CPU Cost?
1. Check if tasks run on cloud instead of edge
2. Verify task execution times
3. Review CPU cost parameters
4. Optimize orchestration policy

### Unexpected Cost Variations?
1. Check failed task count (failed tasks don't contribute cost)
2. Verify cost parameters in config files
3. Check for migration between servers
4. Review task retry logic

## Advanced: Custom Cost Analysis

To extract and analyze costs programmatically:

1. Parse output files (format: DELIMITER-separated values)
2. Extract cost columns (N, N+1, N+2)
3. Aggregate by application type
4. Compare against baseline or target

Example analysis:
```
For each application:
  - Calculate cost per completed task
  - Identify tasks with highest costs
  - Find optimal edge/cloud distribution
  - Recommend improvements
```
