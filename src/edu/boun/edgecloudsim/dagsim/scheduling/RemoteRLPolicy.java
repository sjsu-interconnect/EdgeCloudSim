package edu.boun.edgecloudsim.dagsim.scheduling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.boun.edgecloudsim.dagsim.DagRecord;
import edu.boun.edgecloudsim.dagsim.DagRuntimeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remote RL Policy that delegates scheduling decisions to an external RL agent
 * (e.g., a Python service in a different repository) via HTTP/JSON.
 */
public class RemoteRLPolicy implements SchedulingPolicy {
    private final String serviceUrl;
    private final Gson gson;

    public RemoteRLPolicy(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        this.gson = new Gson();
    }

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        try {
            // 1. Prepare the observation/state snippet
            Map<String, Object> payload = new HashMap<>();
            payload.put("task", task);
            payload.put("cluster_state", state);

            // 2. Add extra info (Incoming requests and DAG queue) as requested by USER
            if (DagRuntimeManager.getInstance() != null) {
                // You can add more detailed queue info here if needed
                payload.put("active_dags_count", DagRuntimeManager.getInstance().getActiveDagsCount());
                // Optionally serialize active DAGs summary
                // payload.put("active_dags",
                // DagRuntimeManager.getInstance().getActiveDagsSummary());
            }

            // 3. Send to Remote RL Service
            String responseJson = postRequest(serviceUrl, gson.toJson(payload));

            // 4. Parse the decision
            JsonObject decisionObj = gson.fromJson(responseJson, JsonObject.class);
            int tier = decisionObj.get("tier").getAsInt();
            int datacenterId = decisionObj.get("datacenterId").getAsInt();
            int vmId = decisionObj.get("vmId").getAsInt();

            return new PlacementDecision(tier, datacenterId, vmId);

        } catch (Exception e) {
            System.err.println("RemoteRLPolicy failed: " + e.getMessage());
            // Fallback to EdgeFirstFeasible if remote fails
            return new EdgeFirstFeasiblePolicy().decide(task, state);
        }
    }

    private String postRequest(String urlString, String jsonInputString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        }
    }

    @Override
    public String getPolicyName() {
        return "RemoteRLPolicy";
    }
}
