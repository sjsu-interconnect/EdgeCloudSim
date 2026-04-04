/*
 * Title:        EdgeCloudSim - Cloud Server Manager
 * 
 * Description: 
 * DefaultCloudServerManager is responsible for creating datacenters, hosts and VMs.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.cloud_server;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;

/**
 * Default implementation of CloudServerManager for standard cloud
 * infrastructure.
 * Manages cloud datacenters, hosts, and VMs with default EdgeCloudSim
 * configurations.
 */
public class DefaultCloudServerManager extends CloudServerManager {
	private final List<Element> cloudDatacenterElements = new ArrayList<>();
	private int cloudHostIdCounter = 0;

	/**
	 * Constructor for default cloud server manager.
	 */
	public DefaultCloudServerManager() {

	}

	/**
	 * Initializes the cloud server manager.
	 * No special initialization required for default implementation.
	 */
	@Override
	public void initialize() {
	}

	/**
	 * Creates and returns the VM allocation policy for cloud datacenters.
	 * 
	 * @param hostList        List of hosts available for VM allocation
	 * @param dataCenterIndex Index of the datacenter
	 * @return Custom VM allocation policy for cloud resources
	 */
	@Override
	public VmAllocationPolicy getVmAllocationPolicy(List<? extends Host> hostList, int dataCenterIndex) {
		return new CloudVmAllocationPolicy_Custom(hostList, dataCenterIndex);
	}

	/**
	 * Starts the cloud datacenter infrastructure.
	 * Creates and initializes the main cloud datacenter.
	 * 
	 * @throws Exception if datacenter creation fails
	 */
	public void startDatacenters() throws Exception {
		cloudDatacenterElements.clear();
		localDatacenters.clear();
		cloudHostIdCounter = SimSettings.getInstance().getNumOfEdgeHosts();

		Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
		if (doc != null) {
			NodeList datacenterList = doc.getElementsByTagName("datacenter");
			for (int i = 0; i < datacenterList.getLength(); i++) {
				Node datacenterNode = datacenterList.item(i);
				Element datacenterElement = (Element) datacenterNode;
				if (isCloudDatacenterElement(datacenterElement)) {
					cloudDatacenterElements.add(datacenterElement);
				}
			}
		}

		if (cloudDatacenterElements.isEmpty()) {
			localDatacenter = createDatacenterFromSettings(SimSettings.CLOUD_DATACENTER_ID);
			localDatacenters.add(localDatacenter);
			SimSettings.getInstance().registerCloudDatacenterId(localDatacenter.getId());
			return;
		}

		for (int i = 0; i < cloudDatacenterElements.size(); i++) {
			Datacenter dc = createDatacenterFromElement(i, cloudDatacenterElements.get(i));
			localDatacenters.add(dc);
			SimSettings.getInstance().registerCloudDatacenterId(dc.getId());
		}
		localDatacenter = localDatacenters.get(0);
	}

	/**
	 * Terminates all cloud datacenters and releases resources.
	 */
	public void terminateDatacenters() {
		if (localDatacenters != null && !localDatacenters.isEmpty()) {
			for (Datacenter dc : localDatacenters) {
				dc.shutdownEntity();
			}
			return;
		}
		if (localDatacenter != null) {
			localDatacenter.shutdownEntity();
		}
	}

	/**
	 * Creates the complete list of cloud VMs for all hosts.
	 * Ensures unique VM IDs by starting after edge VM ID range.
	 * 
	 * @param brokerId The broker ID that will manage these cloud VMs
	 */
	public void createVmList(int brokerId) {
		// VMs should have unique IDs, so create Cloud VMs after Edge VMs
		int vmCounter = SimSettings.getInstance().getNumOfEdgeVMs();
		vmList.clear();

		if (!cloudDatacenterElements.isEmpty()) {
			for (int dcIdx = 0; dcIdx < cloudDatacenterElements.size(); dcIdx++) {
				Element datacenterElement = cloudDatacenterElements.get(dcIdx);
				vmList.add(new ArrayList<CloudVM>());
				NodeList hostNodeList = datacenterElement.getElementsByTagName("host");
				for (int h = 0; h < hostNodeList.getLength(); h++) {
					Element hostElement = (Element) hostNodeList.item(h);
					NodeList vmNodeList = hostElement.getElementsByTagName("VM");
					for (int v = 0; v < vmNodeList.getLength(); v++) {
						Element vmElement = (Element) vmNodeList.item(v);
						String vmm = vmElement.getAttribute("vmm");
						int numOfCores = Integer.parseInt(vmElement.getElementsByTagName("core").item(0).getTextContent());
						double mips = Double.parseDouble(vmElement.getElementsByTagName("mips").item(0).getTextContent());
						int ram = Integer.parseInt(vmElement.getElementsByTagName("ram").item(0).getTextContent());
						long storage = Long.parseLong(vmElement.getElementsByTagName("storage").item(0).getTextContent());
						long bandwidth = 0;

						CloudVM vm = new CloudVM(vmCounter, brokerId, mips, numOfCores, ram, bandwidth, storage, vmm,
								new CloudletSchedulerTimeShared());
						vmList.get(dcIdx).add(vm);
						vmCounter++;
					}
				}
			}
			return;
		}

		// Fallback: single cloud datacenter from settings
		for (int i = 0; i < SimSettings.getInstance().getNumOfCloudHost(); i++) {
			vmList.add(i, new ArrayList<CloudVM>());
			for (int j = 0; j < SimSettings.getInstance().getNumOfCloudVMsPerHost(); j++) {
				String vmm = "Xen";
				int numOfCores = SimSettings.getInstance().getCoreForCloudVM();
				double mips = SimSettings.getInstance().getMipsForCloudVM();
				int ram = SimSettings.getInstance().getRamForCloudVM();
				long storage = SimSettings.getInstance().getStorageForCloudVM();
				long bandwidth = 0;

				CloudVM vm = new CloudVM(vmCounter, brokerId, mips, numOfCores, ram, bandwidth, storage, vmm,
						new CloudletSchedulerTimeShared());
				vmList.get(i).add(vm);
				vmCounter++;
			}
		}
	}

	/**
	 * Calculates the average CPU utilization across all cloud VMs.
	 * Iterates through all hosts and their VMs to compute overall utilization.
	 * 
	 * @return Average utilization percentage (0.0 to 1.0)
	 */
	public double getAvgUtilization() {
		double totalUtilization = 0;
		double vmCounter = 0;

		List<List<CloudVM>> vmLists = vmList;
		for (int dcIdx = 0; dcIdx < vmLists.size(); dcIdx++) {
			List<CloudVM> vmArray = vmLists.get(dcIdx);
			for (int vmIndex = 0; vmIndex < vmArray.size(); vmIndex++) {
				totalUtilization += vmArray.get(vmIndex).getCloudletScheduler()
						.getTotalUtilizationOfCpu(CloudSim.clock());
				vmCounter++;
			}
		}

		return totalUtilization / vmCounter;
	}

	/**
	 * Creates a cloud datacenter with specified configuration.
	 * Configures datacenter characteristics including hosts, policies, and costs.
	 * 
	 * @param index The datacenter index identifier
	 * @return Configured Datacenter instance
	 * @throws Exception if datacenter creation fails
	 */
	private Datacenter createDatacenterFromSettings(int index) throws Exception {
		String arch = "x86";
		String os = "Linux";
		String vmm = "Xen";
		SimSettings ss = SimSettings.getInstance();
		double costPerBw = ss.getCloudCostPerBw();
		double costPerSec = ss.getCloudCostPerSec();
		double costPerMem = ss.getCloudCostPerMem();
		double costPerStorage = ss.getCloudCostPerStorage();

		List<Host> hostList = createHosts();

		String name = "CloudDatacenter_" + Integer.toString(index);
		double time_zone = 3.0; // Time zone this resource is located
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // No SAN devices added currently

		// Create datacenter characteristics object with infrastructure properties:
		// architecture, OS, VMM, host list, time zone, and pricing model
		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				arch, os, vmm, hostList, time_zone, costPerSec, costPerMem, costPerStorage, costPerBw);

		// Create the datacenter with VM allocation policy
		Datacenter datacenter = null;

		VmAllocationPolicy vm_policy = getVmAllocationPolicy(hostList, index);
		datacenter = new Datacenter(name, characteristics, vm_policy, storageList, 0);

		// Register cloud datacenter costs in SimSettings (configurable; by default cloud
		// compute is derived as multiplier x edge compute cost).
		SimSettings.datacenterCosts.put(datacenter.getId(), new Double[] {
				costPerBw,
				costPerSec,
				costPerMem,
				costPerStorage
		});

		return datacenter;
	}

	private Datacenter createDatacenterFromElement(int index, Element datacenterElement) throws Exception {
		String arch = datacenterElement.getAttribute("arch");
		String os = datacenterElement.getAttribute("os");
		String vmm = datacenterElement.getAttribute("vmm");
		double costPerBw = Double
				.parseDouble(datacenterElement.getElementsByTagName("costPerBw").item(0).getTextContent());
		double costPerSec = Double
				.parseDouble(datacenterElement.getElementsByTagName("costPerSec").item(0).getTextContent());
		double costPerMem = Double
				.parseDouble(datacenterElement.getElementsByTagName("costPerMem").item(0).getTextContent());
		double costPerStorage = Double
				.parseDouble(datacenterElement.getElementsByTagName("costPerStorage").item(0).getTextContent());

		List<Host> hostList = createHostsFromElement(datacenterElement);

		String name = "CloudDatacenter_" + Integer.toString(index);
		double time_zone = 3.0;
		LinkedList<Storage> storageList = new LinkedList<Storage>();

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				arch, os, vmm, hostList, time_zone, costPerSec, costPerMem, costPerStorage, costPerBw);

		VmAllocationPolicy vm_policy = getVmAllocationPolicy(hostList, index);
		Datacenter datacenter = new Datacenter(name, characteristics, vm_policy, storageList, 0);

		SimSettings.datacenterCosts.put(datacenter.getId(), new Double[] {
				costPerBw,
				costPerSec,
				costPerMem,
				costPerStorage
		});

		return datacenter;
	}

	/**
	 * Creates the list of hosts for the cloud datacenter.
	 * Each host is configured with processing elements, RAM, storage, and bandwidth
	 * based on simulation settings and number of VMs per host.
	 * 
	 * @return List of configured Host objects for the datacenter
	 */
	private List<Host> createHosts() {
		// Step 1: Create list to store cloud hosts
		List<Host> hostList = new ArrayList<Host>();

		for (int i = 0; i < SimSettings.getInstance().getNumOfCloudHost(); i++) {
			int numOfVMPerHost = SimSettings.getInstance().getNumOfCloudVMsPerHost();
			// Calculate total resources needed (host resources = sum of VM resources)
			int numOfCores = SimSettings.getInstance().getCoreForCloudVM() * numOfVMPerHost;
			double mips = SimSettings.getInstance().getMipsForCloudVM() * numOfVMPerHost;
			int ram = SimSettings.getInstance().getRamForCloudVM() * numOfVMPerHost;
			long storage = SimSettings.getInstance().getStorageForCloudVM() * numOfVMPerHost;
			long bandwidth = 0;

			// Step 2: Create processing elements (PEs) for this host
			List<Pe> peList = new ArrayList<Pe>();

			// Step 3: Add PEs to the list with MIPS provisioner
			for (int j = 0; j < numOfCores; j++) {
				peList.add(new Pe(j, new PeProvisionerSimple(mips))); // PE ID and MIPS capacity
			}

			// Step 4: Create host with unique ID and resource provisioners
			Host host = new Host(
					cloudHostIdCounter++,
					new RamProvisionerSimple(ram),
					new BwProvisionerSimple(bandwidth), // Bandwidth in kbps
					storage,
					peList,
					new VmSchedulerSpaceShared(peList) // Space-shared VM scheduling
			);
			hostList.add(host);
		}

		return hostList;
	}

	private List<Host> createHostsFromElement(Element datacenterElement) {
		List<Host> hostList = new ArrayList<Host>();

		NodeList hostNodeList = datacenterElement.getElementsByTagName("host");
		for (int j = 0; j < hostNodeList.getLength(); j++) {
			Element hostElement = (Element) hostNodeList.item(j);
			int numOfCores = Integer.parseInt(hostElement.getElementsByTagName("core").item(0).getTextContent());
			double mips = Double.parseDouble(hostElement.getElementsByTagName("mips").item(0).getTextContent());
			int ram = Integer.parseInt(hostElement.getElementsByTagName("ram").item(0).getTextContent());
			long storage = Long.parseLong(hostElement.getElementsByTagName("storage").item(0).getTextContent());
			long bandwidth = 0;

			List<Pe> peList = new ArrayList<Pe>();
			for (int i = 0; i < numOfCores; i++) {
				peList.add(new Pe(i, new PeProvisionerSimple(mips)));
			}

			Host host = new Host(
					cloudHostIdCounter++,
					new RamProvisionerSimple(ram),
					new BwProvisionerSimple(bandwidth),
					storage,
					peList,
					new VmSchedulerSpaceShared(peList));
			hostList.add(host);
		}

		return hostList;
	}

	private boolean isCloudDatacenterElement(Element datacenterElement) {
		if (datacenterElement == null) {
			return false;
		}
		String tier = datacenterElement.getAttribute("tier");
		return tier != null && tier.equalsIgnoreCase("CLOUD");
	}
}
