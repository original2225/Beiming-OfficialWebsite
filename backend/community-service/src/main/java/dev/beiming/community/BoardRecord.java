package dev.beiming.community;

public record BoardRecord(
  String id,
  String slug,
  String name,
  String description,
  String visibility,
  String postingRole,
  int sortOrder,
  long createdAt,
  long updatedAt
) {
}
