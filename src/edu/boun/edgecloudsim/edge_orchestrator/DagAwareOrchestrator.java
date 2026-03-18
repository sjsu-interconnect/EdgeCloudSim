package edu.boun.edgecloudsim.edge_orchestrator;

import org.cloudbus.cloudsim.Vm;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.dagsim.scheduling.*;
import edu.boun.edgecloudsim.cloud_server.CloudVM;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.List;

/**
 * An EdgeOrchestrator that delegates decisions to a pluggable SchedulingPolicy.
 * This allows using the new DAG-sim policies (EFT, RL, etc.) within the
 * standard EdgeCloudSim execution flow.
 */
public class DagAwareOrchestrator extends EdgeOrchestrator {
    private final SchedulingPolicy schedulingPolicy;

    public DagAwareOrchestrator(String policyName, String simScenario, SchedulingPolicy schedulingPolicy) {
        super(policyName, simScenario);
        this.schedulingPolicy = schedulingPolicy;
    }

    @Override
    public void initialize() {
        // Initialization if needed
    }

    @Override
    public int getDeviceToOffload(Task task) {
        PlacementDecision decision = getPolicyDecision(task);
        if (decision.destTier == PlacementDecision.TIER_CLOUD) {
            return SimSettings.CLOUD_DATACENTER_ID;
        } else {
            return SimSettings.GENERIC_EDGE_DEVICE_ID;
        }
    }

    @Override
    public Vm getVmToOffload(Task task, int deviceId) {
        PlacementDecision decision = getPolicyDecision(task);

        if (decision.destTier == PlacementDecision.TIER_CLOUD) {
            // Retrieve cloud VM
            List<CloudVM> vms = SimManager.getInstance().getCloudServerManager().getVmList(decision.destDatacenterId);
            for (CloudVM vm : vms) {
                if (vm.getId() == decision.destVmId) {
                    return vm;
                }
            }
            if (decision.destVmId >= 0 && decision.destVmId < vms.size()) { // backward-compatible index fallback
                return vms.get(decision.destVmId);
            }
        } else {
            // Retrieve edge VM
            List<EdgeVM> vms = SimManager.getInstance().getEdgeServerManager().getVmList(decision.destDatacenterId);
            for (EdgeVM vm : vms) {
                if (vm.getId() == decision.destVmId) {
                    return vm;
                }
            }
            if (decision.destVmId >= 0 && decision.destVmId < vms.size()) { // backward-compatible index fallback
                return vms.get(decision.destVmId);
            }
        }
        return null;
    }

    private PlacementDecision getPolicyDecision(Task task) {
        // Convert Task to TaskContext
        TaskContext context = new TaskContext();
        context.taskId = (task.getDagTaskId() != null) ? task.getDagTaskId() : String.valueOf(task.getCloudletId());
        context.dagId = task.getDagId();
        context.taskType = SimSettings.getInstance().getTaskName(task.getTaskType());
        context.lengthMI = task.getCloudletLength();
        context.cpuMemoryMb = SimSettings.getInstance().getRamForMobileVM(); // Approximation
        context.gpuMemoryMb = 0; // Default
        context.gpuUtilizationPercent = 0;
        context.readyTimeMs = task.getSubmissionTime() * 1000.0;
        context.currentTimeMs = CloudSim.clock() * 1000.0;

        // Build Cluster State snippet
        ClusterState state = buildClusterStateSnapshot();

        return schedulingPolicy.decide(context, state);
    }

    public static ClusterState buildClusterStateSnapshot() {
        ClusterState state = new ClusterState(CloudSim.clock() * 1000.0);

        // Populate VMs based on EdgeServerManager and CloudServerManager
        int numEdgeDcs = SimSettings.getInstance().getNumOfEdgeDatacenters();
        state.vms = new ClusterState.VMInfo[2][][];

        // Edge Tier
        state.vms[PlacementDecision.TIER_EDGE] = new ClusterState.VMInfo[numEdgeDcs][];
        for (int dc = 0; dc < numEdgeDcs; dc++) {
            List<EdgeVM> edgeVms = SimManager.getInstance().getEdgeServerManager().getVmList(dc);
            state.vms[PlacementDecision.TIER_EDGE][dc] = new ClusterState.VMInfo[edgeVms.size()];
            for (int vmIdx = 0; vmIdx < edgeVms.size(); vmIdx++) {
                EdgeVM evm = edgeVms.get(vmIdx);
                state.vms[PlacementDecision.TIER_EDGE][dc][vmIdx] = new ClusterState.VMInfo(
                        evm.getId(), dc, PlacementDecision.TIER_EDGE, evm.getMips());
                state.vms[PlacementDecision.TIER_EDGE][dc][vmIdx].queuedTaskCount = evm.getCloudletScheduler()
                        .getCloudletExecList().size();
            }
        }

        // Cloud Tier
        state.vms[PlacementDecision.TIER_CLOUD] = new ClusterState.VMInfo[1][]; // Cloud is usually one DC in
                                                                                // EdgeCloudSim
        List<CloudVM> cloudVms = SimManager.getInstance().getCloudServerManager().getVmList(0);
        state.vms[PlacementDecision.TIER_CLOUD][0] = new ClusterState.VMInfo[cloudVms.size()];
        for (int vmIdx = 0; vmIdx < cloudVms.size(); vmIdx++) {
            CloudVM cvm = cloudVms.get(vmIdx);
            state.vms[PlacementDecision.TIER_CLOUD][0][vmIdx] = new ClusterState.VMInfo(
                    cvm.getId(), 0, PlacementDecision.TIER_CLOUD, cvm.getMips());
            state.vms[PlacementDecision.TIER_CLOUD][0][vmIdx].queuedTaskCount = cvm.getCloudletScheduler()
                    .getCloudletExecList().size();
        }

        return state;
    }

    @Override
    public void processEvent(org.cloudbus.cloudsim.core.SimEvent arg0) {
    }

    @Override
    public void shutdownEntity() {
    }

    @Override
    public void startEntity() {
    }
}
