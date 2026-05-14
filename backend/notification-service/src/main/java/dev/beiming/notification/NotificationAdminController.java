package dev.beiming.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationAdminController {
  private final NotificationAdminService notificationAdminService;

  NotificationAdminController(NotificationAdminService notificationAdminService) {
    this.notificationAdminService = notificationAdminService;
  }

  @GetMapping("/api/notifications/admin/events")
  ApiEnvelope<PageResult<NotificationEventView>> events(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "") String eventType,
    @RequestParam(defaultValue = "") String recipientUserId,
    @RequestParam(required = false) Long createdAfter,
    @RequestParam(required = false) Long createdBefore,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(notificationAdminService.events(authorization, eventType, recipientUserId, createdAfter, createdBefore, page, pageSize));
  }

  @GetMapping("/api/notifications/admin/deliveries")
  ApiEnvelope<PageResult<NotificationDeliveryView>> deliveries(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "") String channel,
    @RequestParam(defaultValue = "") String recipientUserId,
    @RequestParam(required = false) Long createdAfter,
    @RequestParam(required = false) Long createdBefore,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(notificationAdminService.deliveries(authorization, status, channel, recipientUserId, createdAfter, createdBefore, page, pageSize));
  }
}
