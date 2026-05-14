package dev.beiming.notification;

public record NotificationListQuery(
  String recipientUserId,
  String status,
  String type,
  Long createdAfter,
  Long createdBefore,
  int page,
  int pageSize
) {
}
