package dev.beiming.notification;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class NotificationEventRepository {
  private final JdbcTemplate jdbc;
  private final RowMapper<NotificationEventRecord> mapper = (rs, rowNum) -> new NotificationEventRecord(
    rs.getString("id"),
    rs.getString("event_key"),
    rs.getString("event_type"),
    rs.getString("source_service"),
    rs.getString("source_id"),
    rs.getString("actor_user_id"),
    rs.getString("actor_display_name"),
    rs.getString("actor_avatar_url"),
    rs.getString("recipient_user_id"),
    rs.getString("target_type"),
    rs.getString("target_id"),
    rs.getString("title"),
    rs.getString("body"),
    rs.getString("action_url"),
    rs.getString("payload_json"),
    rs.getLong("created_at")
  );

  NotificationEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean insert(NotificationEventRecord record) {
    try {
      return jdbc.update(
        """
          insert into beiming_notification_events
          (id, event_key, event_type, source_service, source_id, actor_user_id, actor_display_name,
           actor_avatar_url, recipient_user_id, target_type, target_id, title, body, action_url, payload_json, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.eventKey(),
        record.eventType(),
        record.sourceService(),
        record.sourceId(),
        record.actorUserId(),
        record.actorDisplayName(),
        record.actorAvatarUrl(),
        record.recipientUserId(),
        record.targetType(),
        record.targetId(),
        record.title(),
        record.body(),
        record.actionUrl(),
        record.payloadJson(),
        record.createdAt()
      ) > 0;
    } catch (DuplicateKeyException ignored) {
      return false;
    }
  }

  List<NotificationEventRecord> page(NotificationEventQuery query) {
    var sql = new StringBuilder("select * from beiming_notification_events where 1 = 1");
    var args = new ArrayList<Object>();
    if (!clean(query.eventType()).isBlank()) {
      sql.append(" and event_type = ?");
      args.add(clean(query.eventType()));
    }
    if (!clean(query.recipientUserId()).isBlank()) {
      sql.append(" and recipient_user_id = ?");
      args.add(clean(query.recipientUserId()));
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

  int count(NotificationEventQuery query) {
    var sql = new StringBuilder("select count(*) from beiming_notification_events where 1 = 1");
    var args = new ArrayList<Object>();
    if (!clean(query.eventType()).isBlank()) {
      sql.append(" and event_type = ?");
      args.add(clean(query.eventType()));
    }
    if (!clean(query.recipientUserId()).isBlank()) {
      sql.append(" and recipient_user_id = ?");
      args.add(clean(query.recipientUserId()));
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

  private String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
