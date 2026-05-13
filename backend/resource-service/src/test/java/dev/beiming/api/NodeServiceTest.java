package dev.beiming.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeServiceTest {
  @TempDir
  Path dataDir;

  @Test
  void publicNodesDoNotExposeDaemonToken() {
    var nodes = service();

    nodes.create(Map.of(
      "id", "north",
      "name", "North Node",
      "daemonUrl", "http://127.0.0.1:8790/",
      "daemonToken", "secret-token"
    ));

    var node = nodes.publicNodes().stream()
      .filter(item -> item.get("id").equals("north"))
      .findFirst()
      .orElseThrow();
    assertThat(node).doesNotContainKey("daemonToken");
    assertThat(node.get("hasDaemonToken")).isEqualTo(true);
  }

  @Test
  void blankDaemonTokenUpdateKeepsExistingToken() {
    var nodes = service();
    nodes.create(Map.of(
      "id", "north",
      "name", "North Node",
      "daemonUrl", "http://127.0.0.1:8790",
      "daemonToken", "secret-token"
    ));

    nodes.upsert("north", Map.of(
      "name", "Renamed Node",
      "daemonUrl", "http://127.0.0.1:8791",
      "daemonToken", ""
    ));

    assertThat(nodes.get("north").daemonToken()).isEqualTo("secret-token");
    assertThat(nodes.get("north").name()).isEqualTo("Renamed Node");
    assertThat(nodes.get("north").daemonUrl()).isEqualTo("http://127.0.0.1:8791");
  }

  @Test
  void invalidNodeUrlIsRejected() {
    var nodes = service();

    assertThatThrownBy(() -> nodes.create(Map.of(
        "id", "bad",
        "name", "Bad Node",
        "daemonUrl", "ftp://127.0.0.1",
        "daemonToken", "secret-token"
      )))
      .isInstanceOf(ApiException.class)
      .extracting("status")
      .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private NodeService service() {
    return new NodeService(new ObjectMapper(), dataDir.toString());
  }
}
