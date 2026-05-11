package dev.beiming.api;

public record RemoteNode(String id, String name, String daemonUrl, String daemonToken) {
  public RemoteNode normalized() {
    var url = daemonUrl == null ? "" : daemonUrl.trim().replaceAll("/+$", "");
    return new RemoteNode(
      id == null ? "" : id.trim(),
      name == null ? "" : name.trim(),
      url,
      daemonToken == null ? "" : daemonToken.trim()
    );
  }
}
