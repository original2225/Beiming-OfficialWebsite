package games.beiming.website.ops.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.ops.model.RemoteNode;
import java.net.URI;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

final class RealtimeSupport {
    static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() {};

    private RealtimeSupport() {
    }

    static void send(ObjectMapper mapper, WebSocketSession session, String event, Object data) {
        send(mapper, session, event, true, data, "");
    }

    static void sendError(ObjectMapper mapper, WebSocketSession session, String event, String message) {
        send(mapper, session, event, false, null, message);
    }

    static void send(ObjectMapper mapper, WebSocketSession session, String event, boolean ok, Object data, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Map<String, Object> packet = new LinkedHashMap<String, Object>();
        packet.put("event", event);
        packet.put("ok", ok);
        if (data != null) {
            packet.put("data", data);
        }
        if (message != null && !message.isEmpty()) {
            packet.put("message", message);
        }
        packet.put("timestamp", System.currentTimeMillis());
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(packet)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    static URI daemonWsUri(RemoteNode node) {
        String base = node.getDaemonUrl().replaceFirst("^http", "ws").replaceAll("/+$", "");
        String token = "";
        if (node.getDaemonToken() != null && !node.getDaemonToken().isEmpty()) {
            try {
                token = "?token=" + URLEncoder.encode(node.getDaemonToken(), "UTF-8");
            } catch (Exception ignored) {
                token = "";
            }
        }
        return URI.create(base + "/ws" + token);
    }

    static Object payloadData(Map<String, Object> payload) {
        return payload.containsKey("data") ? payload.get("data") : payload;
    }

    static boolean payloadOk(Map<String, Object> payload) {
        return !Boolean.FALSE.equals(payload.get("ok"));
    }

    static String payloadMessage(Map<String, Object> payload, String fallback) {
        Object message = payload.get("message");
        return message == null || String.valueOf(message).isEmpty() ? fallback : String.valueOf(message);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
