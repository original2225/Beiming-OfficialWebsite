package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InteractionRepository {
  private final JdbcTemplate jdbc;

  InteractionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasPostLike(String postId, String userId) {
    return exists("select count(*) from beiming_community_post_reactions where post_id = ? and user_id = ? and reaction_type = 'LIKE'", postId, userId);
  }

  boolean hasCommentLike(String commentId, String userId) {
    return exists("select count(*) from beiming_community_comment_reactions where comment_id = ? and user_id = ? and reaction_type = 'LIKE'", commentId, userId);
  }

  boolean hasFavorite(String postId, String userId) {
    return exists("select count(*) from beiming_community_post_favorites where post_id = ? and user_id = ?", postId, userId);
  }

  boolean addPostLike(String postId, String userId) {
    try {
      return jdbc.update(
        "insert into beiming_community_post_reactions (id, post_id, user_id, reaction_type, created_at) values (?, ?, ?, 'LIKE', ?)",
        "post-reaction-" + java.util.UUID.randomUUID(),
        postId,
        userId,
        CommunityRules.now()
      ) > 0;
    } catch (DuplicateKeyException ignored) {
      return false;
    }
  }

  boolean removePostLike(String postId, String userId) {
    return jdbc.update("delete from beiming_community_post_reactions where post_id = ? and user_id = ? and reaction_type = 'LIKE'", postId, userId) > 0;
  }

  boolean addCommentLike(String commentId, String userId) {
    try {
      return jdbc.update(
        "insert into beiming_community_comment_reactions (id, comment_id, user_id, reaction_type, created_at) values (?, ?, ?, 'LIKE', ?)",
        "comment-reaction-" + java.util.UUID.randomUUID(),
        commentId,
        userId,
        CommunityRules.now()
      ) > 0;
    } catch (DuplicateKeyException ignored) {
      return false;
    }
  }

  boolean removeCommentLike(String commentId, String userId) {
    return jdbc.update("delete from beiming_community_comment_reactions where comment_id = ? and user_id = ? and reaction_type = 'LIKE'", commentId, userId) > 0;
  }

  boolean addFavorite(String postId, String userId) {
    try {
      return jdbc.update(
        "insert into beiming_community_post_favorites (id, post_id, user_id, created_at) values (?, ?, ?, ?)",
        "favorite-" + java.util.UUID.randomUUID(),
        postId,
        userId,
        CommunityRules.now()
      ) > 0;
    } catch (DuplicateKeyException ignored) {
      return false;
    }
  }

  boolean removeFavorite(String postId, String userId) {
    return jdbc.update("delete from beiming_community_post_favorites where post_id = ? and user_id = ?", postId, userId) > 0;
  }

  private boolean exists(String sql, String first, String second) {
    var count = jdbc.queryForObject(sql, Integer.class, first, second);
    return count != null && count > 0;
  }
}
