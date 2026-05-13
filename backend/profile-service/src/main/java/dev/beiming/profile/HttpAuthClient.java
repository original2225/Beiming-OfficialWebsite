package dev.beiming.profile;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class HttpAuthClient implements AuthClient {
  private final RestClient restClient;

  HttpAuthClient(@Value("${beiming.services.auth-url}") String authUrl) {
    this.restClient = RestClient.builder().baseUrl(authUrl.replaceFirst("/+$", "")).build();
  }

  @Override
  public CurrentUserView requireUser(String authorization) {
    var value = authorization == null ? "" : authorization.trim();
    if (value.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
    try {
      var body = restClient.get()
        .uri("/api/auth/validate")
        .header("Authorization", value)
        .retrieve()
        .body(JsonNode.class);
      var user = body == null ? null : body.at("/data/user");
      if (user == null || user.isMissingNode()) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期");
      return new CurrentUserView(
        user.path("id").asText(""),
        user.path("name").asText(""),
        user.path("email").asText(""),
        user.path("role").asText("")
      );
    } catch (HttpClientErrorException error) {
      if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
        throw new ApiException(HttpStatus.valueOf(error.getStatusCode().value()), "登录已过期");
      }
      throw error;
    }
  }

  @Override
  public CurrentUserView optionalUser(String authorization) {
    if (authorization == null || authorization.isBlank()) return null;
    try {
      return requireUser(authorization);
    } catch (ApiException error) {
      if (error.status() == HttpStatus.UNAUTHORIZED || error.status() == HttpStatus.FORBIDDEN) return null;
      throw error;
    }
  }
}
