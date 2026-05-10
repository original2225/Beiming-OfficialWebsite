package games.beiming.website.ops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.ops.model.RemoteNode;
import games.beiming.website.ops.service.DaemonClient;
import games.beiming.website.ops.service.NodeService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ContainerTerminalWebSocketHandler extends TextWebSocketHandler {
    private static final Pattern BLOCKED_COMMAND = Pattern.compile(".*\\b(reboot|shutdown|poweroff|mkfs|dd\\s+if=|rm\\s+-rf\\s+/).*");

    private final NodeService nodes;
    private final DaemonClient daemonClient;
    private final ContainerReplayStore replayStore;
    private final ObjectMapper mapper;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<String, TerminalSession>();

    public ContainerTerminalWebSocketHandler(NodeService nodes, DaemonClient daemonClient, ContainerReplayStore replayStore, ObjectMapper mapper) {
        this.nodes = nodes;
        this.daemonClient = daemonClient;
        this.replayStore = replayStore;
        this.mapper = mapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) throws Exception {
        Map<String, Object> packet = mapper.readValue(message.getPayload(), RealtimeSupport.MAP);
        String event = RealtimeSupport.string(packet.get("event"));
        Map<String, Object> data = RealtimeSupport.map(packet.get("data"));
        if ("container/terminal/open".equals(event)) {
            open(browser, data);
            return;
        }
        if ("container/input".equals(event)) {
            TerminalSession session = sessions.get(browser.getId());
            if (session == null) {
                return;
            }
            String command = RealtimeSupport.string(data.get("command")).trim();
            if (command.isEmpty()) {
                return;
            }
            if (BLOCKED_COMMAND.matcher(command).matches()) {
                session.emitInputError("Command is blocked by safety policy");
                return;
            }
            session.sendInput(command + "\n");
            return;
        }
        if ("container/terminal/clear".equals(event)) {
            RealtimeSupport.send(mapper, browser, "container/terminal/clear", new LinkedHashMap<String, Object>());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        closeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        RealtimeSupport.sendError(mapper, session, "container/terminal/error", exception.getMessage());
        closeSession(session);
    }

    private void open(WebSocketSession browser, Map<String, Object> data) {
        try {
            closeSession(browser);
            RemoteNode node = nodes.get(RealtimeSupport.string(data.get("nodeId")));
            String containerId = RealtimeSupport.string(data.get("containerId")).trim();
            if (containerId.isEmpty()) {
                RealtimeSupport.sendError(mapper, browser, "container/terminal/error", "Container id is required");
                return;
            }
            int tail = clampInt(data.get("tail"), 220, 20, 5000);
            TerminalSession session = new TerminalSession(browser, node, containerId, tail);
            sessions.put(browser.getId(), session);
            session.open();
        } catch (Exception error) {
            RealtimeSupport.sendError(mapper, browser, "container/terminal/error", error.getMessage());
        }
    }

    private void closeSession(WebSocketSession browser) throws Exception {
        TerminalSession session = sessions.remove(browser.getId());
        if (session != null) {
            session.close();
        }
    }

    private int clampInt(Object value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(RealtimeSupport.string(value))));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private class TerminalSession {
        private final WebSocketSession browser;
        private final RemoteNode node;
        private final String containerId;
        private final int tail;
        private WebSocketSession daemon;
        private String sessionId = "unknown";
        private String mode = "log-stream";

        TerminalSession(WebSocketSession browser, RemoteNode node, String containerId, int tail) {
            this.browser = browser;
            this.node = node;
            this.containerId = containerId;
            this.tail = tail;
        }

        void open() throws Exception {
            daemon = client.doHandshake(new DaemonTerminalHandler(this), new WebSocketHttpHeaders(), RealtimeSupport.daemonWsUri(node)).get(10, TimeUnit.SECONDS);
            sendDaemon("container/attach", mapOf("containerId", containerId));
        }

        void close() throws Exception {
            if (daemon != null && daemon.isOpen()) {
                daemon.close(CloseStatus.NORMAL);
            }
        }

        void sendInput(String input) throws Exception {
            if (!"attach".equals(mode) || daemon == null || !daemon.isOpen()) {
                return;
            }
            sendDaemon("container/input", mapOf("input", input));
        }

        void emitInputError(String message) {
            String text = "\r\n" + message + "\r\n";
            replayStore.append(node.getId(), containerId, sessionId, text);
            RealtimeSupport.send(mapper, browser, "container/stdout", mapOf("text", text));
        }

        void handleAttach(Map<String, Object> payload) {
            Map<String, Object> data = RealtimeSupport.map(RealtimeSupport.payloadData(payload));
            boolean ok = RealtimeSupport.payloadOk(payload);
            if (!ok && (data.containsKey("id") || data.containsKey("sessionId"))) {
                enterLogMode(data);
                return;
            }
            if (!ok) {
                RealtimeSupport.send(mapper, browser, "container/terminal/ready", readyPayload("log-stream", "", false));
                fetchStartupReplay();
                return;
            }
            sessionId = sessionId(data);
            mode = Boolean.TRUE.equals(data.get("interactive")) ? "attach" : "log-stream";
            String replay = replayStore.read(node.getId(), containerId, sessionId);
            RealtimeSupport.send(mapper, browser, "container/terminal/ready", readyPayload(mode, replay, Boolean.TRUE.equals(data.get("interactive"))));
            if (replay.isEmpty()) {
                fetchStartupReplay();
            }
        }

        void handleStdout(Map<String, Object> payload) {
            Map<String, Object> data = RealtimeSupport.map(RealtimeSupport.payloadData(payload));
            String text = RealtimeSupport.string(data.get("text"));
            ContainerReplayStore.ReplayUpdate update = replayStore.append(node.getId(), containerId, sessionId, text);
            if (update.isReplace()) {
                RealtimeSupport.send(mapper, browser, "container/terminal/replay", replayPayload(update));
            } else if (!update.getDelta().isEmpty()) {
                RealtimeSupport.send(mapper, browser, "container/stdout", mapOf("text", update.getDelta()));
            }
        }

        void enterLogMode(Map<String, Object> meta) {
            sessionId = sessionId(meta);
            mode = "log-stream";
            String replay = replayStore.read(node.getId(), containerId, sessionId);
            RealtimeSupport.send(mapper, browser, "container/terminal/ready", readyPayload(mode, replay, false));
            if (replay.isEmpty()) {
                fetchStartupReplay();
            }
        }

        void fetchStartupReplay() {
            try {
                Object raw = daemonClient.call(node, HttpMethod.GET, "/api/containers/" + DaemonClient.enc(containerId) + "/logs?tail=" + tail + "&sinceStart=1", null, null);
                ContainerReplayStore.ReplayUpdate update = replayStore.append(node.getId(), containerId, sessionId, RealtimeSupport.string(raw), true);
                if (update.isReplace() || !update.getDelta().isEmpty()) {
                    RealtimeSupport.send(mapper, browser, "container/terminal/replay", replayPayload(update));
                }
            } catch (Exception ignored) {
            }
        }

        void sendDaemon(String event, Object data) throws Exception {
            Map<String, Object> packet = new LinkedHashMap<String, Object>();
            packet.put("event", event);
            packet.put("data", data);
            synchronized (daemon) {
                daemon.sendMessage(new TextMessage(mapper.writeValueAsString(packet)));
            }
        }

        private Map<String, Object> readyPayload(String mode, String replay, boolean interactive) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("mode", mode);
            payload.put("containerId", containerId);
            payload.put("replay", replay);
            payload.put("replayLimit", replayStore.limit());
            payload.put("interactive", interactive);
            return payload;
        }

        private Map<String, Object> replayPayload(ContainerReplayStore.ReplayUpdate update) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("text", update.getText());
            payload.put("delta", update.getDelta());
            payload.put("replace", update.isReplace());
            return payload;
        }

        private String sessionId(Map<String, Object> meta) {
            String id = RealtimeSupport.string(meta.get("sessionId"));
            if (id.isEmpty()) id = RealtimeSupport.string(meta.get("startedAt"));
            if (id.isEmpty()) id = RealtimeSupport.string(meta.get("id"));
            return id.isEmpty() ? containerId : id;
        }
    }

    private class DaemonTerminalHandler extends TextWebSocketHandler {
        private final TerminalSession terminal;

        DaemonTerminalHandler(TerminalSession terminal) {
            this.terminal = terminal;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            Map<String, Object> packet = mapper.readValue(message.getPayload(), RealtimeSupport.MAP);
            String event = RealtimeSupport.string(packet.get("event"));
            Map<String, Object> payload = RealtimeSupport.map(packet.get("payload"));
            if ("container/attach".equals(event)) {
                terminal.handleAttach(payload);
            } else if ("container/stdout".equals(event)) {
                terminal.handleStdout(payload);
            } else if ("container/closed".equals(event)) {
                RealtimeSupport.send(mapper, terminal.browser, "container/terminal/closed", new LinkedHashMap<String, Object>());
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            RealtimeSupport.sendError(mapper, terminal.browser, "container/terminal/error", exception.getMessage());
        }
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }
}
