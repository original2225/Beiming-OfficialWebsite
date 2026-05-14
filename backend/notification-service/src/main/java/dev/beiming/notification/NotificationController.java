package dev.beiming.notification;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NotificationController {
  private final NotificationService notificationService;

  NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping("/api/notifications")
  ApiEnvelope<PageResult<NotificationView>> list(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "ALL") String status,
    @RequestParam(defaultValue = "") String type,
    @RequestParam(required = false) Long createdAfter,
    @RequestParam(required = false) Long createdBefore,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(notificationService.list(authorization, status, type, createdAfter, createdBefore, page, pageSize));
  }

  @GetMapping("/api/notifications/unread-count")
  ApiEnvelope<UnreadCountView> unreadCount(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization
  ) {
    return ApiEnvelope.ok(notificationService.unreadCount(authorization));
  }

  @PutMapping("/api/notifications/{notificationId}/read")
  ApiEnvelope<Map<String, Object>> markRead(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String notificationId
  ) {
    return ApiEnvelope.ok(notificationService.markRead(authorization, notificationId));
  }

  @PutMapping("/api/notifications/read-all")
  ApiEnvelope<Map<String, Object>> markAllRead(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization
  ) {
    return ApiEnvelope.ok(notificationService.markAllRead(authorization));
  }

  @DeleteMapping("/api/notifications/{notificationId}")
  ApiEnvelope<Map<String, Object>> archive(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String notificationId
  ) {
    return ApiEnvelope.ok(notificationService.archive(authorization, notificationId));
  }
}
