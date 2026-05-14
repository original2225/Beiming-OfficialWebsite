package dev.beiming.community;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HttpProfileClient implements ProfileClient {
  private final RestClient restClient;

  HttpProfileClient(@Value("${beiming.services.profile-url}") String profileUrl) {
    this.restClient = RestClient.builder().baseUrl(profileUrl.replaceFirst("/+$", "")).build();
  }

  @Override
  public AuthorSnapshot resolve(String authorization, CurrentUserView user) {
    try {
      var body = restClient.get()
        .uri("/api/profile/me")
        .header("Authorization", authorization == null ? "" : authorization.trim())
        .retrieve()
        .body(JsonNode.class);
      var data = body == null ? null : body.path("data");
      if (data == null || data.isMissingNode()) return AuthorSnapshot.fromUser(user);
      var displayName = valueOrFallback(data, "displayName", user.name());
      return new AuthorSnapshot(
        user.id(),
        displayName,
        data.path("avatarUrl").asText(""),
        data.path("minecraftId").asText("")
      ).normalized();
    } catch (Exception ignored) {
      return AuthorSnapshot.fromUser(user);
    }
  }

  private String valueOrFallback(JsonNode node, String field, String fallback) {
    var value = node.path(field).asText("");
    return value == null || value.trim().isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
  }
}
