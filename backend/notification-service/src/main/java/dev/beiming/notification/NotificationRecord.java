package dev.beiming.notification;

public record NotificationRecord(
  String id,
  String eventId,
  String recipientUserId,
  String type,
  String status,
  String title,
  String body,
  String actorUserId,
  String actorDisplayName,
  String actorAvatarUrl,
  String targetType,
  String targetId,
  String actionUrl,
  String payloadJson,
  long createdAt,
  long readAt,
  long archivedAt
) {
}
