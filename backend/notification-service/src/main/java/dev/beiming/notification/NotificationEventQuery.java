package dev.beiming.notification;

public record NotificationEventQuery(
  String eventType,
  String recipientUserId,
  Long createdAfter,
  Long createdBefore,
  int page,
  int pageSize
) {
}
