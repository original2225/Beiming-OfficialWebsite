package dev.beiming.community;

public record AuditLogRecord(
  String id,
  String actorUserId,
  String actorDisplayName,
  String action,
  String targetType,
  String targetId,
  String detail,
  long createdAt
) {
}
