package dev.beiming.notification;

public record NotificationDeliveryQuery(
  String status,
  String channel,
  String recipientUserId,
  Long createdAfter,
  Long createdBefore,
  int page,
  int pageSize
) {
}
