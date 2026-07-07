package edu.boun.edgecloudsim.dagsim.scheduling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.dagsim.DagRuntimeManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote RL Policy that delegates scheduling decisions to an external RL agent
 * via HTTP/JSON.
 */
public class RemoteRLPolicy implements SchedulingPolicy {
    private final String serviceUrl;
    private final String actUrl;
    private final Gson gson;
    private final int timeoutMs;

    private static final Gson STATIC_GSON = new Gson();
    private static final Map<String, DecisionTrace> TRACE_BY_TASK = new ConcurrentHashMap<>();

    public static class DecisionTrace {
        public JsonObject state;
        public JsonObject action;
        public int selectedDcVmCount = -1;
        public int selectedDcQueueLen = -1;
        public double selectedDcAvgQueueLen = -1.0;
        public double selectedDcMaxQueueLen = -1.0;
        public double selectedDcAvgUtilization = -1.0;
    }

    public RemoteRLPolicy(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        this.actUrl = resolveEndpoint(serviceUrl, "/act");
        this.gson = new Gson();
        this.timeoutMs = SimSettings.getInstance().getRlHttpTimeoutMs();
    }

    @Override
    public PlacementDecision decide(TaskContext task, ClusterState state) {
        try {
            DagRuntimeManager drm = DagRuntimeManager.getInstance();
            double budget = SimSettings.getInstance().getRlBudgetCost();
            double costSoFar = (drm != null && task.dagId != null) ? drm.getDagCostSoFar(task.dagId) : 0.0;
            int activeDagCount = (drm != null) ? drm.getActiveDagsCount() : 0;

            JsonObject stateJson = buildStateJson(task, state, costSoFar, budget, activeDagCount);
            JsonObject payload = new JsonObject();
            payload.add("state", stateJson);
            payload.addProperty("trainingMode", SimSettings.getInstance().getRlTrainingMode());
            payload.add("actionMask", buildActionMask(state));

            String responseJson = postRequest(actUrl, gson.toJson(payload), timeoutMs);
            JsonObject decisionObj = gson.fromJson(responseJson, JsonObject.class);
            // String tierName = decisionObj.get("tier").getAsString().toUpperCase();
            // int tier = "CLOUD".equals(tierName) ? PlacementDecision.TIER_CLOUD : PlacementDecision.TIER_EDGE;
            // int datacenterId = decisionObj.get("datacenterId").getAsInt();
            // int vmId = decisionObj.get("vmId").getAsInt();
            String tierName = decisionObj.get("tier").getAsString().toUpperCase();
            int tier = "CLOUD".equals(tierName) ? PlacementDecision.TIER_CLOUD : PlacementDecision.TIER_EDGE;
            int datacenterId = decisionObj.get("datacenterId").getAsInt();

            int vmId;
            if (decisionObj.has("vmId") && !decisionObj.get("vmId").isJsonNull()) {
                vmId = decisionObj.get("vmId").getAsInt();
            } else {
                vmId = selectDefaultVmId(state, tier, datacenterId);
            }

            if (task.dagId != null && task.taskId != null) {
                JsonObject actionObj = new JsonObject();
                actionObj.addProperty("tier", tierName);
                actionObj.addProperty("datacenterId", datacenterId);
                actionObj.addProperty("vmId", vmId);
                if (decisionObj.has("actionIndex")) {
                    actionObj.add("actionIndex", decisionObj.get("actionIndex"));
                }
                DecisionTrace trace = new DecisionTrace();
                trace.state = stateJson;
                trace.action = actionObj;
                populateSelectedDcStats(trace, state, tier, datacenterId);
                TRACE_BY_TASK.put(key(task.dagId, task.taskId), trace);
            }

            return new PlacementDecision(tier, datacenterId, vmId);

        } catch (Exception e) {
            System.err.println("RemoteRLPolicy failed: " + e.getMessage());
            PlacementDecision fallback = new EdgeFirstFeasiblePolicy().decide(task, state);

            // Store a fallback trace so onTaskCloudletFinished can still call postObservation.
            // Without this the observation is silently skipped and Python blocks forever.
            if (task.dagId != null && task.taskId != null) {
                double budget = SimSettings.getInstance().getRlBudgetCost();
                DagRuntimeManager drm = DagRuntimeManager.getInstance();
                double costSoFar = (drm != null) ? drm.getDagCostSoFar(task.dagId) : 0.0;
                int activeDagCount = (drm != null) ? drm.getActiveDagsCount() : 0;
                JsonObject stateJson = buildStateJson(task, state, costSoFar, budget, activeDagCount);

                String tierName = (fallback.destTier == PlacementDecision.TIER_CLOUD) ? "CLOUD" : "EDGE";
                JsonObject actionObj = new JsonObject();
                actionObj.addProperty("tier", tierName);
                actionObj.addProperty("datacenterId", fallback.destDatacenterId);
                actionObj.addProperty("vmId", fallback.destVmId);
                actionObj.addProperty("actionIndex", -1); // marker: fallback decision

                DecisionTrace trace = new DecisionTrace();
                trace.state = stateJson;
                trace.action = actionObj;
                populateSelectedDcStats(trace, state, fallback.destTier, fallback.destDatacenterId);
                TRACE_BY_TASK.put(key(task.dagId, task.taskId), trace);
            }

            return fallback;
        }
    }

    @Override
    public String getPolicyName() {
        return "RemoteRLPolicy";
    }

    public static DecisionTrace consumeTrace(String dagId, String taskId) {
        if (dagId == null || taskId == null) {
            return null;
        }
        return TRACE_BY_TASK.remove(key(dagId, taskId));
    }

    public static void postObservation(
            String serviceUrl,
            int timeoutMs,
            DecisionTrace trace,
            JsonObject nextState,
            double reward,
            boolean done,
            double actualLatency,
            double actualCost,
            double costSoFar,
            double budget,
            boolean budgetViolated) {
        if (trace == null || trace.state == null || trace.action == null) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.add("state", trace.state);
            payload.add("action", trace.action);
            payload.addProperty("reward", reward);
            payload.add("next_state", nextState);
            payload.addProperty("done", done);

            JsonObject info = new JsonObject();
            info.addProperty("actualLatency", actualLatency);
            info.addProperty("actualCost", actualCost);
            info.addProperty("costSoFar", costSoFar);
            info.addProperty("budget", budget);
            info.addProperty("budgetViolated", budgetViolated);
            payload.add("info", info);

            String observeUrl = resolveEndpoint(serviceUrl, "/observe");
            postRequest(observeUrl, STATIC_GSON.toJson(payload), timeoutMs);
        } catch (Exception e) {
            System.err.println("RemoteRLPolicy observe failed: " + e.getMessage());
        }
    }

    public static JsonObject buildStateJson(
            TaskContext task,
            ClusterState state,
            double costSoFar,
            double budget,
            int activeDagCount) {
        JsonObject root = new JsonObject();

        JsonObject taskObj = new JsonObject();
        taskObj.addProperty("dagId", task.dagId != null ? task.dagId : "NA");
        taskObj.addProperty("taskId", task.taskId != null ? task.taskId : "NA");
        taskObj.addProperty("taskType", task.taskType != null ? task.taskType : "NA");
        taskObj.addProperty("mi", task.lengthMI);
        taskObj.addProperty("dataSizeBytes", Math.max(1.0, task.cpuMemoryMb * 1024.0 * 1024.0));
        root.add("task", taskObj);

        JsonObject clusterObj = new JsonObject();
        JsonArray edgeVms = new JsonArray();
        JsonArray cloudVms = new JsonArray();
        int totalQueue = 0;

        double edgeMips = 0.0;
        double edgeUtil = 0.0;
        int edgeCount = 0;
        double cloudMips = 0.0;
        double cloudUtil = 0.0;
        int cloudCount = 0;

        if (state.vms != null) {
            for (int tier = 0; tier < state.vms.length; tier++) {
                if (state.vms[tier] == null) {
                    continue;
                }
                for (ClusterState.VMInfo[] dcVms : state.vms[tier]) {
                    if (dcVms == null) {
                        continue;
                    }
                    for (ClusterState.VMInfo vm : dcVms) {
                        if (vm == null) {
                            continue;
                        }
                        JsonObject vmObj = new JsonObject();
                        vmObj.addProperty("dcId", vm.datacenterId);
                        vmObj.addProperty("vmId", vm.vmId);
                        vmObj.addProperty("availableMips", vm.mips);
                        double util = vm.queuedTaskCount <= 0 ? 0.0
                                : Math.min(1.0, vm.queuedTaskCount / (double) (vm.queuedTaskCount + 1));
                        vmObj.addProperty("utilization", util);
                        vmObj.addProperty("queueLen", vm.queuedTaskCount);
                        vmObj.addProperty("reservedAvailableAtMs", vm.reservedAvailableAtMs);
                        vmObj.addProperty("reservedWaitMs", vm.reservedWaitMs);
                        vmObj.addProperty("costPerBw", vm.costPerBw);
                        vmObj.addProperty("costPerSec", vm.costPerSec);
                        totalQueue += vm.queuedTaskCount;

                        if (tier == PlacementDecision.TIER_EDGE) {
                            edgeVms.add(vmObj);
                            edgeMips += vm.mips;
                            edgeUtil += util;
                            edgeCount++;
                        } else {
                            cloudVms.add(vmObj);
                            cloudMips += vm.mips;
                            cloudUtil += util;
                            cloudCount++;
                        }
                    }
                }
            }
        }

        sortVmArray(edgeVms);
        sortVmArray(cloudVms);
        clusterObj.add("edgeVms", edgeVms);
        clusterObj.add("cloudVms", cloudVms);

        JsonObject edgeAgg = new JsonObject();
        edgeAgg.addProperty("availableMips", edgeMips);
        edgeAgg.addProperty("utilization", edgeCount > 0 ? edgeUtil / edgeCount : 0.0);
        edgeAgg.addProperty("queueLen", sumQueue(edgeVms));
        clusterObj.add("edge", edgeAgg);

        JsonObject cloudAgg = new JsonObject();
        cloudAgg.addProperty("availableMips", cloudMips);
        cloudAgg.addProperty("utilization", cloudCount > 0 ? cloudUtil / cloudCount : 0.0);
        cloudAgg.addProperty("queueLen", sumQueue(cloudVms));
        clusterObj.add("cloud", cloudAgg);
        root.add("cluster", clusterObj);

        JsonObject budgetObj = new JsonObject();
        double remaining = budget - costSoFar;
        budgetObj.addProperty("costSoFar", costSoFar);
        budgetObj.addProperty("remainingBudget", remaining);
        budgetObj.addProperty("budgetFractionUsed", budget > 0 ? costSoFar / budget : 0.0);
        root.add("budget", budgetObj);

        JsonObject queueObj = new JsonObject();
        queueObj.addProperty("activeDagCount", activeDagCount);
        queueObj.addProperty("totalQueueLen", totalQueue);
        root.add("queue", queueObj);

        JsonObject timeObj = new JsonObject();
        timeObj.addProperty("simTime", state.currentTimeMs);
        root.add("time", timeObj);

        return root;
    }

    private static JsonArray buildActionMask(ClusterState state) {
        JsonArray arr = new JsonArray();

        int edgeDcCount = 0;
        int cloudDcCount = 0;

        if (state.vms != null) {
            if (state.vms.length > PlacementDecision.TIER_EDGE && state.vms[PlacementDecision.TIER_EDGE] != null) {
                edgeDcCount = state.vms[PlacementDecision.TIER_EDGE].length;
            }
            if (state.vms.length > PlacementDecision.TIER_CLOUD && state.vms[PlacementDecision.TIER_CLOUD] != null) {
                cloudDcCount = state.vms[PlacementDecision.TIER_CLOUD].length;
            }
        }

        // Edge actions first: one action per edge datacenter
        for (int dc = 0; dc < edgeDcCount; dc++) {
            ClusterState.VMInfo[] dcVms = state.vms[PlacementDecision.TIER_EDGE][dc];
            boolean feasible = dcVms != null && dcVms.length > 0;
            arr.add(feasible ? 1 : 0);
        }

        // Cloud actions next: one action per cloud datacenter
        for (int dc = 0; dc < cloudDcCount; dc++) {
            ClusterState.VMInfo[] dcVms = state.vms[PlacementDecision.TIER_CLOUD][dc];
            boolean feasible = dcVms != null && dcVms.length > 0;
            arr.add(feasible ? 1 : 0);
        }

        // Safety fallback if cluster info is missing
        if (arr.size() == 0) {
            arr.add(1); // edge dc 0
            arr.add(1); // cloud dc 0
        }

        return arr;
    }

    private static int sumQueue(JsonArray vmArray) {
        int total = 0;
        for (JsonElement e : vmArray) {
            JsonObject vm = e.getAsJsonObject();
            total += vm.get("queueLen").getAsInt();
        }
        return total;
    }

    private static void sortVmArray(JsonArray arr) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : arr) {
            list.add(e.getAsJsonObject());
        }
        list.sort(Comparator
                .comparingInt((JsonObject o) -> o.get("dcId").getAsInt())
                .thenComparingInt(o -> o.get("vmId").getAsInt()));
        while (arr.size() > 0) {
            arr.remove(arr.size() - 1);
        }
        for (JsonObject o : list) {
            arr.add(o);
        }
    }

    private static String resolveEndpoint(String configuredUrl, String endpointPath) {
        String url = configuredUrl.trim();
        if (url.endsWith(endpointPath)) {
            return url;
        }
        if (url.endsWith("/act") || url.endsWith("/observe")) {
            int idx = url.lastIndexOf('/');
            url = url.substring(0, idx);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + endpointPath;
    }

    private static String key(String dagId, String taskId) {
        return dagId + "::" + taskId;
    }

    private static String postRequest(String urlString, String jsonInputString, int timeoutMs) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = conn.getResponseCode();
        BufferedReader br;
        if (status >= 200 && status < 300) {
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        try (br) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            if (status < 200 || status >= 300) {
                throw new RuntimeException("HTTP " + status + " from " + urlString + ": " + response);
            }
            return response.toString();
        }
    }

    private static int selectDefaultVmId(ClusterState state, int tier, int datacenterId) {
        if (state == null || state.vms == null) {
            return 0;
        }
        if (tier < 0 || tier >= state.vms.length || state.vms[tier] == null) {
            return 0;
        }
        if (datacenterId < 0 || datacenterId >= state.vms[tier].length) {
            return 0;
        }

        ClusterState.VMInfo[] dcVms = state.vms[tier][datacenterId];
        if (dcVms == null || dcVms.length == 0) {
            return 0;
        }

        ClusterState.VMInfo best = null;
        for (ClusterState.VMInfo vm : dcVms) {
            if (vm == null) {
                continue;
            }
            if (best == null || vm.queuedTaskCount < best.queuedTaskCount) {
                best = vm;
            }
        }

        return best != null ? best.vmId : dcVms[0].vmId;
    }

    private static void populateSelectedDcStats(DecisionTrace trace, ClusterState state, int tier, int datacenterId) {
        if (trace == null || state == null || state.vms == null) {
            return;
        }
        if (tier < 0 || tier >= state.vms.length || state.vms[tier] == null) {
            return;
        }
        if (datacenterId < 0 || datacenterId >= state.vms[tier].length) {
            return;
        }

        ClusterState.VMInfo[] dcVms = state.vms[tier][datacenterId];
        if (dcVms == null || dcVms.length == 0) {
            trace.selectedDcVmCount = 0;
            trace.selectedDcQueueLen = 0;
            trace.selectedDcAvgQueueLen = 0.0;
            trace.selectedDcMaxQueueLen = 0.0;
            trace.selectedDcAvgUtilization = 0.0;
            return;
        }

        int vmCount = 0;
        int totalQueue = 0;
        int maxQueue = 0;
        double utilSum = 0.0;
        for (ClusterState.VMInfo vm : dcVms) {
            if (vm == null) {
                continue;
            }
            vmCount++;
            totalQueue += vm.queuedTaskCount;
            maxQueue = Math.max(maxQueue, vm.queuedTaskCount);
            double util = vm.queuedTaskCount <= 0 ? 0.0
                    : Math.min(1.0, vm.queuedTaskCount / (double) (vm.queuedTaskCount + 1));
            utilSum += util;
        }

        trace.selectedDcVmCount = vmCount;
        trace.selectedDcQueueLen = totalQueue;
        trace.selectedDcAvgQueueLen = vmCount > 0 ? totalQueue / (double) vmCount : 0.0;
        trace.selectedDcMaxQueueLen = maxQueue;
        trace.selectedDcAvgUtilization = vmCount > 0 ? utilSum / (double) vmCount : 0.0;
    }
}
