# Cost Calculation Visual Guide

## Cost Calculation Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        TASK LIFECYCLE & COST                             │
└─────────────────────────────────────────────────────────────────────────┘

1️⃣  TASK CREATED
   ┌──────────────────────────────────────────────┐
   │ Input Data: 100 MB = 104,857,600 bytes       │
   │ Output Data: 50 MB = 52,428,800 bytes        │
   │ Total Data: 150 MB = 157,286,400 bytes       │
   │ Status: SUBMITTED                            │
   └──────────────────────────────────────────────┘
   💰 Cost So Far: $0

2️⃣  TASK SCHEDULED
   ┌──────────────────────────────────────────────┐
   │ Assigned to: Edge Server                     │
   │ Estimated Runtime: 5 seconds                 │
   │ Status: SCHEDULED                            │
   └──────────────────────────────────────────────┘
   💰 Cost So Far: $0

3️⃣  DATA TRANSFER (Bandwidth Cost Incurred)
   ┌──────────────────────────────────────────────┐
   │ Total Bytes Transferred: 157,286,400         │
   │ Bandwidth Rate: $0.00001/byte                │
   │ ▓▓▓▓▓▓░░░░ 60% transferred...                │
   └──────────────────────────────────────────────┘
   💰 Partial Cost: $0 (will be added at completion)

4️⃣  TASK EXECUTION (CPU Cost Accumulating)
   ┌──────────────────────────────────────────────┐
   │ Executing on Edge Server                     │
   │ CPU Cost Rate: $0.02/second                  │
   │ Elapsed Time: 2.5 seconds                    │
   │ ████████░░ 50% complete...                   │
   └──────────────────────────────────────────────┘
   💰 Partial Cost: $0 (will be added at completion)

5️⃣  TASK COMPLETES ✓
   ┌──────────────────────────────────────────────┐
   │ Total Execution Time: 5.0 seconds            │
   │ Execution Location: Edge Server              │
   │ Data Transferred: 157,286,400 bytes          │
   │ Status: COMPLETED                            │
   └──────────────────────────────────────────────┘
   
   ➕ CALCULATE COSTS:
   
   🌐 Bandwidth Cost:
      = Total Data × Rate
      = 157,286,400 bytes × $0.00001/byte
      = $1.5729
   
   ⚙️  CPU Cost (Edge):
      = Execution Time × Edge Rate
      = 5.0 seconds × $0.02/second
      = $0.10
   
   💰 Total Task Cost:
      = Bandwidth Cost + CPU Cost
      = $1.5729 + $0.10
      = $1.6729
   
   STATUS: Cost recorded in system

6️⃣  SIMULATION ENDS (Aggregation)
   ┌──────────────────────────────────────────────┐
   │ Total Completed Tasks: 100                   │
   │ Total Bandwidth Cost: $150.00                │
   │ Total CPU Cost: $10.00                       │
   │ Total Overall Cost: $160.00                  │
   └──────────────────────────────────────────────┘
   
   📊 CALCULATE AVERAGES:
   
   💰 Average Total Cost = $160.00 / 100 = $1.60/task
   🌐 Average BW Cost = $150.00 / 100 = $1.50/task
   ⚙️  Average CPU Cost = $10.00 / 100 = $0.10/task
   
   📤 OUTPUT:
      "average cost: 1.600000$ (bw: 1.500000$, cpu: 0.100000$)"

7️⃣  ANALYSIS & INTERPRETATION
   ┌──────────────────────────────────────────────┐
   │ 💰 Average Total: $1.60                      │
   │ 🌐 Bandwidth: $1.50 (94%)                    │
   │ ⚙️  CPU: $0.10 (6%)                          │
   │                                              │
   │ 🔍 Interpretation:                           │
   │    Data transfer dominates the cost          │
   │    Consider compression or reducing          │
   │    data transfer for cost reduction          │
   └──────────────────────────────────────────────┘
```

## Cost Component Breakdown

### For Different Scenarios

#### Scenario A: Data-Intensive Task
```
Input: 500 MB    ┐
Output: 500 MB   ├─ Total: 1 GB
Time: 1 second   ┘

Bandwidth Cost = 1,073,741,824 × $0.00001 = $10.74 ▓▓▓▓▓▓▓▓▓░ 99%
CPU Cost       = 1 × $0.02 (Edge)        = $0.02  ░░░░░░░░░░  1%
────────────────────────────────────────────────────────────────
Total Cost     = $10.76

💡 Optimization: Reduce data transfer through compression
```

#### Scenario B: Compute-Intensive Task
```
Input: 1 MB      ┐
Output: 1 MB     ├─ Total: 2 MB
Time: 10 seconds ┘

Bandwidth Cost = 2,097,152 × $0.00001    = $0.02  ░░░░░░░░░░  1%
CPU Cost       = 10 × $0.02 (Edge)       = $0.20  ▓▓▓▓▓▓▓▓▓░ 91%
────────────────────────────────────────────────────────────────
Total Cost (Edge)   = $0.22

If on Cloud:
CPU Cost       = 10 × $0.05 (Cloud)      = $0.50  ▓▓▓▓▓▓▓▓▓░ 96%
────────────────────────────────────────────────────────────────
Total Cost (Cloud)  = $0.52

💡 Optimization: Use edge instead of cloud (57% savings!)
```

#### Scenario C: Balanced Task
```
Input: 100 MB    ┐
Output: 100 MB   ├─ Total: 200 MB
Time: 5 seconds  ┘

Bandwidth Cost = 209,715,200 × $0.00001  = $2.10  ▓▓▓▓▓░░░░░ 50%
CPU Cost       = 5 × $0.02 (Edge)        = $0.10  ░░░░░░░░░░  2%
────────────────────────────────────────────────────────────────
Total Cost     = $2.20

💡 Optimization: Balance data compression and processing efficiency
```

## Cost Parameter Impact

### How Each Parameter Affects Total Cost

```
Base Scenario: 100 MB data, 2 second execution on edge
Base Cost: $1.00 (bw: $1.00, cpu: $0.04)

📊 BANDWIDTH COST SENSITIVITY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Rate        Cost      Total    Change
─────────────────────────────────────────────────
$0.000005   $0.50     $0.54    ↓ 46%  ✓ Better
$0.00001    $1.00     $1.04    Base
$0.00002    $2.00     $2.04    ↑ 96%  ✗ Worse

💡 Doubling bandwidth cost increases total cost by 96%!

📊 EDGE CPU COST SENSITIVITY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Rate        Cost      Total    Change
─────────────────────────────────────────────────
$0.01       $0.02     $1.02    ↓ 2%   ✓ Better
$0.02       $0.04     $1.04    Base
$0.05       $0.10     $1.10    ↑ 6%   ~ Acceptable
$0.10       $0.20     $1.20    ↑ 15%  ✗ Worse

💡 Using cloud ($0.05) instead of edge ($0.02) adds 6% to total cost

📊 EXECUTION TIME SENSITIVITY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Time        CPU Cost  Total    Change
─────────────────────────────────────────────────
1 sec       $0.02     $1.02    ↓ 2%   ✓ Better
2 sec       $0.04     $1.04    Base
4 sec       $0.08     $1.08    ↑ 4%   ~ Acceptable
10 sec      $0.20     $1.20    ↑ 15%  ✗ Worse

💡 Doubling execution time increases total by 4% in this scenario
```

## Console Output Interpretation

```
FULL SIMULATION OUTPUT:
═══════════════════════════════════════════════════════════════
Simulation Duration: 15 minutes
Scenarios Executed: 3
Total Tasks: 1,000

PERFORMANCE METRICS:
Average Service Time: 250 ms
Average Processing Time: 100 ms
Average Network Delay: 150 ms

💰 COST ANALYSIS:
average cost: 2.543210$ (bw: 2.100000$, cpu: 0.443210$)
                ↑              ↑              ↑
           Total Cost    Bandwidth Cost   CPU Cost

BREAKDOWN:
• Total Cost: $2.54 per task
• Bandwidth: $2.10/task (83% of total)
• CPU: $0.44/task (17% of total)

📊 INTERPRETATION:
Communication-heavy workload. Data transfer is the bottleneck.
Optimization opportunities:
1. Implement compression (could save ~15-20%)
2. Optimize network paths
3. Reduce task input data if possible
═══════════════════════════════════════════════════════════════
```

## Cost Comparison Chart

### Comparing Two Orchestration Policies

```
POLICY A: Local Processing (Edge-First)
┌──────────────────────────────────────┐
│ Average Task Cost: $1.50             │
│ BW Cost:   $0.80 ░░░░░░░░░░ 53%     │
│ CPU Cost:  $0.70 ▓▓▓▓▓▓▓░░░░ 47%     │
│ Efficiency: 95%                      │
│ Latency: 200 ms                      │
└──────────────────────────────────────┘

vs

POLICY B: Cloud Processing (Cloud-First)
┌──────────────────────────────────────┐
│ Average Task Cost: $2.00             │
│ BW Cost:   $0.80 ░░░░░░░░░░ 40%     │
│ CPU Cost:  $1.20 ▓▓▓▓▓▓▓▓▓░ 60%     │
│ Efficiency: 92%                      │
│ Latency: 350 ms                      │
└──────────────────────────────────────┘

COMPARISON:
Policy A is 25% cheaper ($1.50 vs $2.00)
Policy A has better latency (200 ms vs 350 ms)
Policy A has better efficiency (95% vs 92%)

RECOMMENDATION: Use Policy A ✓
```

## Per-Application Cost Breakdown

### Example: Multi-Application Simulation

```
Streaming Video Processing:
│ Tasks: 100 │ Success Rate: 98%
├─ Average Cost: $5.00 (bw: $4.50, cpu: $0.50)
├─ Total Cost: $490
└─ Cost Profile: HIGHLY COMMUNICATION-INTENSIVE (90% BW)

Web Service Hosting:
│ Tasks: 200 │ Success Rate: 99%
├─ Average Cost: $0.50 (bw: $0.10, cpu: $0.40)
├─ Total Cost: $99
└─ Cost Profile: CPU-INTENSIVE (80% CPU)

Data Analytics:
│ Tasks: 150 │ Success Rate: 95%
├─ Average Cost: $2.00 (bw: $1.50, cpu: $0.50)
├─ Total Cost: $295
└─ Cost Profile: BALANCED (75% BW, 25% CPU)

OVERALL:
│ Total Tasks: 450 │ Average Success: 97%
├─ Total Cost: $884
├─ Average Cost Per Task: $1.96 (bw: $1.64, cpu: $0.32)
└─ Most Expensive: Streaming Video (57% of total cost)

OPTIMIZATION TARGETS:
1. Focus on streaming video data compression
2. Implement progressive download for video
3. Cache frequently accessed data
```

## Real-World Example Calculation

### Video Encoding Task

```
TASK PARAMETERS:
├─ Input: 1 Hour 4K Video File
│  ├─ File Size: 50 GB (uncompressed raw)
│  └─ Actual Transfer: 5 GB (pre-compressed for transfer)
│
├─ Output: Encoded Video
│  ├─ Multiple Bitrates: 1Mbps to 10Mbps
│  └─ Total Output: 2 GB
│
├─ Execution: Edge Server (Video Processing Node)
│  ├─ Time: 15 minutes = 900 seconds
│  └─ CPU Rate: $0.02/second
│
└─ Network: 4G LTE
   └─ Rate: $0.00001/byte (standard)

CALCULATION:
┌────────────────────────────────────────┐
│ Total Data Transfer                    │
│ = Input + Output + Metadata            │
│ = 5 GB + 2 GB + 0.1 GB                │
│ = 7.1 GB                              │
│ = 7,617,636,352 bytes                 │
│                                        │
│ Bandwidth Cost                         │
│ = 7,617,636,352 × $0.00001             │
│ = $76.18 (92%)                         │
│                                        │
│ CPU Cost (Edge)                        │
│ = 900 seconds × $0.02/second           │
│ = $18.00 (8%)                          │
│                                        │
│ TOTAL COST: $94.18 per video          │
│                                        │
│ Per-minute Cost: $6.28                │
│ Per-GB Cost: $13.27                    │
└────────────────────────────────────────┘

COMPARISON:
If on Cloud (CPU: $0.05/sec):
├─ BW Cost: $76.18 (91%)
├─ CPU Cost: $45.00 (9%)
└─ Total: $121.18 (28% more expensive!)

OPTIMIZATION:
Current edge solution: $94.18
With 30% data compression: $65.73 (30% savings!)
Switch to cloud with parallelization: Depends on speedup
```

## Cost-Based Performance Tuning

```
START HERE:
┌─ Identify Cost Bottleneck
│  ├─ Run simulation: "average cost: X$ (bw: Y$, cpu: Z$)"
│  └─ Check percentage of bw vs cpu
│
├─ IF Bandwidth > 70% of Cost:
│  ├─ ✓ Implement Compression
│  ├─ ✓ Use Content Caching
│  ├─ ✓ Reduce Task I/O
│  └─ ✓ Optimize Network Paths
│
├─ IF CPU > 70% of Cost:
│  ├─ ✓ Prefer Edge over Cloud
│  ├─ ✓ Offload to more devices
│  ├─ ✓ Optimize Algorithms
│  └─ ✓ Enable Hardware Acceleration
│
├─ IF 40-60% Split (Balanced):
│  ├─ ✓ Optimize Both Aspects
│  ├─ ✓ Consider Trade-offs
│  └─ ✓ Profile for Bottleneck
│
└─ RE-RUN SIMULATION
   └─ Measure Improvement %
      └─ If satisfied, stop
         └─ Else, repeat process
```

## Files and Locations

This visual guide complements:
- [COST_CALCULATION_BREAKDOWN.md](/doc/COST_CALCULATION_BREAKDOWN.md) - Textual explanation
- [COST_QUICK_REFERENCE.md](/doc/COST_QUICK_REFERENCE.md) - Quick lookup
- [COST_IMPLEMENTATION_GUIDE.md](/doc/COST_IMPLEMENTATION_GUIDE.md) - Code details

For code implementation details, see those documents.

## Interactive Calculation

### Try it Yourself Template

```
TASK TO ANALYZE:
├─ Input Size: _____ MB
├─ Output Size: _____ MB
├─ Execution Time: _____ seconds
├─ Execution Location: ☐ Edge ☐ Cloud ☐ Mobile
└─ Bandwidth Rate: $0.00001/byte (default)

CALCULATION:
├─ Total Data = (Input + Output) MB × 1,048,576 bytes/MB
│  = _____ bytes
│
├─ Bandwidth Cost = Total Data × $0.00001
│  = $_____ 
│
├─ CPU Cost = Execution Time × Rate
│  Rate = ☐ $0.02 (Edge) ☐ $0.05 (Cloud) ☐ $0.00 (Mobile)
│  = _____ seconds × $_____ = $_____
│
└─ Total Cost = Bandwidth Cost + CPU Cost
   = $_____ + $_____ = $_____

ANALYSIS:
├─ Bandwidth percentage: ____ %
├─ CPU percentage: ____ %
└─ Optimization focus: ☐ Data Transfer ☐ Computation ☐ Both
```

---

For more details, see the comprehensive documentation files in `/doc/`
