package dev.beiming.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRepository {
  private final JdbcTemplate jdbc;
  private final RowMapper<NotificationRecord> mapper = (rs, rowNum) -> new NotificationRecord(
    rs.getString("id"),
    rs.getString("event_id"),
    rs.getString("recipient_user_id"),
    rs.getString("type"),
    rs.getString("status"),
    rs.getString("title"),
    rs.getString("body"),
    rs.getString("actor_user_id"),
    rs.getString("actor_display_name"),
    rs.getString("actor_avatar_url"),
    rs.getString("target_type"),
    rs.getString("target_id"),
    rs.getString("action_url"),
    rs.getString("payload_json"),
    rs.getLong("created_at"),
    rs.getLong("read_at"),
    rs.getLong("archived_at")
  );

  NotificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void insert(NotificationRecord record) {
    jdbc.update(
      """
        insert into beiming_notifications
        (id, event_id, recipient_user_id, type, status, title, body, actor_user_id, actor_display_name,
         actor_avatar_url, target_type, target_id, action_url, payload_json, created_at, read_at, archived_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.eventId(),
      record.recipientUserId(),
      record.type(),
      record.status(),
      record.title(),
      record.body(),
      record.actorUserId(),
      record.actorDisplayName(),
      record.actorAvatarUrl(),
      record.targetType(),
      record.targetId(),
      record.actionUrl(),
      record.payloadJson(),
      record.createdAt(),
      record.readAt(),
      record.archivedAt()
    );
  }

  List<NotificationRecord> page(NotificationListQuery query) {
    var sql = new StringBuilder("select * from beiming_notifications where recipient_user_id = ?");
    var args = new ArrayList<Object>();
    args.add(query.recipientUserId());
    if (!clean(query.status()).isBlank() && !"ALL".equals(clean(query.status()))) {
      sql.append(" and status = ?");
      args.add(clean(query.status()));
    }
    if (!clean(query.type()).isBlank()) {
      sql.append(" and type = ?");
      args.add(clean(query.type()));
    }
    if (query.createdAfter() != null) {
      sql.append(" and created_at > ?");
      args.add(query.createdAfter());
    }
    if (query.createdBefore() != null) {
      sql.append(" and created_at < ?");
      args.add(query.createdBefore());
    }
    sql.append(" order by created_at desc, id desc limit ? offset ?");
    args.add(query.pageSize());
    args.add((query.page() - 1) * query.pageSize());
    return jdbc.query(sql.toString(), mapper, args.toArray());
  }

  int count(NotificationListQuery query) {
    var sql = new StringBuilder("select count(*) from beiming_notifications where recipient_user_id = ?");
    var args = new ArrayList<Object>();
    args.add(query.recipientUserId());
    if (!clean(query.status()).isBlank() && !"ALL".equals(clean(query.status()))) {
      sql.append(" and status = ?");
      args.add(clean(query.status()));
    }
    if (!clean(query.type()).isBlank()) {
      sql.append(" and type = ?");
      args.add(clean(query.type()));
    }
    if (query.createdAfter() != null) {
      sql.append(" and created_at > ?");
      args.add(query.createdAfter());
    }
    if (query.createdBefore() != null) {
      sql.append(" and created_at < ?");
      args.add(query.createdBefore());
    }
    var count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    return count == null ? 0 : count;
  }

  int countUnreadByRecipient(String recipientUserId) {
    var count = jdbc.queryForObject(
      "select count(*) from beiming_notifications where recipient_user_id = ? and status = 'UNREAD'",
      Integer.class,
      recipientUserId
    );
    return count == null ? 0 : count;
  }

  Optional<NotificationRecord> findByIdAndRecipient(String notificationId, String recipientUserId) {
    return jdbc.query(
      "select * from beiming_notifications where id = ? and recipient_user_id = ?",
      mapper,
      notificationId,
      recipientUserId
    ).stream().findFirst();
  }

  boolean markRead(String notificationId, String recipientUserId, long readAt) {
    return jdbc.update(
      """
        update beiming_notifications
        set status = case when status = 'ARCHIVED' then status else 'READ' end,
            read_at = case when status = 'ARCHIVED' then read_at else ? end
        where id = ? and recipient_user_id = ?
      """,
      readAt,
      notificationId,
      recipientUserId
    ) > 0;
  }

  int markAllRead(String recipientUserId, long readAt) {
    return jdbc.update(
      """
        update beiming_notifications
        set status = 'READ', read_at = ?
        where recipient_user_id = ? and status = 'UNREAD'
      """,
      readAt,
      recipientUserId
    );
  }

  boolean archive(String notificationId, String recipientUserId, long archivedAt) {
    return jdbc.update(
      """
        update beiming_notifications
        set status = 'ARCHIVED', archived_at = ?
        where id = ? and recipient_user_id = ?
      """,
      archivedAt,
      notificationId,
      recipientUserId
    ) > 0;
  }

  private String clean(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }
}
