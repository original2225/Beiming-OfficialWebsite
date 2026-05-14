package dev.beiming.community;

public record PostDetailView(
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
  boolean liked,
  boolean favorited,
  long createdAt,
  long updatedAt,
  long publishedAt,
  String moderationNote,
  PollView poll
) {
  static PostDetailView fromRecord(PostRecord record, boolean liked, boolean favorited, PollView poll) {
    return new PostDetailView(
      record.id(),
      record.boardId(),
      record.authorUserId(),
      record.authorDisplayName(),
      record.authorAvatarUrl(),
      record.authorMinecraftId(),
      record.title(),
      record.content(),
      record.status(),
      record.visibility(),
      record.reviewStatus(),
      record.pinned(),
      record.locked(),
      record.viewCount(),
      record.commentCount(),
      record.likeCount(),
      record.favoriteCount(),
      liked,
      favorited,
      record.createdAt(),
      record.updatedAt(),
      record.publishedAt(),
      record.moderationNote(),
      poll
    );
  }
}
