package dev.beiming.community;

public record ReportRecord(
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
}
