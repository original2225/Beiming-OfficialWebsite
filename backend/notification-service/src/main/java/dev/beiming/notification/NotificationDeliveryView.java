package dev.beiming.notification;

public record NotificationDeliveryView(
  String id,
  String notificationId,
  String recipientUserId,
  String channel,
  String status,
  int attemptCount,
  String lastError,
  long createdAt,
  long updatedAt
) {
}
