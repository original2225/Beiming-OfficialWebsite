package dev.beiming.community;

public record CommentView(
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
  boolean liked,
  long createdAt,
  long updatedAt,
  String moderationNote
) {
  static CommentView fromRecord(CommentRecord record, boolean liked) {
    return new CommentView(
      record.id(),
      record.postId(),
      record.parentCommentId(),
      record.authorUserId(),
      record.authorDisplayName(),
      record.authorAvatarUrl(),
      record.authorMinecraftId(),
      record.content(),
      record.status(),
      record.likeCount(),
      liked,
      record.createdAt(),
      record.updatedAt(),
      record.moderationNote()
    );
  }
}
