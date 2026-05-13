package dev.beiming.profile;

public record AdminProfileUpdateRequest(
  String displayName,
  String bio,
  String avatarUrl,
  String minecraftId,
  String minecraftUuid,
  String memberGroup,
  String memberStatus,
  String visibility,
  Long joinedAt,
  Boolean featured,
  String adminNote
) {
}
