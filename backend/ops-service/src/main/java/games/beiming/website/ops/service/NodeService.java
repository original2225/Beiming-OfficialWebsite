package games.beiming.website.ops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.ops.model.RemoteNode;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NodeService {
    private static final TypeReference<List<RemoteNode>> NODE_LIST = new TypeReference<List<RemoteNode>>() {};

    private final ObjectMapper mapper;
    private final File configFile;
    private List<RemoteNode> nodes;

    public NodeService(ObjectMapper mapper, @Value("${beiming.ops.data-dir}") String dataDir) {
        this.mapper = mapper;
        this.configFile = new File(dataDir, "remote-nodes.json");
    }

    public synchronized List<RemoteNode> nodes() {
        if (nodes != null) {
            return nodes;
        }
        nodes = readStoredNodes();
        if (nodes.isEmpty()) {
            nodes = readEnvNodes();
        }
        if (nodes.isEmpty()) {
            nodes = new ArrayList<RemoteNode>();
            nodes.add(new RemoteNode("primary-node", "Primary Docker Node", "http://127.0.0.1:8790", ""));
        }
        return nodes;
    }

    public List<Map<String, Object>> publicNodes() {
        return nodes().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("daemonUrl", node.getDaemonUrl());
            item.put("auth", "daemon");
            item.put("hasDaemonToken", node.getDaemonToken() != null && !node.getDaemonToken().isEmpty());
            return item;
        }).collect(Collectors.toList());
    }

    public RemoteNode get(String nodeId) {
        for (RemoteNode node : nodes()) {
            if (node.getId().equals(nodeId)) {
                return node;
            }
        }
        throw new IllegalArgumentException("Unknown remote node: " + nodeId);
    }

    public synchronized RemoteNode create(Map<String, Object> patch) {
        String requestedId = string(patch.get("id")).trim();
        if (!requestedId.isEmpty() && !requestedId.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("节点 ID 只能包含字母、数字、下划线和短横线");
        }
        String id = requestedId.isEmpty() ? "node-" + UUID.randomUUID().toString().substring(0, 8) : requestedId;
        for (RemoteNode node : nodes()) {
            if (node.getId().equals(id)) {
                throw new IllegalArgumentException("Remote node already exists: " + id);
            }
        }
        return upsert(id, patch);
    }

    public synchronized RemoteNode upsert(String nodeId, Map<String, Object> patch) {
        List<RemoteNode> list = nodes();
        int index = -1;
        RemoteNode previous = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(nodeId)) {
                index = i;
                previous = list.get(i);
                break;
            }
        }
        RemoteNode next = new RemoteNode(
            nodeId,
            string(patch.containsKey("name") ? patch.get("name") : previous == null ? "" : previous.getName()),
            string(patch.containsKey("daemonUrl") ? patch.get("daemonUrl") : previous == null ? "" : previous.getDaemonUrl()),
            string(patch.containsKey("daemonToken") ? patch.get("daemonToken") : previous == null ? "" : previous.getDaemonToken())
        ).normalized();
        if ((next.getDaemonToken() == null || next.getDaemonToken().isEmpty()) && previous != null) {
            next.setDaemonToken(previous.getDaemonToken());
        }
        validate(next);
        if (index >= 0) {
            list.set(index, next);
        } else {
            list.add(next);
        }
        writeStoredNodes(list);
        return next;
    }

    public synchronized void delete(String nodeId) {
        List<RemoteNode> next = nodes().stream().filter(node -> !node.getId().equals(nodeId)).collect(Collectors.toList());
        if (next.size() == nodes().size()) {
            throw new IllegalArgumentException("Unknown remote node: " + nodeId);
        }
        nodes = new ArrayList<RemoteNode>(next);
        writeStoredNodes(nodes);
    }

    private List<RemoteNode> readStoredNodes() {
        if (!configFile.exists()) {
            return new ArrayList<RemoteNode>();
        }
        try {
            return mapper.readValue(configFile, NODE_LIST).stream().map(RemoteNode::normalized).collect(Collectors.toList());
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private List<RemoteNode> readEnvNodes() {
        String raw = System.getenv("REMOTE_NODES_JSON");
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<RemoteNode>();
        }
        try {
            return mapper.readValue(raw, NODE_LIST).stream().map(RemoteNode::normalized).collect(Collectors.toList());
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private void writeStoredNodes(List<RemoteNode> value) {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create data dir: " + parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, value);
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private void validate(RemoteNode node) {
        if (node.getName() == null || node.getName().isEmpty()) {
            throw new IllegalArgumentException("节点名称不能为空");
        }
        if (node.getDaemonUrl() == null || !(node.getDaemonUrl().startsWith("http://") || node.getDaemonUrl().startsWith("https://"))) {
            throw new IllegalArgumentException("Daemon 地址必须是有效的 http(s) URL");
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
