package games.beiming.website.ops.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ContainerNormalizer {
    private final Map<String, NetworkSample> networkSamples = new ConcurrentHashMap<String, NetworkSample>();

    public Map<String, Object> enrich(Map<String, Object> row, Map<String, Object> stats, Map<String, Object> inspect, String nodeId, Number swapUsedBytes) {
        Usage mem = parseUsagePair(string(stats.get("MemUsage")));
        Usage net = parseUsagePair(string(stats.get("NetIO")));
        Rate rate = calculateNetworkRate(nodeId, string(row.get("ID")), net.usedBytes, net.limitBytes);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", string(row.get("ID")));
        result.put("name", string(row.get("Names")));
        result.put("image", string(path(inspect, "Config", "Image"), row.get("Image")));
        result.put("status", string(row.get("Status")));
        result.put("state", string(path(inspect, "State", "Status"), row.get("State")));
        result.put("ports", string(row.get("Ports")));
        result.put("command", string(row.get("Command")));
        result.put("startedAt", string(path(inspect, "State", "StartedAt")));
        result.put("finishedAt", string(path(inspect, "State", "FinishedAt")));
        result.put("created", string(inspect.containsKey("Created") ? inspect.get("Created") : row.get("CreatedAt")));
        result.put("labels", map(path(inspect, "Config", "Labels")));

        Map<String, Object> statsOut = new LinkedHashMap<String, Object>();
        statsOut.put("cpuPercent", parseDockerPercent(string(stats.get("CPUPerc"))));
        statsOut.put("memoryPercent", parseDockerPercent(string(stats.get("MemPerc"))));
        statsOut.put("memoryUsedBytes", mem.usedBytes);
        statsOut.put("memoryLimitBytes", mem.limitBytes);
        statsOut.put("swapUsedBytes", swapUsedBytes == null ? 0L : swapUsedBytes.longValue());
        statsOut.put("networkRxBytes", net.usedBytes);
        statsOut.put("networkTxBytes", net.limitBytes);
        statsOut.put("networkDownloadBps", rate.downloadBps);
        statsOut.put("networkUploadBps", rate.uploadBps);
        result.put("stats", statsOut);

        Map<String, Object> network = new LinkedHashMap<String, Object>();
        network.put("mode", string(path(inspect, "HostConfig", "NetworkMode"), "default"));
        network.put("ports", normalizePorts(map(path(inspect, "NetworkSettings", "Ports"))));
        result.put("network", network);

        Map<String, Object> labels = map(path(inspect, "Config", "Labels"));
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("env", list(path(inspect, "Config", "Env")));
        config.put("mounts", normalizeMounts(inspect));
        config.put("privileged", bool(path(inspect, "HostConfig", "Privileged")));
        config.put("workingDir", string(path(inspect, "Config", "WorkingDir")));
        config.put("command", join(list(path(inspect, "Config", "Cmd"))));
        double nanoCpus = number(path(inspect, "HostConfig", "NanoCpus"));
        config.put("cpuLimit", nanoCpus == 0 ? "" : Math.round((nanoCpus / 1000000000D) * 100D) / 100D);
        config.put("memoryLimit", number(path(inspect, "HostConfig", "Memory")));
        config.put("networkDownloadLimit", string(labels.get("beiming.net.download")));
        config.put("networkUploadLimit", string(labels.get("beiming.net.upload")));
        config.put("stdinOpen", bool(path(inspect, "Config", "OpenStdin")));
        config.put("tty", bool(path(inspect, "Config", "Tty")));
        result.put("config", config);

        result.put("restartPolicy", string(path(inspect, "HostConfig", "RestartPolicy", "Name"), "no"));
        Map<String, Object> terminal = new LinkedHashMap<String, Object>();
        terminal.put("attachStdin", bool(path(inspect, "Config", "AttachStdin")));
        terminal.put("attachStdout", bool(path(inspect, "Config", "AttachStdout")));
        terminal.put("attachStderr", bool(path(inspect, "Config", "AttachStderr")));
        terminal.put("openStdin", bool(path(inspect, "Config", "OpenStdin")));
        terminal.put("tty", bool(path(inspect, "Config", "Tty")));
        terminal.put("interactive", bool(path(inspect, "Config", "OpenStdin")) || bool(path(inspect, "Config", "Tty")) || bool(path(inspect, "Config", "AttachStdin")));
        result.put("terminal", terminal);
        return result;
    }

    public Map<String, Object> normalizeImage(Map<String, Object> row) {
        String repo = string(row.get("Repository"));
        String tag = string(row.get("Tag"), "latest");
        Map<String, Object> image = new LinkedHashMap<String, Object>();
        image.put("id", string(row.get("ID")));
        image.put("name", !repo.isEmpty() && !tag.isEmpty() && !"<none>".equals(tag) ? repo + ":" + tag : repo);
        image.put("repository", repo);
        image.put("tag", tag);
        image.put("size", string(row.get("Size")));
        image.put("createdSince", string(row.get("CreatedSince")));
        return image;
    }

    public List<Map<String, Object>> parseVirshList(String text) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (text == null) {
            return rows;
        }
        String[] lines = text.split("\\R");
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s{2,}", 3);
            if (parts.length < 3) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", "-".equals(parts[0]) ? null : parts[0]);
            row.put("name", parts[1].trim());
            row.put("state", parts[2].trim());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> normalizePorts(Map<String, Object> ports) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Object> entry : ports.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("containerPort", entry.getKey());
            List<?> bindings = list(entry.getValue());
            if (bindings.isEmpty()) {
                item.put("host", "");
            } else {
                List<String> hosts = new ArrayList<String>();
                for (Object binding : bindings) {
                    Map<String, Object> map = map(binding);
                    hosts.add(string(map.get("HostIp"), "0.0.0.0") + ":" + string(map.get("HostPort")));
                }
                item.put("host", String.join(", ", hosts));
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> normalizeMounts(Map<String, Object> inspect) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object bind : list(path(inspect, "HostConfig", "Binds"))) {
            String spec = String.valueOf(bind);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("spec", spec);
            item.put("type", spec.split(":", 2)[0].startsWith("/") ? "bind" : "volume");
            result.add(item);
        }
        for (Object rawMount : list(inspect.get("Mounts"))) {
            Map<String, Object> mount = map(rawMount);
            String type = "volume".equals(string(mount.get("Type"))) ? "volume" : "bind";
            String source = "volume".equals(type) ? string(mount.get("Name")) : string(mount.get("Source"));
            String target = string(mount.get("Destination"));
            String mode = Boolean.FALSE.equals(mount.get("RW")) ? "ro" : "rw";
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("spec", source + ":" + target + ":" + mode);
            item.put("type", type);
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private Rate calculateNetworkRate(String nodeId, String containerId, long rxBytes, long txBytes) {
        String key = nodeId + ":" + containerId;
        long now = System.currentTimeMillis();
        NetworkSample previous = networkSamples.put(key, new NetworkSample(rxBytes, txBytes, now));
        if (previous == null) {
            return new Rate(0, 0);
        }
        double seconds = Math.max((now - previous.now) / 1000D, 0.001D);
        return new Rate(Math.max(0, Math.round((rxBytes - previous.rxBytes) / seconds)), Math.max(0, Math.round((txBytes - previous.txBytes) / seconds)));
    }

    private double parseDockerPercent(String value) {
        try {
            return Math.round(Double.parseDouble(value.replace("%", "")) * 100D) / 100D;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Usage parseUsagePair(String value) {
        String[] parts = value.split("/", 2);
        return new Usage(parseDockerSize(parts.length > 0 ? parts[0].trim() : ""), parseDockerSize(parts.length > 1 ? parts[1].trim() : ""));
    }

    private long parseDockerSize(String value) {
        String trimmed = value == null ? "" : value.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([\\d.]+)\\s*([kmgt]?i?b|b)?$").matcher(trimmed);
        if (!matcher.find()) {
            return 0;
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "b" : matcher.group(2);
        double scale = 1D;
        if ("kb".equals(unit) || "kib".equals(unit)) scale = 1024D;
        else if ("mb".equals(unit) || "mib".equals(unit)) scale = Math.pow(1024, 2);
        else if ("gb".equals(unit) || "gib".equals(unit)) scale = Math.pow(1024, 3);
        else if ("tb".equals(unit) || "tib".equals(unit)) scale = Math.pow(1024, 4);
        return Math.round(amount * scale);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : new ArrayList<Object>();
    }

    private Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String string(Object value, Object fallback) {
        String text = string(value);
        return text.isEmpty() ? string(fallback) : text;
    }

    private String join(List<?> values) {
        List<String> texts = new ArrayList<String>();
        for (Object value : values) {
            texts.add(String.valueOf(value));
        }
        return String.join(" ", texts);
    }

    private static class Usage {
        private final long usedBytes;
        private final long limitBytes;

        private Usage(long usedBytes, long limitBytes) {
            this.usedBytes = usedBytes;
            this.limitBytes = limitBytes;
        }
    }

    private static class Rate {
        private final long downloadBps;
        private final long uploadBps;

        private Rate(long downloadBps, long uploadBps) {
            this.downloadBps = downloadBps;
            this.uploadBps = uploadBps;
        }
    }

    private static class NetworkSample {
        private final long rxBytes;
        private final long txBytes;
        private final long now;

        private NetworkSample(long rxBytes, long txBytes, long now) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.now = now;
        }
    }
}
