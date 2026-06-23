package edu.boun.edgecloudsim.mobility;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * Static mobility model: each mobile device is assigned one edge location at
 * initialization and remains there for the whole simulation.
 */
public class StaticMobility extends MobilityModel {
	private Location[] locations;

	public StaticMobility(int numberOfMobileDevices, double simulationTime) {
		super(numberOfMobileDevices, simulationTime);
	}

	@Override
	public void initialize() {
		locations = new Location[numberOfMobileDevices];

		Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
		NodeList datacenterList = doc.getElementsByTagName("datacenter");
		if (datacenterList.getLength() == 0) {
			SimLogger.printLine("StaticMobility: no edge datacenter locations found.");
			System.exit(1);
		}

		for (int deviceId = 0; deviceId < numberOfMobileDevices; deviceId++) {
			int datacenterIndex = deviceId % datacenterList.getLength(); //round robin
			locations[deviceId] = readDatacenterLocation(datacenterList.item(datacenterIndex));
		}
	}

	@Override
	public Location getLocation(int deviceId, double time) {
		if (deviceId < 0 || deviceId >= locations.length || locations[deviceId] == null) {
			SimLogger.printLine("StaticMobility: no location found for device " + deviceId + " at " + time);
			System.exit(1);
		}
		return locations[deviceId];
	}

	private Location readDatacenterLocation(Node datacenterNode) {
		Element datacenterElement = (Element) datacenterNode;
		Element location = (Element) datacenterElement.getElementsByTagName("location").item(0);
		int placeTypeIndex = Integer.parseInt(location.getElementsByTagName("attractiveness").item(0).getTextContent());
		int wlanId = Integer.parseInt(location.getElementsByTagName("wlan_id").item(0).getTextContent());
		int xPos = Integer.parseInt(location.getElementsByTagName("x_pos").item(0).getTextContent());
		int yPos = Integer.parseInt(location.getElementsByTagName("y_pos").item(0).getTextContent());
		return new Location(placeTypeIndex, wlanId, xPos, yPos);
	}
}
