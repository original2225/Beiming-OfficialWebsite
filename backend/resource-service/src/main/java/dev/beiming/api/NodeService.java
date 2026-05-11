package dev.beiming.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NodeService {
  private static final TypeReference<List<RemoteNode>> NODE_LIST = new TypeReference<>() {};

  private final ObjectMapper mapper;
  private final Path configPath;
  private List<RemoteNode> nodes;

  NodeService(ObjectMapper mapper, @Value("${beiming.data-dir}") String dataDir) {
    this.mapper = mapper;
    this.configPath = Path.of(dataDir).resolve("remote-nodes.json");
  }

  synchronized List<RemoteNode> nodes() {
    if (nodes != null) return nodes;
    nodes = readStoredNodes();
    if (nodes.isEmpty()) {
      nodes = readEnvNodes();
    }
    if (nodes.isEmpty()) {
      nodes = new ArrayList<>(List.of(new RemoteNode("amd-9950x", "本机 Daemon", "http://127.0.0.1:8790", "")));
    }
    return nodes;
  }

  List<Map<String, Object>> publicNodes() {
    return nodes().stream().map(node -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", node.id());
      item.put("name", node.name());
      item.put("daemonUrl", node.daemonUrl());
      item.put("daemonToken", node.daemonToken());
      item.put("auth", "daemon");
      item.put("hasDaemonToken", node.daemonToken() != null && !node.daemonToken().isBlank());
      return item;
    }).toList();
  }

  RemoteNode get(String nodeId) {
    return nodes().stream()
      .filter(node -> node.id().equals(nodeId))
      .findFirst()
      .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown remote node: " + nodeId));
  }

  synchronized RemoteNode create(Map<String, Object> patch) {
    var requestedId = string(patch.get("id")).trim();
    if (!requestedId.isBlank() && !requestedId.matches("^[a-zA-Z0-9_-]+$")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "节点 ID 只能包含字母、数字、下划线和短横线");
    }
    var id = requestedId.isBlank() ? "node-" + UUID.randomUUID().toString().substring(0, 8) : requestedId;
    if (nodes().stream().anyMatch(node -> node.id().equals(id))) {
      throw new ApiException(HttpStatus.CONFLICT, "Remote node already exists: " + id);
    }
    return upsert(id, patch);
  }

  synchronized RemoteNode upsert(String nodeId, Map<String, Object> patch) {
    var list = nodes();
    var index = -1;
    RemoteNode previous = null;
    for (var i = 0; i < list.size(); i++) {
      if (list.get(i).id().equals(nodeId)) {
        index = i;
        previous = list.get(i);
        break;
      }
    }
    var next = new RemoteNode(
      nodeId,
      string(patch.getOrDefault("name", previous == null ? "" : previous.name())),
      string(patch.getOrDefault("daemonUrl", previous == null ? "" : previous.daemonUrl())),
      string(patch.getOrDefault("daemonToken", previous == null ? "" : previous.daemonToken()))
    ).normalized();
    if (next.daemonToken().isBlank() && previous != null) {
      next = new RemoteNode(next.id(), next.name(), next.daemonUrl(), previous.daemonToken()).normalized();
    }
    validate(next);
    if (index >= 0) list.set(index, next);
    else list.add(next);
    writeStoredNodes(list);
    return next;
  }

  synchronized void delete(String nodeId) {
    var before = nodes().size();
    nodes = new ArrayList<>(nodes().stream().filter(node -> !node.id().equals(nodeId)).toList());
    if (nodes.size() == before) {
      throw new ApiException(HttpStatus.NOT_FOUND, "Unknown remote node: " + nodeId);
    }
    writeStoredNodes(nodes);
  }

  private List<RemoteNode> readStoredNodes() {
    if (!Files.exists(configPath)) return new ArrayList<>();
    try {
      return new ArrayList<>(mapper.readValue(Files.readString(configPath), NODE_LIST).stream().map(RemoteNode::normalized).toList());
    } catch (IOException error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }
  }

  private List<RemoteNode> readEnvNodes() {
    var raw = System.getenv("REMOTE_NODES_JSON");
    if (raw == null || raw.isBlank()) return new ArrayList<>();
    try {
      return new ArrayList<>(mapper.readValue(raw, NODE_LIST).stream().map(RemoteNode::normalized).toList());
    } catch (IOException error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }
  }

  private void writeStoredNodes(List<RemoteNode> value) {
    try {
      Files.createDirectories(configPath.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), value);
    } catch (IOException error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
    }
  }

  private void validate(RemoteNode node) {
    if (node.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "节点名称不能为空");
    if (node.daemonUrl().isBlank() || !(node.daemonUrl().startsWith("http://") || node.daemonUrl().startsWith("https://"))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Daemon 地址必须是有效的 http(s) URL");
    }
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
