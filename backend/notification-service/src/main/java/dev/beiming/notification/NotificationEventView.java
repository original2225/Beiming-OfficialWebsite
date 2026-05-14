package dev.beiming.notification;

public record NotificationEventView(
  String id,
  String eventKey,
  String eventType,
  String sourceService,
  String sourceId,
  String actorUserId,
  String actorDisplayName,
  String actorAvatarUrl,
  String recipientUserId,
  String targetType,
  String targetId,
  String title,
  String body,
  String actionUrl,
  String payloadJson,
  long createdAt
) {
}
