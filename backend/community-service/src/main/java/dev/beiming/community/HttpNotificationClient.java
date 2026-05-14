package dev.beiming.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HttpNotificationClient implements NotificationClient {
  private final RestClient restClient;
  private final String internalToken;

  HttpNotificationClient(
    @Value("${beiming.services.notification-url}") String notificationUrl,
    @Value("${beiming.internal.notification-token:}") String internalToken
  ) {
    this.restClient = RestClient.builder().baseUrl(notificationUrl.replaceFirst("/+$", "")).build();
    this.internalToken = internalToken == null ? "" : internalToken.trim();
  }

  @Override
  public void createEvent(CreateNotificationEventRequest request) {
    var spec = restClient.post()
      .uri("/api/internal/notifications/events")
      .body(request);
    if (!internalToken.isBlank()) {
      spec.header("X-Beiming-Internal-Token", internalToken);
    }
    spec.retrieve().toBodilessEntity();
  }
}
