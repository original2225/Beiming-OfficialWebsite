package dev.beiming.community;

public record CreateBoardRequest(
  String slug,
  String name,
  String description,
  String visibility,
  String postingRole,
  Integer sortOrder
) {
}
