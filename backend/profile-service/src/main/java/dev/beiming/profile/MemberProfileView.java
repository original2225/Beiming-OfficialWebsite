package dev.beiming.profile;

public record MemberProfileView(
  String id,
  String userId,
  String displayName,
  String bio,
  String avatarUrl,
  String minecraftId,
  String minecraftUuid,
  String skinUrl,
  String memberGroup,
  String memberStatus,
  String visibility,
  long joinedAt,
  long createdAt,
  long updatedAt,
  boolean featured,
  boolean exists
) {
  static MemberProfileView fromRecord(MemberProfileRecord record, boolean exists) {
    return new MemberProfileView(
      record.id(),
      record.userId(),
      record.displayName(),
      record.bio(),
      record.avatarUrl(),
      record.minecraftId(),
      record.minecraftUuid(),
      skinUrl(record),
      record.memberGroup(),
      record.memberStatus(),
      record.visibility(),
      record.joinedAt(),
      record.createdAt(),
      record.updatedAt(),
      record.featured(),
      exists
    );
  }

  private static String skinUrl(MemberProfileRecord record) {
    return MinecraftSkinUrl.resolve(record.minecraftId(), record.minecraftUuid());
  }
}
