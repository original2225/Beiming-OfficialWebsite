package dev.beiming.community;

public record PostRecord(
  String id,
  String boardId,
  String authorUserId,
  String authorDisplayName,
  String authorAvatarUrl,
  String authorMinecraftId,
  String title,
  String content,
  String status,
  String visibility,
  String reviewStatus,
  boolean pinned,
  boolean locked,
  long viewCount,
  long commentCount,
  long likeCount,
  long favoriteCount,
  boolean hasPoll,
  long createdAt,
  long updatedAt,
  long publishedAt,
  long deletedAt,
  String lastModeratedBy,
  String moderationNote
) {
}
