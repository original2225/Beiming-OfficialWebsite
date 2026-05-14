package dev.beiming.notification;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalNotificationController {
  private final NotificationEventService notificationEventService;

  InternalNotificationController(NotificationEventService notificationEventService) {
    this.notificationEventService = notificationEventService;
  }

  @PostMapping("/api/internal/notifications/events")
  ApiEnvelope<CreateNotificationEventResponse> createEvent(
    @RequestHeader(value = "X-Beiming-Internal-Token", required = false) String internalToken,
    @RequestBody CreateNotificationEventRequest request
  ) {
    return ApiEnvelope.ok(notificationEventService.createEvent(internalToken, request));
  }
}
