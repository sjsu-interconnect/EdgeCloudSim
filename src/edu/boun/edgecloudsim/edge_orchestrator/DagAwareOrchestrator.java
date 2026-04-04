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
            List<org.cloudbus.cloudsim.Datacenter> clouds =
                    SimManager.getInstance().getCloudServerManager().getDatacenterList();
            if (clouds != null && !clouds.isEmpty()) {
                int idx = Math.max(0, Math.min(decision.destDatacenterId, clouds.size() - 1));
                return clouds.get(idx).getId();
            }
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
                ClusterState.VMInfo info = state.vms[PlacementDecision.TIER_EDGE][dc][vmIdx];
                info.queuedTaskCount = evm.getCloudletScheduler().getCloudletExecList().size();
                double util = evm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
                util = Math.max(0.0, Math.min(1.0, util));
                double ramMb = evm.getRam();
                info.freeMemoryMb = Math.max(0.0, ramMb * (1.0 - util));
                // Approximate GPU memory capacity using RAM until GPU model is explicit.
                info.freeGpuMemoryMb = Math.max(0.0, ramMb * (1.0 - util));
            }
        }

        // Cloud Tier
        List<org.cloudbus.cloudsim.Datacenter> clouds =
                SimManager.getInstance().getCloudServerManager().getDatacenterList();
        int numCloudDcs = clouds != null ? clouds.size() : 0;
        state.vms[PlacementDecision.TIER_CLOUD] = new ClusterState.VMInfo[numCloudDcs][];
        for (int dc = 0; dc < numCloudDcs; dc++) {
            List<CloudVM> cloudVms = SimManager.getInstance().getCloudServerManager().getVmList(dc);
            state.vms[PlacementDecision.TIER_CLOUD][dc] = new ClusterState.VMInfo[cloudVms.size()];
            for (int vmIdx = 0; vmIdx < cloudVms.size(); vmIdx++) {
                CloudVM cvm = cloudVms.get(vmIdx);
                state.vms[PlacementDecision.TIER_CLOUD][dc][vmIdx] = new ClusterState.VMInfo(
                        cvm.getId(), dc, PlacementDecision.TIER_CLOUD, cvm.getMips());
                ClusterState.VMInfo info = state.vms[PlacementDecision.TIER_CLOUD][dc][vmIdx];
                info.queuedTaskCount = cvm.getCloudletScheduler().getCloudletExecList().size();
                double util = cvm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
                util = Math.max(0.0, Math.min(1.0, util));
                double ramMb = cvm.getRam();
                info.freeMemoryMb = Math.max(0.0, ramMb * (1.0 - util));
                info.freeGpuMemoryMb = Math.max(0.0, ramMb * (1.0 - util));
            }
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
