package dev.beiming.community;

import java.util.Map;

public record CreateNotificationEventRequest(
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
  Map<String, Object> payload
) {
}
