package dev.beiming.community;

public record ReportView(
  String id,
  String targetType,
  String targetId,
  String reporterUserId,
  String reporterDisplayName,
  String reason,
  String detail,
  String status,
  String reviewerUserId,
  String reviewNote,
  long createdAt,
  long updatedAt,
  long resolvedAt
) {
  static ReportView fromRecord(ReportRecord record) {
    return new ReportView(
      record.id(),
      record.targetType(),
      record.targetId(),
      record.reporterUserId(),
      record.reporterDisplayName(),
      record.reason(),
      record.detail(),
      record.status(),
      record.reviewerUserId(),
      record.reviewNote(),
      record.createdAt(),
      record.updatedAt(),
      record.resolvedAt()
    );
  }
}
