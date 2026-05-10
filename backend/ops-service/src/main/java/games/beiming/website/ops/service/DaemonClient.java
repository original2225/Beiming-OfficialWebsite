package games.beiming.website.ops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.beiming.website.ops.model.RemoteNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class DaemonClient {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() {};

    private final ObjectMapper mapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public DaemonClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Object call(RemoteNode node, String path) {
        return call(node, HttpMethod.GET, path, null, null);
    }

    public Object call(RemoteNode node, HttpMethod method, String path, Object body, MediaType contentType) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (node.getDaemonToken() != null && !node.getDaemonToken().isEmpty()) {
            headers.setBearerAuth(node.getDaemonToken());
        }
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        org.springframework.http.HttpEntity<Object> entity = new org.springframework.http.HttpEntity<Object>(body == null ? "" : body, headers);
        org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(node.getDaemonUrl() + path, method, entity, String.class);
        String text = response.getBody() == null ? "" : response.getBody();
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(text, MAP);
        } catch (Exception error) {
            throw new IllegalStateException(text.isEmpty() ? response.getStatusCode().toString() : text);
        }
        if (!response.getStatusCode().is2xxSuccessful() || Boolean.FALSE.equals(payload.get("ok"))) {
            throw new IllegalStateException(String.valueOf(payload.getOrDefault("message", "Daemon request failed")));
        }
        return payload.containsKey("data") ? payload.get("data") : payload;
    }

    public HttpHeaders authHeaders(RemoteNode node) {
        HttpHeaders headers = new HttpHeaders();
        if (node.getDaemonToken() != null && !node.getDaemonToken().isEmpty()) {
            headers.setBearerAuth(node.getDaemonToken());
        }
        return headers;
    }

    public static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception ignored) {
            return "";
        }
    }
}
