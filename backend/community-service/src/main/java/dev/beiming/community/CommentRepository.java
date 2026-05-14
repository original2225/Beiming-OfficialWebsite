package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CommentRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<CommentRecord> mapper = (rs, rowNum) -> new CommentRecord(
    rs.getString("id"),
    rs.getString("post_id"),
    rs.getString("parent_comment_id"),
    rs.getString("author_user_id"),
    rs.getString("author_display_name"),
    rs.getString("author_avatar_url"),
    rs.getString("author_minecraft_id"),
    rs.getString("content"),
    rs.getString("status"),
    rs.getLong("like_count"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("deleted_at"),
    rs.getString("last_moderated_by"),
    rs.getString("moderation_note")
  );

  CommentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<CommentRecord> byPostId(String postId) {
    return jdbc.query("select * from beiming_community_comments where post_id = ? order by created_at asc", mapper, postId);
  }

  Optional<CommentRecord> findById(String commentId) {
    return jdbc.query("select * from beiming_community_comments where id = ?", mapper, commentId).stream().findFirst();
  }

  void insert(CommentRecord record) {
    jdbc.update(
      """
        insert into beiming_community_comments
        (id, post_id, parent_comment_id, author_user_id, author_display_name, author_avatar_url, author_minecraft_id,
         content, status, like_count, created_at, updated_at, deleted_at, last_moderated_by, moderation_note)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
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
      record.createdAt(),
      record.updatedAt(),
      record.deletedAt(),
      record.lastModeratedBy(),
      record.moderationNote()
    );
  }

  void update(CommentRecord record) {
    jdbc.update(
      """
        update beiming_community_comments
        set content = ?, status = ?, like_count = ?, updated_at = ?, deleted_at = ?, last_moderated_by = ?, moderation_note = ?
        where id = ?
      """,
      record.content(),
      record.status(),
      record.likeCount(),
      record.updatedAt(),
      record.deletedAt(),
      record.lastModeratedBy(),
      record.moderationNote(),
      record.id()
    );
  }

  void adjustLikeCount(String commentId, int delta) {
    jdbc.update("update beiming_community_comments set like_count = greatest(like_count + ?, 0) where id = ?", delta, commentId);
  }
}
