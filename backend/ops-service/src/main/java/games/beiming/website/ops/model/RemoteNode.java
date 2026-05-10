package games.beiming.website.ops.model;

public class RemoteNode {
    private String id;
    private String name;
    private String daemonUrl;
    private String daemonToken;

    public RemoteNode() {
    }

    public RemoteNode(String id, String name, String daemonUrl, String daemonToken) {
        this.id = trim(id);
        this.name = trim(name);
        this.daemonUrl = trim(daemonUrl).replaceAll("/+$", "");
        this.daemonToken = trim(daemonToken);
    }

    public RemoteNode normalized() {
        return new RemoteNode(id, name, daemonUrl, daemonToken);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDaemonUrl() {
        return daemonUrl;
    }

    public void setDaemonUrl(String daemonUrl) {
        this.daemonUrl = daemonUrl;
    }

    public String getDaemonToken() {
        return daemonToken;
    }

    public void setDaemonToken(String daemonToken) {
        this.daemonToken = daemonToken;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
