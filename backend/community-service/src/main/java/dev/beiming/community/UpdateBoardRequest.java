package dev.beiming.community;

public record UpdateBoardRequest(
  String name,
  String description,
  String visibility,
  String postingRole,
  Integer sortOrder
) {
}
