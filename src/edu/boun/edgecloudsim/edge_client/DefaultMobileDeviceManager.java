/*
 * Title:        EdgeCloudSim - Mobile Device Manager
 * 
 * Description: 
 * DefaultMobileDeviceManager is responsible for submitting the tasks to the related
 * device by using the Edge Orchestrator. It also takes proper actions 
 * when the execution of the tasks are finished.
 * By default, DefaultMobileDeviceManager sends tasks to the edge servers or
 * cloud servers. If you want to use different topology, for example
 * MAN edge server, you should modify the flow defined in this class.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.edge_client;

import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.core.SimSettings.NETWORK_DELAY_TYPES;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.TaskProperty;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.dagsim.DagRuntimeManager;

/**
 * Default implementation of MobileDeviceManager for standard edge computing
 * scenarios.
 * Handles task submission, orchestration, and network communication for mobile
 * devices
 * in edge-cloud computing environments with support for edge servers and cloud
 * servers.
 */
public class DefaultMobileDeviceManager extends MobileDeviceManager {
	// Custom event tags to avoid conflicts with CloudSim's internal tags
	private static final int BASE = 100000; // Base value for custom event tags
	private static final int REQUEST_RECEIVED_BY_CLOUD = BASE + 1;
	private static final int REQUEST_RECEIVED_BY_EDGE_DEVICE = BASE + 2;
	private static final int RESPONSE_RECEIVED_BY_MOBILE_DEVICE = BASE + 3;
	private int taskIdCounter = 0; // Counter for generating unique task IDs

	/**
	 * Constructor for default mobile device manager.
	 * 
	 * @throws Exception if manager initialization fails
	 */
	public DefaultMobileDeviceManager() throws Exception {
	}

	/**
	 * Initializes the mobile device manager.
	 * No special initialization required for default implementation.
	 */
	@Override
	public void initialize() {
	}

	/**
	 * Creates and returns the CPU utilization model for tasks.
	 * Uses custom utilization model with application-specific parameters.
	 * 
	 * @return CpuUtilizationModel_Custom for realistic resource modeling
	 */
	@Override
	public UtilizationModel getCpuUtilizationModel() {
		return new CpuUtilizationModel_Custom();
	}

	/**
	 * Submit cloudlets to the created VMs.
	 * Overridden from parent class but not used in EdgeCloudSim's task submission
	 * model.
	 * Tasks are submitted via submitTask() method instead.
	 */
	protected void submitCloudlets() {
		// Not used in EdgeCloudSim - tasks submitted via submitTask() method
	}

	/**
	 * Processes a cloudlet return event when task execution completes.
	 * Handles network delays for task result delivery back to mobile device.
	 * 
	 * @param ev SimEvent containing the completed task
	 */
	protected void processCloudletReturn(SimEvent ev) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		Task task = (Task) ev.getData();

		// Log task execution completion
		SimLogger.getInstance().taskExecuted(task.getCloudletId());

		if (task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID) {
			// Task completed on cloud - calculate WAN download delay for result delivery
			double WanDelay = networkModel.getDownloadDelay(SimSettings.CLOUD_DATACENTER_ID, task.getMobileDeviceId(),
					task);
			if (WanDelay > 0) {
				Location currentLocation = SimManager.getInstance().getMobilityModel()
						.getLocation(task.getMobileDeviceId(), CloudSim.clock() + WanDelay);
				if (task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId()) {
					networkModel.downloadStarted(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);
					SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), WanDelay,
							NETWORK_DELAY_TYPES.WAN_DELAY);
					schedule(getId(), WanDelay, RESPONSE_RECEIVED_BY_MOBILE_DEVICE, task);
				} else {
					SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
				}
			} else {
				SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(),
						NETWORK_DELAY_TYPES.WAN_DELAY);
			}
		} else {
			// Task completed on edge server - calculate WLAN download delay for result
			// delivery
			double WlanDelay = networkModel.getDownloadDelay(task.getAssociatedHostId(), task.getMobileDeviceId(),
					task);
			if (WlanDelay > 0) {
				Location currentLocation = SimManager.getInstance().getMobilityModel()
						.getLocation(task.getMobileDeviceId(), CloudSim.clock() + WlanDelay);
				if (task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId()) {
					networkModel.downloadStarted(currentLocation, SimSettings.GENERIC_EDGE_DEVICE_ID);
					SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), WlanDelay,
							NETWORK_DELAY_TYPES.WLAN_DELAY);
					schedule(getId(), WlanDelay, RESPONSE_RECEIVED_BY_MOBILE_DEVICE, task);
				} else {
					SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
				}
			} else {
				SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(),
						NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
		}
	}

	/**
	 * Processes custom events specific to EdgeCloudSim's mobile device operations.
	 * Handles task upload completion and result delivery events.
	 * 
	 * @param ev The simulation event to process
	 */
	protected void processOtherEvent(SimEvent ev) {
		if (ev == null) {
			SimLogger.printLine(
					getName() + ".processOtherEvent(): " + "Error - an event is null! Terminating simulation...");
			System.exit(1);
			return;
		}

		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();

		switch (ev.getTag()) {
			case REQUEST_RECEIVED_BY_CLOUD: {
				Task task = (Task) ev.getData();

				// Mark upload as completed to cloud datacenter
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);

				// Submit task to appropriate cloud VM
				submitTaskToVm(task, 0, SimSettings.CLOUD_DATACENTER_ID);

				break;
			}
			case REQUEST_RECEIVED_BY_EDGE_DEVICE: {
				Task task = (Task) ev.getData();

				// Mark upload as completed to edge server
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);

				// Submit task to appropriate edge VM
				submitTaskToVm(task, 0, SimSettings.GENERIC_EDGE_DEVICE_ID);

				break;
			}
			case RESPONSE_RECEIVED_BY_MOBILE_DEVICE: {
				Task task = (Task) ev.getData();

				// Mark download as finished based on datacenter type
				if (task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);
				else if (task.getAssociatedDatacenterId() != SimSettings.MOBILE_DATACENTER_ID)
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);

				// Calculate cost metrics before logging task completion
				double taskServiceTime = CloudSim.clock() - task.getSubmissionTime();

				// Map associated DC ID to actual entity ID if it's a symbolic placeholder
				int datacenterId = task.getAssociatedDatacenterId();
				if (datacenterId == SimSettings.CLOUD_DATACENTER_ID) {
					datacenterId = SimManager.getInstance().getCloudServerManager().getDatacenter().getId();
				} else if (datacenterId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
					// For generic edge, we pick the cost from the first edge datacenter as a proxy
					datacenterId = SimManager.getInstance().getEdgeServerManager().getDatacenterList().get(0).getId();
				}

				// Extract cost parameters from the centralized registry in SimSettings
				Double[] costs = SimSettings.datacenterCosts.get(datacenterId);

				// Fallback to sensible default values if datacenter is still not registered
				double costPerBw = (costs != null) ? costs[0] : 0.00000000009; // Default cloud egress rate
				double costPerSec = (costs != null) ? costs[1] : 0.0002083333; // Default cloud compute rate
				double costPerMem = (costs != null) ? costs[2] : 0.0;
				double costPerStorage = (costs != null) ? costs[3] : 0.0;

				// Bandwidth cost: (upload + download) * cost_per_byte
				double totalBytes = task.getCloudletFileSize() + task.getCloudletOutputSize();
				double bwCost = totalBytes * costPerBw;

				// CPU cost: actual_execution_time * cost_per_second
				double cpuCost = Math.max(0, task.getActualCPUTime() * costPerSec);

				// Optional: Add Memory and Storage costs
				// Memory cost based on the task type's standard RAM allocation
				double vmRam = (task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
						? SimSettings.getInstance().getRamForCloudVM()
						: SimSettings.getInstance().getRamForMobileVM(); // Default for edge/mobile

				cpuCost += (vmRam * costPerMem); // Memory cost component
				cpuCost += (task.getCloudletOutputSize() * costPerStorage / 1024.0); // Storage cost

				// Set cost in SimLogger (before taskEnded to include in aggregation)
				SimLogger.getInstance().setCost(task.getCloudletId(), bwCost, cpuCost);

				// Calculate QoE (Quality of Experience)
				double maxDelayRequirement = 10.0; // Default 10 seconds
				double delaySensitivity = 0.5; // Default 50% sensitivity to delay
				double qoe = 100.0; // Start at perfect QoE

				if (maxDelayRequirement > 0 && taskServiceTime > maxDelayRequirement) {
					// Degrade QoE based on delay overage
					double delayRatio = (taskServiceTime - maxDelayRequirement) / maxDelayRequirement;
					qoe = 100.0 * Math.max(0, 1.0 - (delayRatio * delaySensitivity));
				}
				SimLogger.getInstance().setQoE(task.getCloudletId(), qoe);

				// Log task completion (this aggregates all metrics including cost and QoE)
				SimLogger.getInstance().taskEnded(task.getCloudletId(), CloudSim.clock());

				// Notify DAG runtime manager (if present) that this cloudlet finished
				if (DagRuntimeManager.getInstance() != null) {
					DagRuntimeManager.getInstance().onTaskCloudletFinished(task.getCloudletId(), CloudSim.clock());
				}
				break;
			}
			default:
				SimLogger.printLine(getName() + ".processOtherEvent(): "
						+ "Error - event unknown by this DatacenterBroker. Terminating simulation...");
				System.exit(1);
				break;
		}
	}

	/**
	 * Submits a task from a mobile device for processing.
	 * Handles orchestration decisions and network delay simulation for task
	 * offloading.
	 * 
	 * @param edgeTask Task properties including requirements and mobile device
	 *                 context
	 */
	public void submitTask(TaskProperty edgeTask) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();

		// Create EdgeCloudSim task from task properties
		Task task = createTask(edgeTask);

		// If this TaskProperty originated from a DAG, register mapping and set fields
		if (edgeTask.getDagId() != null && edgeTask.getDagTaskId() != null) {
			task.setDagId(edgeTask.getDagId());
			task.setDagTaskId(edgeTask.getDagTaskId());
			if (DagRuntimeManager.getInstance() != null) {
				DagRuntimeManager.getInstance().registerCloudletMapping(task.getCloudletId(), edgeTask.getDagId(),
						edgeTask.getDagTaskId());
			}
		}

		// Get current location of the mobile device
		Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(),
				CloudSim.clock());

		// Set the location where this task was submitted
		task.setSubmittedLocation(currentLocation);

		// Add task to simulation logging system
		SimLogger.getInstance().addLog(task.getMobileDeviceId(),
				task.getCloudletId(),
				task.getTaskType(),
				(int) task.getCloudletLength(),
				(int) task.getCloudletFileSize(),
				(int) task.getCloudletOutputSize());

		// Use edge orchestrator to decide where to process this task
		int nextHopId = SimManager.getInstance().getEdgeOrchestrator().getDeviceToOffload(task);

		// Handle task submission based on orchestrator decision
		if (nextHopId == SimSettings.CLOUD_DATACENTER_ID) {
			// Task assigned to cloud - calculate WAN upload delay
			double WanDelay = networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task);

			if (WanDelay > 0) {
				// Start network upload and schedule task arrival after delay
				networkModel.uploadStarted(currentLocation, nextHopId);
				SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
				SimLogger.getInstance().setUploadDelay(task.getCloudletId(), WanDelay, NETWORK_DELAY_TYPES.WAN_DELAY);
				schedule(getId(), WanDelay, REQUEST_RECEIVED_BY_CLOUD, task);
			} else {
				// WAN bandwidth not available - reject task
				SimLogger.getInstance().rejectedDueToBandwidth(
						task.getCloudletId(),
						CloudSim.clock(),
						SimSettings.VM_TYPES.CLOUD_VM.ordinal(),
						NETWORK_DELAY_TYPES.WAN_DELAY);
			}
		} else if (nextHopId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
			// Task assigned to edge server - calculate WLAN upload delay
			double WlanDelay = networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task);

			if (WlanDelay > 0) {
				// Start network upload and schedule task arrival after delay
				networkModel.uploadStarted(currentLocation, nextHopId);
				schedule(getId(), WlanDelay, REQUEST_RECEIVED_BY_EDGE_DEVICE, task);
				SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
				SimLogger.getInstance().setUploadDelay(task.getCloudletId(), WlanDelay, NETWORK_DELAY_TYPES.WLAN_DELAY);
			} else {
				// WLAN bandwidth not available - reject task
				SimLogger.getInstance().rejectedDueToBandwidth(
						task.getCloudletId(),
						CloudSim.clock(),
						SimSettings.VM_TYPES.EDGE_VM.ordinal(),
						NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
		} else {
			// Unknown orchestrator decision - terminate simulation
			SimLogger.printLine("Unknown nextHopId! Terminating simulation...");
			System.exit(1);
		}
	}

	/**
	 * Submits a task to a specific VM in the designated datacenter.
	 * Handles VM selection, resource assignment, and task binding.
	 * 
	 * @param task         The task to be submitted
	 * @param delay        Additional delay before task execution
	 * @param datacenterId The target datacenter ID (cloud or edge)
	 */
	private void submitTaskToVm(Task task, double delay, int datacenterId) {
		// Use orchestrator to select appropriate VM for this task
		Vm selectedVM = SimManager.getInstance().getEdgeOrchestrator().getVmToOffload(task, datacenterId);

		// Determine VM type for logging purposes
		int vmType = 0;
		if (datacenterId == SimSettings.CLOUD_DATACENTER_ID)
			vmType = SimSettings.VM_TYPES.CLOUD_VM.ordinal();
		else
			vmType = SimSettings.VM_TYPES.EDGE_VM.ordinal();

		if (selectedVM != null) {
			// Associate task with the selected datacenter
			if (datacenterId == SimSettings.CLOUD_DATACENTER_ID)
				task.setAssociatedDatacenterId(SimSettings.CLOUD_DATACENTER_ID);
			else
				task.setAssociatedDatacenterId(selectedVM.getHost().getDatacenter().getId());

			// Save resource assignment information
			task.setAssociatedHostId(selectedVM.getHost().getId());
			task.setAssociatedVmId(selectedVM.getId());

			// Bind task to the selected VM using CloudSim mechanisms
			getCloudletList().add(task);
			bindCloudletToVm(task.getCloudletId(), selectedVM.getId());

			// SimLogger.printLine(CloudSim.clock() + ": Cloudlet#" + task.getCloudletId() +
			// " is submitted to VM#" + task.getVmId());
			schedule(getVmsToDatacentersMap().get(task.getVmId()), delay, CloudSimTags.CLOUDLET_SUBMIT, task);

			SimLogger.getInstance().taskAssigned(task.getCloudletId(),
					selectedVM.getHost().getDatacenter().getId(),
					selectedVM.getHost().getId(),
					selectedVM.getId(),
					vmType);
		} else {
			// SimLogger.printLine("Task #" + task.getCloudletId() + " cannot assign to any
			// VM");
			SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), vmType);
		}
	}

	/**
	 * Creates an EdgeCloudSim Task from TaskProperty specifications.
	 * Configures resource utilization models and assigns task metadata.
	 * 
	 * @param edgeTask Task properties defining requirements and constraints
	 * @return Configured Task object ready for execution
	 */
	private Task createTask(TaskProperty edgeTask) {
		// Use full utilization model for RAM and bandwidth (standard approach)
		UtilizationModel utilizationModel = new UtilizationModelFull();
		// Use custom CPU utilization model for realistic resource modeling
		UtilizationModel utilizationModelCPU = getCpuUtilizationModel();

		// Create EdgeCloudSim task with specified parameters
		Task task = new Task(edgeTask.getMobileDeviceId(), ++taskIdCounter,
				edgeTask.getLength(), edgeTask.getPesNumber(),
				edgeTask.getInputFileSize(), edgeTask.getOutputFileSize(),
				utilizationModelCPU, utilizationModel, utilizationModel);

		// Set task ownership and classification
		task.setUserId(this.getId());
		task.setTaskType(edgeTask.getTaskType());

		// Associate task with CPU utilization model for dynamic utilization calculation
		if (utilizationModelCPU instanceof CpuUtilizationModel_Custom) {
			((CpuUtilizationModel_Custom) utilizationModelCPU).setTask(task);
		}

		return task;
	}
}
