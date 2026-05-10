package games.beiming.website.ops.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.common.core.response.ApiResponse;
import games.beiming.website.ops.model.RemoteNode;
import games.beiming.website.ops.service.ContainerNormalizer;
import games.beiming.website.ops.service.DaemonClient;
import games.beiming.website.ops.service.NodeService;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class OpsController {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<List<Map<String, Object>>>() {};
    private static final List<String> DOWNLOAD_HEADERS = java.util.Arrays.asList(
        "accept-ranges",
        "content-disposition",
        "content-length",
        "content-range",
        "content-type",
        "x-file-name",
        "x-file-size"
    );

    private final NodeService nodes;
    private final DaemonClient daemon;
    private final ContainerNormalizer normalizer;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public OpsController(NodeService nodes, DaemonClient daemon, ContainerNormalizer normalizer, ObjectMapper mapper) {
        this.nodes = nodes;
        this.daemon = daemon;
        this.normalizer = normalizer;
        this.mapper = mapper;
    }

    @GetMapping("/api/ops/ping")
    public ApiResponse<Map<String, String>> ping() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("service", "ops-service");
        data.put("message", "ops service ready");
        return ApiResponse.success(data);
    }

    @GetMapping("/api/ops/nodes")
    public ApiResponse<List<Map<String, Object>>> listNodes() {
        return ApiResponse.success(nodes.publicNodes());
    }

    @PostMapping("/api/ops/nodes")
    public ApiResponse<Map<String, Object>> createNode(@RequestBody Map<String, Object> body) {
        RemoteNode node = nodes.create(body);
        return ApiResponse.success(findPublicNode(node.getId()));
    }

    @PutMapping("/api/ops/nodes/{nodeId}")
    public ApiResponse<Map<String, Object>> updateNode(@PathVariable String nodeId, @RequestBody Map<String, Object> body) {
        RemoteNode node = nodes.upsert(nodeId, body);
        return ApiResponse.success(findPublicNode(node.getId()));
    }

    @DeleteMapping("/api/ops/nodes/{nodeId}")
    public ApiResponse<Map<String, Object>> deleteNode(@PathVariable String nodeId) {
        nodes.delete(nodeId);
        return ApiResponse.success(single("nodeId", nodeId));
    }

    @GetMapping("/api/ops/nodes/{nodeId}/ping")
    public ApiResponse<Map<String, Object>> pingNode(@PathVariable String nodeId) {
        RemoteNode node = nodes.get(nodeId);
        Map<String, Object> result = map(daemon.call(node, "/health"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("nodeId", node.getId());
        data.put("output", result.containsKey("service") ? result.get("service") : "beiming-daemon-ready");
        return ApiResponse.success(data);
    }

    @GetMapping("/api/ops/docker/images/search")
    public ApiResponse<Object> searchDockerImages(@RequestParam(defaultValue = "") String q) {
        if (q.trim().length() < 2) {
            return ApiResponse.success(new ArrayList<Object>());
        }
        Map<?, ?> payload = restTemplate.getForObject(
            "https://hub.docker.com/v2/search/repositories/?page_size=8&query={query}",
            Map.class,
            q.trim()
        );
        Object results = payload == null ? null : payload.get("results");
        return ApiResponse.success(results == null ? new ArrayList<Object>() : results);
    }

    @GetMapping("/api/ops/nodes/{nodeId}/metrics")
    public ApiResponse<Map<String, Object>> metrics(@PathVariable String nodeId) {
        Map<String, Object> raw = map(daemon.call(nodes.get(nodeId), "/api/metrics"));
        List<Double> mem = splitNumbers(raw.get("mem"));
        List<Double> swap = splitNumbers(raw.get("swap"));
        String[] diskParts = string(raw.get("disk")).split(" ");
        List<Double> net = splitNumbers(raw.get("net"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("cpu", parseCpuIdle(string(raw.get("cpuLine"))));
        Map<String, Object> cpuSpec = new LinkedHashMap<String, Object>();
        cpuSpec.put("cores", number(raw.get("cpuCores")));
        cpuSpec.put("threads", number(raw.containsKey("cpuThreads") ? raw.get("cpuThreads") : raw.get("cpuCores")));
        data.put("cpuSpec", cpuSpec);
        data.put("memory", metricUsage(mem));
        data.put("swap", metricUsage(swap));
        Map<String, Object> disk = new LinkedHashMap<String, Object>();
        disk.put("totalMb", diskParts.length > 0 ? number(diskParts[0]) : 0);
        disk.put("usedMb", diskParts.length > 1 ? number(diskParts[1]) : 0);
        disk.put("percent", diskParts.length > 2 ? number(diskParts[2].replace("%", "")) : 0);
        data.put("disk", disk);
        data.put("network", mapOfObject("downloadBps", 0, "uploadBps", 0));
        data.put("networkTotals", mapOfObject("downloadBytes", net.size() > 0 ? net.get(0) : 0, "uploadBytes", net.size() > 1 ? net.get(1) : 0));
        data.put("load", splitNumbers(raw.get("load")));
        data.put("updatedAt", System.currentTimeMillis());
        return ApiResponse.success(data);
    }

    @GetMapping("/api/ops/nodes/{nodeId}/containers")
    public ApiResponse<List<Map<String, Object>>> containers(@PathVariable String nodeId, @RequestParam(defaultValue = "0") String fast) {
        RemoteNode node = nodes.get(nodeId);
        Map<String, Object> payload = map(daemon.call(node, "/api/containers" + ("1".equals(fast) ? "?fast=1" : "")));
        List<Map<String, Object>> rows = mapList(payload.get("rows"));
        List<Map<String, Object>> statsRows = mapList(payload.get("stats"));
        List<Map<String, Object>> inspectRows = mapList(payload.get("inspect"));
        Map<String, Object> swapRows = map(payload.get("swap"));
        Map<String, Map<String, Object>> statsById = byAny(statsRows, "ID", "Container");
        Map<String, Map<String, Object>> statsByName = byAny(statsRows, "Name");
        Map<String, Map<String, Object>> inspectById = inspectByShortId(inspectRows);
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> inspect = inspectById.containsKey(string(row.get("ID"))) ? inspectById.get(string(row.get("ID"))) : new LinkedHashMap<String, Object>();
            Object swapValue = swapRows.containsKey(string(row.get("ID"))) ? swapRows.get(string(row.get("ID"))) : swapRows.get(pathString(inspect, "Id"));
            data.add(normalizer.enrich(row, firstMap(statsById.get(string(row.get("ID"))), statsByName.get(string(row.get("Names")))), inspect, node.getId(), asNumber(swapValue)));
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/api/ops/nodes/{nodeId}/containers/stats")
    public ApiResponse<List<Map<String, Object>>> containerStats(@PathVariable String nodeId) {
        RemoteNode node = nodes.get(nodeId);
        Map<String, Object> payload = map(daemon.call(node, "/api/containers/stats"));
        List<Map<String, Object>> statsRows = mapList(payload.get("stats"));
        List<Map<String, Object>> inspectRows = mapList(payload.get("inspect"));
        Map<String, Map<String, Object>> inspectById = inspectByShortId(inspectRows);
        Map<String, Map<String, Object>> inspectByName = byName(inspectRows);
        Map<String, Object> swapRows = map(payload.get("swap"));
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> stats : statsRows) {
            String shortId = string(first(stats.get("ID"), stats.get("Container")));
            Map<String, Object> inspect = firstMap(inspectById.get(shortId), inspectByName.get(string(stats.get("Name"))));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("ID", !shortId.isEmpty() ? shortId : left(pathString(inspect, "Id"), 12));
            row.put("Names", !string(stats.get("Name")).isEmpty() ? string(stats.get("Name")) : trimSlash(pathString(inspect, "Name")));
            row.put("Image", pathString(inspect, "Config", "Image"));
            row.put("State", pathString(inspect, "State", "Status"));
            row.put("Status", pathString(inspect, "State", "Status"));
            row.put("Ports", "");
            row.put("Command", "");
            data.add(normalizer.enrich(row, stats, inspect, node.getId(), asNumber(swapRows.containsKey(string(row.get("ID"))) ? swapRows.get(string(row.get("ID"))) : swapRows.get(pathString(inspect, "Id")))));
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/api/ops/nodes/{nodeId}/containers/{containerId}")
    public ApiResponse<Map<String, Object>> container(@PathVariable String nodeId, @PathVariable String containerId, @RequestParam(defaultValue = "0") String fast) {
        RemoteNode node = nodes.get(nodeId);
        Map<String, Object> payload = map(daemon.call(node, "/api/containers/" + DaemonClient.enc(containerId) + ("1".equals(fast) ? "?fast=1" : "")));
        Map<String, Object> row = map(payload.get("row"));
        if (row.isEmpty()) {
            throw new IllegalArgumentException("Container not found");
        }
        return ApiResponse.success(normalizer.enrich(row, map(payload.get("stats")), map(payload.get("inspect")), node.getId(), asNumber(payload.get("swap"))));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers")
    public ApiResponse<Object> createContainer(@PathVariable String nodeId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers", body, MediaType.APPLICATION_JSON));
    }

    @GetMapping("/api/ops/nodes/{nodeId}/images")
    public ApiResponse<List<Map<String, Object>>> images(@PathVariable String nodeId) {
        List<Map<String, Object>> rows = mapList(daemon.call(nodes.get(nodeId), "/api/images"));
        List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> image = normalizer.normalizeImage(row);
            if (!string(image.get("name")).isEmpty()) {
                data.add(image);
            }
        }
        return ApiResponse.success(data);
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/exec")
    public ApiResponse<Object> exec(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        String command = string(body.get("command")).trim();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Command is required");
        }
        if (command.matches(".*\\b(reboot|shutdown|poweroff|mkfs|dd\\s+if=|rm\\s+-rf\\s+/).*")) {
            throw new IllegalArgumentException("Command is blocked by safety policy");
        }
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/exec", single("command", command), MediaType.APPLICATION_JSON));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/{operation:start|stop|restart|kill}")
    public ApiResponse<Object> operateContainer(@PathVariable String nodeId, @PathVariable String containerId, @PathVariable String operation) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/" + operation, "", null));
    }

    @PutMapping("/api/ops/nodes/{nodeId}/containers/{containerId}")
    public ApiResponse<Object> updateContainer(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.PUT, "/api/containers/" + DaemonClient.enc(containerId), body, MediaType.APPLICATION_JSON));
    }

    @DeleteMapping("/api/ops/nodes/{nodeId}/containers/{containerId}")
    public ApiResponse<Object> deleteContainer(@PathVariable String nodeId, @PathVariable String containerId) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.DELETE, "/api/containers/" + DaemonClient.enc(containerId), "", null));
    }

    @GetMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/logs")
    public ApiResponse<Object> logs(@PathVariable String nodeId, @PathVariable String containerId, @RequestParam(defaultValue = "200") int tail) {
        int safeTail = Math.min(Math.max(tail, 20), 1000);
        return ApiResponse.success(daemon.call(nodes.get(nodeId), "/api/containers/" + DaemonClient.enc(containerId) + "/logs?tail=" + safeTail));
    }

    @GetMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files")
    public ApiResponse<Object> files(@PathVariable String nodeId, @PathVariable String containerId, @RequestParam(defaultValue = "/") String path, @RequestParam(defaultValue = "0") String download) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), containerPath(containerId, "files", mapOf("path", path, "download", "1".equals(download) ? "1" : ""))));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files")
    public ApiResponse<Object> fileAction(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/files", body, MediaType.APPLICATION_JSON));
    }

    @PutMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files")
    public ApiResponse<Object> renameFile(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.PUT, "/api/containers/" + DaemonClient.enc(containerId) + "/files", body, MediaType.APPLICATION_JSON));
    }

    @DeleteMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files")
    public ApiResponse<Object> deleteFile(@PathVariable String nodeId, @PathVariable String containerId, @RequestParam(defaultValue = "") String path) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.DELETE, containerPath(containerId, "files", mapOf("path", path)), "", null));
    }

    @PostMapping(value = "/api/ops/nodes/{nodeId}/containers/{containerId}/files/upload-chunk", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Object> uploadChunk(@PathVariable String nodeId, @PathVariable String containerId, HttpServletRequest request, @RequestBody byte[] body) {
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/files/upload-chunk" + query, body, MediaType.APPLICATION_OCTET_STREAM));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files/upload-cleanup")
    public ApiResponse<Object> uploadCleanup(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/files/upload-cleanup", body, MediaType.APPLICATION_JSON));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/containers/{containerId}/files/download-cancel")
    public ApiResponse<Object> downloadCancel(@PathVariable String nodeId, @PathVariable String containerId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(daemon.call(nodes.get(nodeId), HttpMethod.POST, "/api/containers/" + DaemonClient.enc(containerId) + "/files/download-cancel", body, MediaType.APPLICATION_JSON));
    }

    @RequestMapping(value = "/api/ops/nodes/{nodeId}/containers/{containerId}/files/download", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String nodeId, @PathVariable String containerId, @RequestParam(defaultValue = "/") String path, @RequestParam(defaultValue = "") String downloadId, HttpServletRequest servletRequest) {
        RemoteNode node = nodes.get(nodeId);
        URI url = UriComponentsBuilder.fromUriString(node.getDaemonUrl())
            .path("/api/containers/{containerId}/files/download")
            .queryParam("path", path)
            .queryParam("downloadId", downloadId)
            .buildAndExpand(containerId)
            .toUri();
        HttpHeaders requestHeaders = daemon.authHeaders(node);
        if (servletRequest.getHeader("Range") != null) {
            requestHeaders.set("Range", servletRequest.getHeader("Range"));
        }
        org.springframework.http.HttpEntity<Void> requestEntity = new org.springframework.http.HttpEntity<Void>(requestHeaders);
        ResponseEntity<byte[]> upstream = restTemplate.exchange(url, HttpMethod.valueOf(servletRequest.getMethod()), requestEntity, byte[].class);
        HttpHeaders headers = new HttpHeaders();
        for (String header : DOWNLOAD_HEADERS) {
            List<String> values = upstream.getHeaders().get(header);
            if (values != null) {
                headers.put(header, values);
            }
        }
        headers.setAccessControlExposeHeaders(DOWNLOAD_HEADERS);
        if ("HEAD".equalsIgnoreCase(servletRequest.getMethod())) {
            return ResponseEntity.status(upstream.getStatusCode()).headers(headers).body(output -> {});
        }
        byte[] bytes = upstream.getBody() == null ? new byte[0] : upstream.getBody();
        return ResponseEntity.status(upstream.getStatusCode()).headers(headers).body(output -> output.write(bytes));
    }

    @GetMapping("/api/ops/nodes/{nodeId}/vms")
    public ApiResponse<List<Map<String, Object>>> vms(@PathVariable String nodeId) {
        return ApiResponse.success(normalizer.parseVirshList(String.valueOf(daemon.call(nodes.get(nodeId), "/api/vms"))));
    }

    @PostMapping("/api/ops/nodes/{nodeId}/vms/{vmName}/{operation}")
    public ResponseEntity<ApiResponse<Void>> operateVm() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ApiResponse.failure(501, "Daemon 暂未支持虚拟机操作"));
    }

    private Map<String, Object> findPublicNode(String id) {
        for (Map<String, Object> node : nodes.publicNodes()) {
            if (id.equals(node.get("id"))) {
                return node;
            }
        }
        throw new IllegalArgumentException("Unknown remote node: " + id);
    }

    private Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private Map<String, String> mapOf(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private Map<String, String> mapOf(String key1, String value1, String key2, String value2) {
        Map<String, String> map = mapOf(key1, value1);
        map.put(key2, value2);
        return map;
    }

    private Map<String, Object> mapOfObject(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key1, value1);
        map.put(key2, value2);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? mapper.convertValue(value, MAP) : new LinkedHashMap<String, Object>();
    }

    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List ? mapper.convertValue(value, MAP_LIST) : new ArrayList<Map<String, Object>>();
    }

    private Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : 0;
    }

    private Map<String, Object> metricUsage(List<Double> values) {
        double total = values.size() > 0 ? values.get(0) : 0;
        double used = values.size() > 1 ? values.get(1) : 0;
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("totalMb", total);
        map.put("usedMb", used);
        map.put("totalGb", Math.round(total / 1024D * 100D) / 100D);
        map.put("usedGb", Math.round(used / 1024D * 100D) / 100D);
        map.put("percent", total > 0 ? Math.round((used / total) * 10000D) / 100D : 0);
        return map;
    }

    private Double parseCpuIdle(String cpuLine) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*id").matcher(cpuLine);
        return matcher.find() ? Math.round(Math.max(0, Math.min(100, 100 - Double.parseDouble(matcher.group(1)))) * 100D) / 100D : null;
    }

    private List<Double> splitNumbers(Object value) {
        List<Double> values = new ArrayList<Double>();
        String text = string(value).trim();
        if (text.isEmpty()) {
            return values;
        }
        for (String part : text.split("\\s+")) {
            values.add(number(part));
        }
        return values;
    }

    private double number(Object value) {
        try {
            return Double.parseDouble(string(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Map<String, Map<String, Object>> byAny(List<Map<String, Object>> rows, String... keys) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            for (String key : keys) {
                if (!string(row.get(key)).isEmpty()) {
                    result.put(string(row.get(key)), row);
                }
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> inspectByShortId(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.put(left(string(row.get("Id")), 12), row);
        }
        return result;
    }

    private Map<String, Map<String, Object>> byName(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            result.put(trimSlash(string(row.get("Name"))), row);
        }
        return result;
    }

    private Map<String, Object> firstMap(Map<String, Object> a, Map<String, Object> b) {
        return a == null || a.isEmpty() ? (b == null ? new LinkedHashMap<String, Object>() : b) : a;
    }

    private Object first(Object a, Object b) {
        return a == null || string(a).isEmpty() ? b : a;
    }

    private String pathString(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) {
                return "";
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return string(current);
    }

    private String left(String value, int size) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), size));
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceFirst("^/+", "");
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String containerPath(String containerId, String operation, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(
            UriComponentsBuilder.fromPath("/api/containers/{containerId}/{operation}").buildAndExpand(containerId, operation).toUriString()
        );
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                builder.queryParam(entry.getKey(), entry.getValue());
            }
        }
        return builder.build().encode().toUriString();
    }
}
