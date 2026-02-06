package edu.boun.edgecloudsim.dagsim;

import com.google.gson.*;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * JSON loader for DAG records.
 * Loads DAG JSON files, parses them, and constructs DagRecord objects with task dependencies.
 */
public class DagJsonLoader {
    
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Load all DAG JSON files from a directory.
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
        
        // Compute relative submission times
        if (!dags.isEmpty()) {
            double minSubmitTime = dags.get(0).getSubmissionTimeEpochSec();
            for (DagRecord dag : dags) {
                long submitAtSimMs = (long) ((dag.getSubmissionTimeEpochSec() - minSubmitTime) * 1000.0);
                dag.setSubmitAtSimMs(submitAtSimMs);
            }
        }
        
        System.out.println("Loaded " + dags.size() + " DAGs from " + dirPath);
        return dags;
    }
    
    /**
     * Load a single DAG from a JSON file.
     */
    private static DagRecord loadDagFromJson(File jsonFile) throws IOException {
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject dagJson = JsonParser.parseReader(reader).getAsJsonObject();
            
            DagRecord dag = new DagRecord();
            dag.setDagId(dagJson.get("dag_id").getAsString());
            dag.setSubmissionTimeEpochSec(dagJson.get("submission_time").getAsDouble());
            dag.setNumInferenceSteps(dagJson.get("num_inference_steps").getAsInt());
            dag.setPromptLength(dagJson.get("prompt_length").getAsInt());
            dag.setNumImages(dagJson.get("num_images").getAsInt());
            dag.setHasLora(dagJson.get("has_lora").getAsBoolean());
            dag.setHasControlnet(dagJson.get("has_controlnet").getAsBoolean());
            
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
