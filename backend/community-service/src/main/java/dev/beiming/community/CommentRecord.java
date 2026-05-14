package dev.beiming.community;

public record CommentRecord(
  String id,
  String postId,
  String parentCommentId,
  String authorUserId,
  String authorDisplayName,
  String authorAvatarUrl,
  String authorMinecraftId,
  String content,
  String status,
  long likeCount,
  long createdAt,
  long updatedAt,
  long deletedAt,
  String lastModeratedBy,
  String moderationNote
) {
}
