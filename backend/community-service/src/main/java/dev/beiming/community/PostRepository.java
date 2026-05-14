package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PostRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<PostRecord> mapper = (rs, rowNum) -> new PostRecord(
    rs.getString("id"),
    rs.getString("board_id"),
    rs.getString("author_user_id"),
    rs.getString("author_display_name"),
    rs.getString("author_avatar_url"),
    rs.getString("author_minecraft_id"),
    rs.getString("title"),
    rs.getString("content"),
    rs.getString("status"),
    rs.getString("visibility"),
    rs.getString("review_status"),
    rs.getBoolean("pinned"),
    rs.getBoolean("locked"),
    rs.getLong("view_count"),
    rs.getLong("comment_count"),
    rs.getLong("like_count"),
    rs.getLong("favorite_count"),
    rs.getBoolean("has_poll"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("published_at"),
    rs.getLong("deleted_at"),
    rs.getString("last_moderated_by"),
    rs.getString("moderation_note")
  );

  PostRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<PostRecord> findById(String postId) {
    return jdbc.query("select * from beiming_community_posts where id = ?", mapper, postId).stream().findFirst();
  }

  List<PostRecord> publicList(List<String> contentVisibilities, List<String> boardVisibilities, String boardId, String q, String sort, int page, int pageSize) {
    var sql = new StringBuilder(
      """
        select p.* from beiming_community_posts p
        join beiming_community_boards b on b.id = p.board_id
        where p.status = 'PUBLISHED'
          and p.review_status = 'APPROVED'
      """
    );
    var args = new ArrayList<Object>();
    if (!contentVisibilities.isEmpty()) {
      sql.append(" and p.visibility in (");
      for (var i = 0; i < contentVisibilities.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append("?");
        args.add(contentVisibilities.get(i));
      }
      sql.append(")");
    }
    if (!boardVisibilities.isEmpty()) {
      sql.append(" and b.visibility in (");
      for (var i = 0; i < boardVisibilities.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append("?");
        args.add(boardVisibilities.get(i));
      }
      sql.append(")");
    }
    if (!CommunityRules.clean(boardId).isBlank()) {
      sql.append(" and p.board_id = ?");
      args.add(CommunityRules.clean(boardId));
    }
    var term = CommunityRules.queryTerm(q);
    if (!term.isBlank()) {
      sql.append(" and (lower(p.title) like ? or lower(p.content) like ? or lower(p.author_display_name) like ?)");
      var like = "%" + term + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if ("hot".equalsIgnoreCase(sort)) {
      sql.append(" order by p.pinned desc, p.like_count desc, p.comment_count desc, p.published_at desc");
    } else {
      sql.append(" order by p.pinned desc, p.published_at desc, p.created_at desc");
    }
    sql.append(" limit ? offset ?");
    args.add(pageSize);
    args.add((page - 1) * pageSize);
    return jdbc.query(sql.toString(), mapper, args.toArray());
  }

  int countPublic(List<String> contentVisibilities, List<String> boardVisibilities, String boardId, String q) {
    var sql = new StringBuilder(
      """
        select count(*) from beiming_community_posts p
        join beiming_community_boards b on b.id = p.board_id
        where p.status = 'PUBLISHED'
          and p.review_status = 'APPROVED'
      """
    );
    var args = new ArrayList<Object>();
    if (!contentVisibilities.isEmpty()) {
      sql.append(" and p.visibility in (");
      for (var i = 0; i < contentVisibilities.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append("?");
        args.add(contentVisibilities.get(i));
      }
      sql.append(")");
    }
    if (!boardVisibilities.isEmpty()) {
      sql.append(" and b.visibility in (");
      for (var i = 0; i < boardVisibilities.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append("?");
        args.add(boardVisibilities.get(i));
      }
      sql.append(")");
    }
    if (!CommunityRules.clean(boardId).isBlank()) {
      sql.append(" and p.board_id = ?");
      args.add(CommunityRules.clean(boardId));
    }
    var term = CommunityRules.queryTerm(q);
    if (!term.isBlank()) {
      sql.append(" and (lower(p.title) like ? or lower(p.content) like ? or lower(p.author_display_name) like ?)");
      var like = "%" + term + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    var count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return count == null ? 0 : count;
  }

  List<PostRecord> adminList(String boardId, String status, String authorUserId, String q, int page, int pageSize) {
    var sql = new StringBuilder("select * from beiming_community_posts where 1 = 1");
    var args = new ArrayList<Object>();
    if (!CommunityRules.clean(boardId).isBlank()) {
      sql.append(" and board_id = ?");
      args.add(CommunityRules.clean(boardId));
    }
    if (!CommunityRules.clean(status).isBlank()) {
      sql.append(" and status = ?");
      args.add(CommunityRules.clean(status).toUpperCase());
    }
    if (!CommunityRules.clean(authorUserId).isBlank()) {
      sql.append(" and author_user_id = ?");
      args.add(CommunityRules.clean(authorUserId));
    }
    var term = CommunityRules.queryTerm(q);
    if (!term.isBlank()) {
      sql.append(" and (lower(title) like ? or lower(content) like ? or lower(author_display_name) like ?)");
      var like = "%" + term + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    sql.append(" order by created_at desc limit ? offset ?");
    args.add(pageSize);
    args.add((page - 1) * pageSize);
    return jdbc.query(sql.toString(), mapper, args.toArray());
  }

  int countAdmin(String boardId, String status, String authorUserId, String q) {
    var sql = new StringBuilder("select count(*) from beiming_community_posts where 1 = 1");
    var args = new ArrayList<Object>();
    if (!CommunityRules.clean(boardId).isBlank()) {
      sql.append(" and board_id = ?");
      args.add(CommunityRules.clean(boardId));
    }
    if (!CommunityRules.clean(status).isBlank()) {
      sql.append(" and status = ?");
      args.add(CommunityRules.clean(status).toUpperCase());
    }
    if (!CommunityRules.clean(authorUserId).isBlank()) {
      sql.append(" and author_user_id = ?");
      args.add(CommunityRules.clean(authorUserId));
    }
    var term = CommunityRules.queryTerm(q);
    if (!term.isBlank()) {
      sql.append(" and (lower(title) like ? or lower(content) like ? or lower(author_display_name) like ?)");
      var like = "%" + term + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    var count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return count == null ? 0 : count;
  }

  List<PostRecord> favoriteList(String userId, int page, int pageSize) {
    return jdbc.query(
      """
        select p.* from beiming_community_post_favorites f
        join beiming_community_posts p on p.id = f.post_id
        where f.user_id = ?
        order by f.created_at desc
        limit ? offset ?
      """,
      mapper,
      userId,
      pageSize,
      (page - 1) * pageSize
    );
  }

  int countFavorites(String userId) {
    var count = jdbc.queryForObject("select count(*) from beiming_community_post_favorites where user_id = ?", Integer.class, userId);
    return count == null ? 0 : count;
  }

  void insert(PostRecord record) {
    jdbc.update(
      """
        insert into beiming_community_posts
        (id, board_id, author_user_id, author_display_name, author_avatar_url, author_minecraft_id, title, content,
         status, visibility, review_status, pinned, locked, view_count, comment_count, like_count, favorite_count,
         has_poll, created_at, updated_at, published_at, deleted_at, last_moderated_by, moderation_note)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
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
      record.publishedAt(),
      record.deletedAt(),
      record.lastModeratedBy(),
      record.moderationNote()
    );
  }

  void update(PostRecord record) {
    jdbc.update(
      """
        update beiming_community_posts
        set board_id = ?, title = ?, content = ?, status = ?, visibility = ?, review_status = ?, pinned = ?, locked = ?,
            view_count = ?, comment_count = ?, like_count = ?, favorite_count = ?, has_poll = ?, updated_at = ?, published_at = ?,
            deleted_at = ?, last_moderated_by = ?, moderation_note = ?
        where id = ?
      """,
      record.boardId(),
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
      record.updatedAt(),
      record.publishedAt(),
      record.deletedAt(),
      record.lastModeratedBy(),
      record.moderationNote(),
      record.id()
    );
  }

  void incrementViewCount(String postId) {
    jdbc.update("update beiming_community_posts set view_count = view_count + 1 where id = ?", postId);
  }

  void adjustCommentCount(String postId, int delta) {
    jdbc.update("update beiming_community_posts set comment_count = greatest(comment_count + ?, 0) where id = ?", delta, postId);
  }

  void adjustLikeCount(String postId, int delta) {
    jdbc.update("update beiming_community_posts set like_count = greatest(like_count + ?, 0) where id = ?", delta, postId);
  }

  void adjustFavoriteCount(String postId, int delta) {
    jdbc.update("update beiming_community_posts set favorite_count = greatest(favorite_count + ?, 0) where id = ?", delta, postId);
  }

  void setHasPoll(String postId, boolean hasPoll) {
    jdbc.update("update beiming_community_posts set has_poll = ?, updated_at = ? where id = ?", hasPoll, CommunityRules.now(), postId);
  }
}
