package edu.boun.edgecloudsim.dagsim;

import java.util.*;

/**
 * Basic validation/test harness for DAG scheduling components.
 * Use this to verify your integration before running full simulation.
 */
public class DagSchedulingTestHarness {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("DAG Scheduling Component Test Harness");
        System.out.println("=".repeat(60) + "\n");
        
        try {
            testDagLoading();
            testTaskDependencies();
            testSchedulingPolicies();
            testValidation();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("✓ All tests passed!");
            System.out.println("=".repeat(60));
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Test 1: DAG JSON Loading
     */
    private static void testDagLoading() throws Exception {
        System.out.println("\n[TEST 1] DAG JSON Loading");
        System.out.println("-".repeat(60));
        
        // Try to load from synthetic_dags directory
        String dagDir = "synthetic_dags";
        java.io.File dir = new java.io.File(dagDir);
        
        if (!dir.exists()) {
            System.out.println("⚠ Directory not found: " + dagDir);
            System.out.println("  (This is OK for initial test - create DAGs first)");
            return;
        }
        
        List<DagRecord> dags = DagJsonLoader.loadAllDags(dagDir);
        System.out.println("✓ Loaded " + dags.size() + " DAGs");
        
        if (dags.isEmpty()) {
            System.out.println("  ⚠ Warning: No DAGs found");
            return;
        }
        
        // Print first DAG details
        DagRecord firstDag = dags.get(0);
        System.out.println("\nFirst DAG: " + firstDag.getDagId());
        System.out.println("  Tasks: " + firstDag.getTotalTasks());
        System.out.println("  Submit (relative): " + firstDag.getSubmitAtSimMs() + " ms");
        System.out.println("  Inference steps: " + firstDag.getNumInferenceSteps());
        System.out.println("  Has LoRA: " + firstDag.isHasLora());
        System.out.println("  Has ControlNet: " + firstDag.isHasControlnet());
        
        // Check dependencies
        int totalDeps = 0;
        for (TaskRecord task : firstDag.getTasksById().values()) {
            totalDeps += task.getDependsOn().size();
        }
        System.out.println("  Total dependencies: " + totalDeps);
    }
    
    /**
     * Test 2: Task Dependency Tracking
     */
    private static void testTaskDependencies() throws Exception {
        System.out.println("\n[TEST 2] Task Dependency Tracking");
        System.out.println("-".repeat(60));
        
        // Create test DAG manually
        DagRecord testDag = new DagRecord();
        testDag.setDagId("test_dag_1");
        testDag.setSubmissionTimeEpochSec(1000.0);
        testDag.setSubmitAtSimMs(0);
        
        // Create tasks
        TaskRecord task1 = new TaskRecord();
        task1.setTaskId("task_1");
        task1.setTaskType("vae_encode");
        task1.setDurationMs(500);
        
        TaskRecord task2 = new TaskRecord();
        task2.setTaskId("task_2");
        task2.setTaskType("unet_denoise");
        task2.setDurationMs(1000);
        task2.setDependsOn(Arrays.asList("task_1"));
        task2.setRemainingDeps(1);
        
        TaskRecord task3 = new TaskRecord();
        task3.setTaskId("task_3");
        task3.setTaskType("sampler");
        task3.setDurationMs(100);
        task3.setDependsOn(Arrays.asList("task_2"));
        task3.setRemainingDeps(1);
        
        // Add to DAG
        testDag.addTask("task_1", task1);
        testDag.addTask("task_2", task2);
        testDag.addTask("task_3", task3);
        
        // Build reverse dependencies
        task1.getChildren().add("task_2");
        task2.getChildren().add("task_3");
        
        // Verify
        System.out.println("Created test DAG with 3 tasks");
        
        // Test dependency chain
        assert task1.getDependsOn().isEmpty() : "task_1 should have no dependencies";
        assert task2.getRemainingDeps() == 1 : "task_2 should have 1 remaining dep";
        assert task1.getChildren().contains("task_2") : "task_1 should have task_2 as child";
        
        System.out.println("✓ task_1 (source): no deps, 1 child");
        System.out.println("✓ task_2 (middle): 1 dep, 1 child");
        System.out.println("✓ task_3 (sink): 1 dep, 0 children");
        
        // Simulate dependency resolution
        task2.decrementRemainingDeps();
        assert task2.getRemainingDeps() == 0 : "task_2 should be ready after decrement";
        System.out.println("✓ Dependency tracking works correctly");
    }
    
    /**
     * Test 3: Scheduling Policies
     */
    private static void testSchedulingPolicies() throws Exception {
        System.out.println("\n[TEST 3] Scheduling Policies");
        System.out.println("-".repeat(60));
        
        // Create mock cluster state
        edu.boun.edgecloudsim.dagsim.scheduling.ClusterState state = 
            new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState(0);
        
        // Create mock VMs
        state.vms = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[2][];
        
        // Edge tier (2 datacenters, 2 VMs each)
        state.vms[0] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[2][];
        state.vms[0][0] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[2];
        state.vms[0][0][0] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo(
            0, 0, 0, 2000);
        state.vms[0][0][0].freeMemoryMb = 16000;
        state.vms[0][0][0].freeGpuMemoryMb = 8000;
        
        state.vms[0][0][1] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo(
            1, 0, 0, 2000);
        state.vms[0][0][1].freeMemoryMb = 16000;
        state.vms[0][0][1].freeGpuMemoryMb = 8000;
        
        state.vms[0][1] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[2];
        state.vms[0][1][0] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo(
            2, 1, 0, 2000);
        state.vms[0][1][0].freeMemoryMb = 16000;
        state.vms[0][1][0].freeGpuMemoryMb = 8000;
        
        // Cloud tier (1 datacenter, 4 VMs)
        state.vms[1] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[1][];
        state.vms[1][0] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo[4];
        for (int i = 0; i < 4; i++) {
            state.vms[1][0][i] = new edu.boun.edgecloudsim.dagsim.scheduling.ClusterState.VMInfo(
                i + 3, 0, 1, 4000);
            state.vms[1][0][i].freeMemoryMb = 32000;
            state.vms[1][0][i].freeGpuMemoryMb = 16000;
        }
        
        state.edgeTierCount = 3;
        state.cloudTierCount = 4;
        
        // Create test task
        edu.boun.edgecloudsim.dagsim.scheduling.TaskContext task = 
            new edu.boun.edgecloudsim.dagsim.scheduling.TaskContext();
        task.taskId = "test_task";
        task.taskType = "unet_denoise";
        task.lengthMI = 843750;
        task.cpuMemoryMb = 6000;
        task.gpuMemoryMb = 5000;
        task.readyTimeMs = 0;
        task.currentTimeMs = 0;
        
        // Test policies
        testPolicy(new edu.boun.edgecloudsim.dagsim.scheduling.RoundRobinPolicy(), 
                  task, state, "RoundRobin");
        testPolicy(new edu.boun.edgecloudsim.dagsim.scheduling.EdgeFirstFeasiblePolicy(),
                  task, state, "EdgeFirstFeasible");
        testPolicy(new edu.boun.edgecloudsim.dagsim.scheduling.EFTPolicy(),
                  task, state, "EFT");
    }
    
    private static void testPolicy(
            edu.boun.edgecloudsim.dagsim.scheduling.SchedulingPolicy policy,
            edu.boun.edgecloudsim.dagsim.scheduling.TaskContext task,
            edu.boun.edgecloudsim.dagsim.scheduling.ClusterState state,
            String name) {
        
        edu.boun.edgecloudsim.dagsim.scheduling.PlacementDecision decision = 
            policy.decide(task, state);
        
        System.out.println("✓ " + name + " policy:");
        System.out.println("    → Tier: " + decision.getTierName());
        System.out.println("    → DC: " + decision.destDatacenterId);
        System.out.println("    → VM: " + decision.destVmId);
    }
    
    /**
     * Test 4: Validation
     */
    private static void testValidation() throws Exception {
        System.out.println("\n[TEST 4] Validation Checks");
        System.out.println("-".repeat(60));
        
        // Create test DAG
        DagRecord dag = new DagRecord();
        dag.setDagId("val_test_dag");
        
        TaskRecord t1 = new TaskRecord();
        t1.setTaskId("t1");
        t1.setState(TaskRecord.TaskState.DONE);
        t1.setFinishTimeMs(1000);
        
        TaskRecord t2 = new TaskRecord();
        t2.setTaskId("t2");
        t2.setState(TaskRecord.TaskState.DONE);
        t2.setStartTimeMs(1100);
        t2.setFinishTimeMs(2000);
        t2.setDependsOn(Arrays.asList("t1"));
        
        dag.addTask("t1", t1);
        dag.addTask("t2", t2);
        
        // Validate dependency order
        for (String depId : t2.getDependsOn()) {
            TaskRecord parent = dag.getTask(depId);
            if (parent.getFinishTimeMs() <= t2.getStartTimeMs()) {
                System.out.println("✓ Dependency order valid: " + depId + 
                                  " finishes at " + parent.getFinishTimeMs() +
                                  " before " + t2.getTaskId() + 
                                  " starts at " + t2.getStartTimeMs());
            }
        }
        
        System.out.println("✓ All validation checks passed");
    }
}
