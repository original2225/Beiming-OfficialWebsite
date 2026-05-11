package dev.beiming.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@RestController
public class RemoteController {
  private final NodeService nodes;
  private final RestClient restClient;

  RemoteController(NodeService nodes, RestClient.Builder restClientBuilder) {
    this.nodes = nodes;
    this.restClient = restClientBuilder.build();
  }

  @GetMapping("/health")
  ApiEnvelope<Map<String, Object>> health() {
    return ApiEnvelope.ok(Map.of("service", "beiming-resource-service"));
  }

  @GetMapping("/api/docker/images/search")
  ApiEnvelope<Object> searchDockerImages(@RequestParam(defaultValue = "") String q) {
    if (q.trim().length() < 2) return ApiEnvelope.ok(List.of());
    var payload = restClient.get()
      .uri("https://hub.docker.com/v2/search/repositories/?page_size=8&query={query}", q.trim())
      .retrieve()
      .body(Map.class);
    return ApiEnvelope.ok(payload == null ? List.of() : payload.getOrDefault("results", List.of()));
  }

  @GetMapping("/api/nodes")
  ApiEnvelope<List<Map<String, Object>>> listNodes() {
    return ApiEnvelope.ok(nodes.publicNodes());
  }

  @PostMapping("/api/nodes")
  ApiEnvelope<Map<String, Object>> createNode(@RequestBody Map<String, Object> body) {
    var node = nodes.create(body);
    return ApiEnvelope.ok(nodes.publicNodes().stream().filter(item -> item.get("id").equals(node.id())).findFirst().orElseThrow());
  }

  @PutMapping("/api/nodes/{nodeId}")
  ApiEnvelope<Map<String, Object>> updateNode(@PathVariable String nodeId, @RequestBody Map<String, Object> body) {
    var node = nodes.upsert(nodeId, body);
    return ApiEnvelope.ok(nodes.publicNodes().stream().filter(item -> item.get("id").equals(node.id())).findFirst().orElseThrow());
  }

  @DeleteMapping("/api/nodes/{nodeId}")
  ApiEnvelope<Map<String, Object>> deleteNode(@PathVariable String nodeId) {
    nodes.delete(nodeId);
    return ApiEnvelope.ok(Map.of("nodeId", nodeId));
  }
}
