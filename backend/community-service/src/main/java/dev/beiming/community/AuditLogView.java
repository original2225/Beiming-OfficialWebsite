package dev.beiming.community;

public record AuditLogView(
  String id,
  String actorUserId,
  String actorDisplayName,
  String action,
  String targetType,
  String targetId,
  String detail,
  long createdAt
) {
  static AuditLogView fromRecord(AuditLogRecord record) {
    return new AuditLogView(
      record.id(),
      record.actorUserId(),
      record.actorDisplayName(),
      record.action(),
      record.targetType(),
      record.targetId(),
      record.detail(),
      record.createdAt()
    );
  }
}
