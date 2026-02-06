package edu.boun.edgecloudsim.task_generator;

import java.util.ArrayList;
import java.util.List;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * Empty load generator that produces no synthetic tasks.
 * Used when running DAG-only simulations to avoid device ID conflicts.
 */
public class EmptyLoadGenerator extends LoadGeneratorModel {
    
    public EmptyLoadGenerator(int numOfMobileDevice, double simulationTime, String simScenario) {
        super(numOfMobileDevice, simulationTime, simScenario);
    }
    
    @Override
    public void initializeModel() {
        // No initialization needed
    }
    
    @Override
    public List<TaskProperty> getTaskList() {
        // Return empty list - no synthetic tasks
        return new ArrayList<>();
    }
    
    @Override
    public int getTaskTypeOfDevice(int deviceId) {
        // Not used when no tasks are generated
        return 0;
    }
}
