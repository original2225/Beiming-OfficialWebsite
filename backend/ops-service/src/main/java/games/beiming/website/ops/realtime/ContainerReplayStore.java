package games.beiming.website.ops.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ContainerReplayStore {
    private static final int REPLAY_LIMIT = 120000;
    private static final int OVERLAP_LIMIT = 20000;
    private final Map<String, String> cache = new ConcurrentHashMap<String, String>();

    public int limit() {
        return REPLAY_LIMIT;
    }

    public String read(String nodeId, String containerId, String sessionId) {
        String value = cache.get(key(nodeId, containerId, sessionId));
        return value == null ? "" : value;
    }

    public ReplayUpdate append(String nodeId, String containerId, String sessionId, String text) {
        return append(nodeId, containerId, sessionId, text, false);
    }

    public ReplayUpdate append(String nodeId, String containerId, String sessionId, String text, boolean authoritative) {
        String normalized = normalize(text);
        String key = key(nodeId, containerId, sessionId);
        String previous = cache.containsKey(key) ? cache.get(key) : "";
        ReplayUpdate update = merge(previous, normalized, authoritative);
        cache.put(key, right(update.getText(), REPLAY_LIMIT));
        return update;
    }

    private ReplayUpdate merge(String previous, String next, boolean authoritative) {
        if (next.isEmpty()) {
            return new ReplayUpdate(previous, "", false);
        }
        if (previous.isEmpty()) {
            return new ReplayUpdate(right(next, REPLAY_LIMIT), next, false);
        }
        if (previous.equals(next) || previous.endsWith(next)) {
            return new ReplayUpdate(previous, "", false);
        }
        if (authoritative && next.contains(previous)) {
            return new ReplayUpdate(right(next, REPLAY_LIMIT), next, true);
        }
        if (next.startsWith(previous)) {
            return new ReplayUpdate(right(next, REPLAY_LIMIT), next.substring(previous.length()), false);
        }
        int overlap = overlap(previous, next);
        if (overlap > 0) {
            String delta = next.substring(overlap);
            return new ReplayUpdate(right(previous + delta, REPLAY_LIMIT), delta, false);
        }
        return new ReplayUpdate(right(previous + next, REPLAY_LIMIT), next, false);
    }

    private int overlap(String left, String right) {
        int max = Math.min(Math.min(left.length(), right.length()), OVERLAP_LIMIT);
        for (int size = max; size > 0; size--) {
            if (left.regionMatches(left.length() - size, right, 0, size)) {
                return size;
            }
        }
        return 0;
    }

    private String key(String nodeId, String containerId, String sessionId) {
        return nodeId + ":" + containerId + ":" + (sessionId == null || sessionId.isEmpty() ? "unknown" : sessionId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String right(String value, int size) {
        return value.length() <= size ? value : value.substring(value.length() - size);
    }

    public static class ReplayUpdate {
        private final String text;
        private final String delta;
        private final boolean replace;

        ReplayUpdate(String text, String delta, boolean replace) {
            this.text = text;
            this.delta = delta;
            this.replace = replace;
        }

        public String getText() {
            return text;
        }

        public String getDelta() {
            return delta;
        }

        public boolean isReplace() {
            return replace;
        }
    }
}
