package dev.beiming.notification;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class NotificationService {
  private final AuthClient authClient;
  private final NotificationRepository notificationRepository;

  NotificationService(AuthClient authClient, NotificationRepository notificationRepository) {
    this.authClient = authClient;
    this.notificationRepository = notificationRepository;
  }

  PageResult<NotificationView> list(String authorization, String status, String type, Long createdAfter, Long createdBefore, int page, int pageSize) {
    var user = authClient.requireUser(authorization);
    var safePage = Math.max(page, 1);
    var safePageSize = Math.min(Math.max(pageSize, 1), 100);
    var query = new NotificationListQuery(user.id(), status, type, createdAfter, createdBefore, safePage, safePageSize);
    var items = notificationRepository.page(query)
      .stream()
      .map(this::toView)
      .toList();
    var total = notificationRepository.count(query);
    return new PageResult<>(items, safePage, safePageSize, total);
  }

  UnreadCountView unreadCount(String authorization) {
    var user = authClient.requireUser(authorization);
    return new UnreadCountView(notificationRepository.countUnreadByRecipient(user.id()));
  }

  Map<String, Object> markRead(String authorization, String notificationId) {
    var user = authClient.requireUser(authorization);
    ensureOwnNotification(notificationId, user.id());
    notificationRepository.markRead(notificationId, user.id(), System.currentTimeMillis());
    return Map.of("read", true);
  }

  Map<String, Object> markAllRead(String authorization) {
    var user = authClient.requireUser(authorization);
    var updated = notificationRepository.markAllRead(user.id(), System.currentTimeMillis());
    return Map.of("updated", updated);
  }

  Map<String, Object> archive(String authorization, String notificationId) {
    var user = authClient.requireUser(authorization);
    ensureOwnNotification(notificationId, user.id());
    notificationRepository.archive(notificationId, user.id(), System.currentTimeMillis());
    return Map.of("archived", true);
  }

  private void ensureOwnNotification(String notificationId, String userId) {
    if (notificationRepository.findByIdAndRecipient(notificationId, userId).isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "通知不存在");
    }
  }

  private NotificationView toView(NotificationRecord record) {
    return new NotificationView(
      record.id(),
      record.recipientUserId(),
      record.type(),
      record.status(),
      record.title(),
      record.body(),
      record.actorUserId(),
      record.actorDisplayName(),
      record.actorAvatarUrl(),
      record.targetType(),
      record.targetId(),
      record.actionUrl(),
      record.payloadJson(),
      record.createdAt(),
      record.readAt(),
      record.archivedAt()
    );
  }
}
