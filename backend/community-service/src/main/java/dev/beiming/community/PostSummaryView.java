package dev.beiming.community;

public record PostSummaryView(
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
  long publishedAt
) {
  static PostSummaryView fromRecord(PostRecord record) {
    return new PostSummaryView(
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
      record.hasPoll(),
      record.createdAt(),
      record.updatedAt(),
      record.publishedAt()
    );
  }
}
