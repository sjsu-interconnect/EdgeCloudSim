package edu.boun.edgecloudsim.dagsim;

import com.google.gson.*;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import edu.boun.edgecloudsim.core.SimSettings;

/**
 * JSON loader for DAG records.
 * Loads DAG JSON files, parses them, and constructs DagRecord objects with task
 * dependencies.
 */
public class DagJsonLoader {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Load all DAG JSON files from a directory.
     * Supports both individual DAG files and container files with "dags" array.
     * 
     * @param dirPath Directory containing *.json DAG files
     * @return List of DagRecords sorted by submission time
     * @throws IOException if file reading fails
     */
    public static List<DagRecord> loadAllDags(String dirPath) throws IOException {
        List<DagRecord> dags = new ArrayList<>();
        File dir = new File(dirPath);

        if (!dir.isDirectory()) {
            throw new IOException("Not a directory: " + dirPath);
        }

        File[] jsonFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (jsonFiles == null || jsonFiles.length == 0) {
            System.out.println("Warning: No JSON files found in " + dirPath);
            return dags;
        }

        for (File jsonFile : jsonFiles) {
            try {
                // Check if file contains a "dags" array (multi-DAG container)
                try (FileReader reader = new FileReader(jsonFile)) {
                    JsonElement elem = JsonParser.parseReader(reader);
                    if (elem.isJsonObject()) {
                        JsonObject rootJson = elem.getAsJsonObject();
                        if (rootJson.has("dags") && rootJson.get("dags").isJsonArray()) {
                            // Load each DAG from the array
                            JsonArray dagsArray = rootJson.getAsJsonArray("dags");
                            for (JsonElement dagElem : dagsArray) {
                                if (dagElem.isJsonObject()) {
                                    DagRecord dag = parseSingleDag(dagElem.getAsJsonObject());
                                    if (dag != null) {
                                        dags.add(dag);
                                    }
                                }
                            }
                            continue;
                        }
                    }
                }

                // Otherwise, treat as individual DAG file
                DagRecord dag = loadDagFromJson(jsonFile);
                if (dag != null) {
                    dags.add(dag);
                }
            } catch (Exception e) {
                System.err.println("Error loading " + jsonFile.getName() + ": " + e.getMessage());
            }
        }

        // Sort by submission time
        dags.sort(Comparator.comparingDouble(DagRecord::getSubmissionTimeEpochSec));

        // Compute relative submission times using Poisson process
        if (!dags.isEmpty()) {
            double warmUpOffsetMs = SimSettings.getInstance().getWarmUpPeriod() * 1000.0;
            double interArrivalMeanSec = SimSettings.getInstance().getDagInterarrivalRate();

            // Create exponential distribution for inter-arrival times
            ExponentialDistribution expDist = new ExponentialDistribution(interArrivalMeanSec * 1000.0);

            // Use SimUtils seed if available for reproducibility
            if (SimSettings.getInstance().hasRngSeed()) {
                expDist.reseedRandomGenerator(SimSettings.getInstance().getRngSeed() + 999);
            }

            double currentSubmitTimeMs = warmUpOffsetMs;
            for (DagRecord dag : dags) {
                // Sample next inter-arrival time
                double interArrivalTimeMs = expDist.sample();
                currentSubmitTimeMs += interArrivalTimeMs;

                dag.setSubmitAtSimMs((long) currentSubmitTimeMs);
            }
        }

        System.out.println("Loaded " + dags.size() + " DAGs from " + dirPath + " with Poisson arrival (mean="
                + SimSettings.getInstance().getDagInterarrivalRate() + "s)");
        return dags;
    }

    /**
     * Load a single DAG from a JSON file or array of DAGs.
     */
    private static DagRecord loadDagFromJson(File jsonFile) throws IOException {
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonElement elem = JsonParser.parseReader(reader);
            JsonObject rootJson = elem.getAsJsonObject();

            // Check if this is a container with a "dags" array (e.g., synthetic_dags.json)
            if (rootJson.has("dags") && rootJson.get("dags").isJsonArray()) {
                // This file contains multiple DAGs; we'll load them all via a special method
                return null; // Signal to load via array method
            }

            // Otherwise, assume this is a single DAG JSON object
            return parseSingleDag(rootJson);
        }
    }

    /**
     * Parse a single DAG from a JSON object.
     */
    private static DagRecord parseSingleDag(JsonObject dagJson) {
        DagRecord dag = new DagRecord();
        dag.setDagId(dagJson.get("dag_id").getAsString());
        dag.setSubmissionTimeEpochSec(dagJson.get("submission_time").getAsDouble());

        // Optional fields
        if (dagJson.has("num_inference_steps")) {
            dag.setNumInferenceSteps(dagJson.get("num_inference_steps").getAsInt());
        }
        if (dagJson.has("prompt_length")) {
            dag.setPromptLength(dagJson.get("prompt_length").getAsInt());
        }
        if (dagJson.has("num_images")) {
            dag.setNumImages(dagJson.get("num_images").getAsInt());
        }
        if (dagJson.has("has_lora")) {
            dag.setHasLora(dagJson.get("has_lora").getAsBoolean());
        }
        if (dagJson.has("has_controlnet")) {
            dag.setHasControlnet(dagJson.get("has_controlnet").getAsBoolean());
        }

        // Load tasks
        JsonArray tasksJson = dagJson.getAsJsonArray("tasks");
        Map<String, TaskRecord> tasksById = new HashMap<>();

        for (JsonElement taskElem : tasksJson) {
            JsonObject taskJson = taskElem.getAsJsonObject();
            TaskRecord task = parseTask(taskJson);
            tasksById.put(task.getTaskId(), task);
        }

        // Build dependencies
        for (JsonElement taskElem : tasksJson) {
            JsonObject taskJson = taskElem.getAsJsonObject();
            String taskId = taskJson.get("task_id").getAsString();
            TaskRecord task = tasksById.get(taskId);

            JsonArray dependsOnJson = taskJson.getAsJsonArray("depends_on");
            if (dependsOnJson != null) {
                List<String> dependsOn = new ArrayList<>();
                for (JsonElement depElem : dependsOnJson) {
                    String depTaskId = depElem.getAsString();
                    dependsOn.add(depTaskId);

                    // Add to parent's children list
                    TaskRecord parentTask = tasksById.get(depTaskId);
                    if (parentTask != null) {
                        parentTask.getChildren().add(taskId);
                    }
                }
                task.setDependsOn(dependsOn);
                task.setRemainingDeps(dependsOn.size());
            }
        }

        // Add all tasks to DAG
        for (TaskRecord task : tasksById.values()) {
            dag.addTask(task.getTaskId(), task);
        }

        return dag;
    }

    /**
     * Parse a single task from JSON.
     */
    private static TaskRecord parseTask(JsonObject taskJson) {
        TaskRecord task = new TaskRecord();
        task.setTaskId(taskJson.get("task_id").getAsString());
        task.setTaskType(taskJson.get("task_type").getAsString());
        task.setDurationMs(taskJson.get("duration_ms").getAsDouble());
        task.setMemoryMb(taskJson.get("memory_mb").getAsDouble());
        task.setGpuMemoryMb(taskJson.get("gpu_memory_mb").getAsDouble());
        task.setGpuUtilization(taskJson.get("gpu_utilization").getAsDouble());

        return task;
    }
}
