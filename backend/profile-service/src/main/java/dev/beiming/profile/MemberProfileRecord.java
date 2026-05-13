package dev.beiming.profile;

public record MemberProfileRecord(
  String id,
  String userId,
  String displayName,
  String bio,
  String avatarUrl,
  String minecraftId,
  String minecraftIdKey,
  String minecraftUuid,
  String memberGroup,
  String memberStatus,
  String visibility,
  long joinedAt,
  long createdAt,
  long updatedAt,
  String adminNote,
  boolean featured,
  String createdBy,
  String updatedBy
) {
}
