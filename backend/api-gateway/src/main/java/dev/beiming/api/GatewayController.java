package dev.beiming.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@RestController
public class GatewayController {
  private static final List<String> HOP_BY_HOP_HEADERS = List.of(
    "connection",
    "content-length",
    "expect",
    "host",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
  );

  private final String resourceUrl;
  private final String authUrl;
  private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  GatewayController(
    @Value("${beiming.services.resource-url}") String resourceUrl,
    @Value("${beiming.services.auth-url}") String authUrl
  ) {
    this.resourceUrl = resourceUrl.replaceFirst("/+$", "");
    this.authUrl = authUrl.replaceFirst("/+$", "");
  }

  @GetMapping("/health")
  ApiEnvelope<Map<String, Object>> health() {
    return ApiEnvelope.ok(Map.of(
      "service", "beiming-api-gateway",
      "resourceService", resourceUrl,
      "authService", authUrl
    ));
  }

  @RequestMapping({"/api/**"})
  ResponseEntity<StreamingResponseBody> proxyApi(HttpServletRequest servletRequest) throws Exception {
    var target = isAuthServicePath(servletRequest.getRequestURI()) ? authUrl : resourceUrl;
    if (!isAuthServicePath(servletRequest.getRequestURI())) {
      validateSession(servletRequest);
    }
    var upstream = forward(servletRequest, target);
    return stream(upstream, servletRequest.getMethod());
  }

  private boolean isAuthServicePath(String path) {
    return path.startsWith("/api/auth/")
      || path.equals("/api/auth")
      || path.startsWith("/api/users")
      || path.equals("/api/users")
      || path.startsWith("/api/cloud/")
      || path.equals("/api/cloud");
  }

  private void validateSession(HttpServletRequest servletRequest) throws Exception {
    var authorization = servletRequest.getHeader("Authorization");
    if (authorization == null || authorization.isBlank()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    var request = HttpRequest.newBuilder(URI.create(authUrl + "/api/auth/validate"))
      .GET()
      .header("Authorization", authorization)
      .build();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      throw new ApiException(HttpStatus.valueOf(response.statusCode()), "登录已过期");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "认证服务暂时不可用");
    }
  }

  private HttpResponse<java.io.InputStream> forward(HttpServletRequest servletRequest, String target) throws Exception {
    var uri = URI.create(target + servletRequest.getRequestURI() + (servletRequest.getQueryString() == null ? "" : "?" + servletRequest.getQueryString()));
    var requestBuilder = HttpRequest.newBuilder(uri);
    servletRequest.getHeaderNames().asIterator().forEachRemaining(name -> {
      if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
        servletRequest.getHeaders(name).asIterator().forEachRemaining(value -> requestBuilder.header(name, value));
      }
    });
    var method = HttpMethod.valueOf(servletRequest.getMethod());
    var publisher = method == HttpMethod.GET || method == HttpMethod.HEAD
      ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofByteArray(StreamUtils.copyToByteArray(servletRequest.getInputStream()));
    requestBuilder.method(servletRequest.getMethod(), publisher);
    return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  private ResponseEntity<StreamingResponseBody> stream(HttpResponse<java.io.InputStream> upstream, String method) {
    var headers = new HttpHeaders();
    upstream.headers().map().forEach((name, values) -> {
      var normalized = name.toLowerCase();
      if (!HOP_BY_HOP_HEADERS.contains(normalized) && !normalized.startsWith("access-control-")) {
        values.forEach(value -> headers.add(name, value));
      }
    });
    headers.setAccessControlExposeHeaders(List.of(
      "accept-ranges",
      "content-disposition",
      "content-length",
      "content-range",
      "content-type",
      "x-file-name",
      "x-file-size"
    ));
    if ("HEAD".equalsIgnoreCase(method)) {
      return ResponseEntity.status(upstream.statusCode()).headers(headers).body(output -> {});
    }
    return ResponseEntity.status(upstream.statusCode()).headers(headers).body(output -> {
      try (var input = upstream.body()) {
        StreamUtils.copy(input, output);
      }
    });
  }
}
