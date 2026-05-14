package dev.beiming.notification;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class NotificationAdminService {
  private final AuthClient authClient;
  private final NotificationEventRepository notificationEventRepository;
  private final NotificationDeliveryRepository notificationDeliveryRepository;

  NotificationAdminService(
    AuthClient authClient,
    NotificationEventRepository notificationEventRepository,
    NotificationDeliveryRepository notificationDeliveryRepository
  ) {
    this.authClient = authClient;
    this.notificationEventRepository = notificationEventRepository;
    this.notificationDeliveryRepository = notificationDeliveryRepository;
  }

  PageResult<NotificationEventView> events(String authorization, String eventType, String recipientUserId, Long createdAfter, Long createdBefore, int page, int pageSize) {
    requireAdmin(authorization);
    var safePage = Math.max(page, 1);
    var safePageSize = Math.min(Math.max(pageSize, 1), 100);
    var query = new NotificationEventQuery(eventType, recipientUserId, createdAfter, createdBefore, safePage, safePageSize);
    var items = notificationEventRepository.page(query).stream()
      .map(record -> new NotificationEventView(
        record.id(),
        record.eventKey(),
        record.eventType(),
        record.sourceService(),
        record.sourceId(),
        record.actorUserId(),
        record.actorDisplayName(),
        record.actorAvatarUrl(),
        record.recipientUserId(),
        record.targetType(),
        record.targetId(),
        record.title(),
        record.body(),
        record.actionUrl(),
        record.payloadJson(),
        record.createdAt()
      ))
      .toList();
    return new PageResult<>(items, safePage, safePageSize, notificationEventRepository.count(query));
  }

  PageResult<NotificationDeliveryView> deliveries(String authorization, String status, String channel, String recipientUserId, Long createdAfter, Long createdBefore, int page, int pageSize) {
    requireAdmin(authorization);
    var safePage = Math.max(page, 1);
    var safePageSize = Math.min(Math.max(pageSize, 1), 100);
    var query = new NotificationDeliveryQuery(status, channel, recipientUserId, createdAfter, createdBefore, safePage, safePageSize);
    var items = notificationDeliveryRepository.page(query).stream()
      .map(record -> new NotificationDeliveryView(
        record.id(),
        record.notificationId(),
        record.recipientUserId(),
        record.channel(),
        record.status(),
        record.attemptCount(),
        record.lastError(),
        record.createdAt(),
        record.updatedAt()
      ))
      .toList();
    return new PageResult<>(items, safePage, safePageSize, notificationDeliveryRepository.count(query));
  }

  private void requireAdmin(String authorization) {
    var user = authClient.requireUser(authorization);
    if (!user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "没有权限");
  }
}
