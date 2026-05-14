package dev.beiming.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayControllerRoutingTest {
  private static final TestServer authServer = TestServer.start("auth");
  private static final TestServer resourceServer = TestServer.start("resource");
  private static final TestServer profileServer = TestServer.start("profile");
  private static final TestServer communityServer = TestServer.start("community");

  @Autowired
  MockMvc mvc;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("beiming.services.auth-url", authServer::url);
    registry.add("beiming.services.resource-url", resourceServer::url);
    registry.add("beiming.services.profile-url", profileServer::url);
    registry.add("beiming.services.community-url", communityServer::url);
    registry.add("beiming.frontend-origin", () -> "http://127.0.0.1:5173");
  }

  @BeforeEach
  void resetServers() {
    authServer.reset();
    resourceServer.reset();
    profileServer.reset();
    communityServer.reset();
  }

  @AfterAll
  static void stopServers() {
    authServer.stop();
    resourceServer.stop();
    profileServer.stop();
    communityServer.stop();
  }

  @Test
  void publicProfileMembersRouteToProfileWithoutSessionValidation() throws Exception {
    var result = mvc.perform(get("/api/profile/members"))
      .andExpect(request().asyncStarted())
      .andReturn();

    mvc.perform(asyncDispatch(result))
      .andExpect(status().isOk())
      .andExpect(content().string("profile:/api/profile/members"));

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isZero();
    org.assertj.core.api.Assertions.assertThat(profileServer.calls()).isEqualTo(1);
  }

  @Test
  void privateProfileRoutesRequireSessionValidationBeforeProxying() throws Exception {
    mvc.perform(get("/api/profile/me"))
      .andExpect(status().isUnauthorized());

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isZero();
    org.assertj.core.api.Assertions.assertThat(profileServer.calls()).isZero();

    var result = mvc.perform(get("/api/profile/me").header("Authorization", "Bearer valid"))
      .andExpect(request().asyncStarted())
      .andReturn();

    mvc.perform(asyncDispatch(result))
      .andExpect(status().isOk())
      .andExpect(content().string("profile:/api/profile/me"));

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(profileServer.calls()).isEqualTo(1);
  }

  @Test
  void gatewayCorsAllowsPatchPreflightForUserManagement() throws Exception {
    mvc.perform(options("/api/users/user-1")
        .header("Origin", "http://127.0.0.1:5173")
        .header("Access-Control-Request-Method", "PATCH"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
  }

  @Test
  void publicCommunityListRoutesWithoutToken() throws Exception {
    var result = mvc.perform(get("/api/community/posts"))
      .andExpect(request().asyncStarted())
      .andReturn();

    mvc.perform(asyncDispatch(result))
      .andExpect(status().isOk())
      .andExpect(content().string("community:/api/community/posts"));

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isZero();
    org.assertj.core.api.Assertions.assertThat(communityServer.calls()).isEqualTo(1);
  }

  @Test
  void publicCommunityDetailRoutesWithoutToken() throws Exception {
    var result = mvc.perform(get("/api/community/posts/post-1/comments"))
      .andExpect(request().asyncStarted())
      .andReturn();

    mvc.perform(asyncDispatch(result))
      .andExpect(status().isOk())
      .andExpect(content().string("community:/api/community/posts/post-1/comments"));

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isZero();
    org.assertj.core.api.Assertions.assertThat(communityServer.calls()).isEqualTo(1);
  }

  @Test
  void communityWriteRouteRequiresToken() throws Exception {
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/community/posts"))
      .andExpect(status().isUnauthorized());

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isZero();
    org.assertj.core.api.Assertions.assertThat(communityServer.calls()).isZero();
  }

  @Test
  void communityWriteRouteProxiesAfterAuthValidation() throws Exception {
    var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/community/posts")
        .header("Authorization", "Bearer valid"))
      .andExpect(request().asyncStarted())
      .andReturn();

    mvc.perform(asyncDispatch(result))
      .andExpect(status().isOk())
      .andExpect(content().string("community:/api/community/posts"));

    org.assertj.core.api.Assertions.assertThat(authServer.calls()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(communityServer.calls()).isEqualTo(1);
  }

  static class TestServer {
    private final String name;
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    private TestServer(String name, HttpServer server) {
      this.name = name;
      this.server = server;
    }

    static TestServer start(String name) {
      try {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var result = new TestServer(name, server);
        server.createContext("/", exchange -> {
          result.calls.incrementAndGet();
          var response = (name + ":" + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return result;
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int calls() {
      return calls.get();
    }

    void reset() {
      calls.set(0);
    }

    void stop() {
      server.stop(0);
    }
  }
}
