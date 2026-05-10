package games.beiming.website.ops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.ops.model.RemoteNode;
import games.beiming.website.ops.service.NodeService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ContainerCreateWebSocketHandler extends TextWebSocketHandler {
    private final NodeService nodes;
    private final ObjectMapper mapper;
    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final Map<String, WebSocketSession> daemonSessions = new ConcurrentHashMap<String, WebSocketSession>();

    public ContainerCreateWebSocketHandler(NodeService nodes, ObjectMapper mapper) {
        this.nodes = nodes;
        this.mapper = mapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession browser, TextMessage message) throws Exception {
        Map<String, Object> packet = mapper.readValue(message.getPayload(), RealtimeSupport.MAP);
        String event = RealtimeSupport.string(packet.get("event"));
        if (!"container/create/open".equals(event)) {
            return;
        }
        Map<String, Object> data = RealtimeSupport.map(packet.get("data"));
        RemoteNode node = nodes.get(RealtimeSupport.string(data.get("nodeId")));
        Map<String, Object> config = RealtimeSupport.map(data.get("config"));
        WebSocketSession daemon = client
            .doHandshake(new DaemonCreateHandler(browser), new WebSocketHttpHeaders(), RealtimeSupport.daemonWsUri(node))
            .get(10, TimeUnit.SECONDS);
        daemonSessions.put(browser.getId(), daemon);
        sendDaemon(daemon, "container/create", config);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        closeDaemon(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        RealtimeSupport.sendError(mapper, session, "container/create/error", exception.getMessage());
        closeDaemon(session);
    }

    private void closeDaemon(WebSocketSession browser) throws Exception {
        WebSocketSession daemon = daemonSessions.remove(browser.getId());
        if (daemon != null && daemon.isOpen()) {
            daemon.close(CloseStatus.NORMAL);
        }
    }

    private void sendDaemon(WebSocketSession daemon, String event, Object data) throws Exception {
        Map<String, Object> packet = new java.util.LinkedHashMap<String, Object>();
        packet.put("event", event);
        packet.put("data", data);
        synchronized (daemon) {
            daemon.sendMessage(new TextMessage(mapper.writeValueAsString(packet)));
        }
    }

    private class DaemonCreateHandler extends TextWebSocketHandler {
        private final WebSocketSession browser;

        DaemonCreateHandler(WebSocketSession browser) {
            this.browser = browser;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            Map<String, Object> packet = mapper.readValue(message.getPayload(), RealtimeSupport.MAP);
            String event = RealtimeSupport.string(packet.get("event"));
            Map<String, Object> payload = RealtimeSupport.map(packet.get("payload"));
            if ("container/create/progress".equals(event)) {
                RealtimeSupport.send(mapper, browser, event, payload);
            } else if ("container/create/done".equals(event)) {
                RealtimeSupport.send(mapper, browser, event, RealtimeSupport.payloadData(payload));
            } else if ("container/create/error".equals(event)) {
                RealtimeSupport.send(mapper, browser, event, false, RealtimeSupport.payloadData(payload), RealtimeSupport.payloadMessage(payload, "Create container failed"));
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            RealtimeSupport.sendError(mapper, browser, "container/create/error", exception.getMessage());
        }
    }
}
