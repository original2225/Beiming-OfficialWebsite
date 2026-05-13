package dev.beiming.profile;

public record ProfileUpsertRequest(
  String displayName,
  String bio,
  String avatarUrl,
  String minecraftId,
  String minecraftUuid,
  String visibility
) {
}
