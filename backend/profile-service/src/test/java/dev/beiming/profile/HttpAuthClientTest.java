package dev.beiming.profile;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAuthClientTest {
  private TestAuthServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop();
  }

  @Test
  void requireUserParsesValidateEnvelopeFromAuthService() {
    server = TestAuthServer.start(200, """
      {"ok":true,"data":{"user":{"id":"user-1","name":"Owner","email":"owner@example.com","role":"ADMIN"}},"message":null}
      """);
    var client = new HttpAuthClient(server.url());

    var user = client.requireUser("Bearer token");

    assertThat(user.id()).isEqualTo("user-1");
    assertThat(user.name()).isEqualTo("Owner");
    assertThat(user.email()).isEqualTo("owner@example.com");
    assertThat(user.role()).isEqualTo("ADMIN");
  }

  @Test
  void requireUserPropagatesExpiredSessionAsUnauthorized() {
    server = TestAuthServer.start(401, """
      {"ok":false,"data":null,"message":"登录已过期"}
      """);
    var client = new HttpAuthClient(server.url());

    assertThatThrownBy(() -> client.requireUser("Bearer expired"))
      .isInstanceOf(ApiException.class)
      .extracting("status")
      .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  static class TestAuthServer {
    private final HttpServer server;

    private TestAuthServer(HttpServer server) {
      this.server = server;
    }

    static TestAuthServer start(int status, String body) {
      try {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/auth/validate", exchange -> {
          exchange.getResponseHeaders().set("content-type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var response = exchange.getResponseBody()) {
            response.write(bytes);
          }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return new TestAuthServer(server);
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }
  }
}
