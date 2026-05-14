package dev.beiming.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class NotificationDeliveryRepository {
  private final JdbcTemplate jdbc;
  private final RowMapper<NotificationDeliveryRecord> mapper = (rs, rowNum) -> new NotificationDeliveryRecord(
    rs.getString("id"),
    rs.getString("notification_id"),
    rs.getString("recipient_user_id"),
    rs.getString("channel"),
    rs.getString("status"),
    rs.getInt("attempt_count"),
    rs.getString("last_error"),
    rs.getLong("created_at"),
    rs.getLong("updated_at")
  );

  NotificationDeliveryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void insert(NotificationDeliveryRecord record) {
    jdbc.update(
      """
        insert into beiming_notification_deliveries
        (id, notification_id, recipient_user_id, channel, status, attempt_count, last_error, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.notificationId(),
      record.recipientUserId(),
      record.channel(),
      record.status(),
      record.attemptCount(),
      record.lastError(),
      record.createdAt(),
      record.updatedAt()
    );
  }

  List<NotificationDeliveryRecord> page(NotificationDeliveryQuery query) {
    var sql = new StringBuilder("select * from beiming_notification_deliveries where 1 = 1");
    var args = new ArrayList<Object>();
    if (!clean(query.status()).isBlank()) {
      sql.append(" and status = ?");
      args.add(clean(query.status()));
    }
    if (!clean(query.channel()).isBlank()) {
      sql.append(" and channel = ?");
      args.add(clean(query.channel()));
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

  int count(NotificationDeliveryQuery query) {
    var sql = new StringBuilder("select count(*) from beiming_notification_deliveries where 1 = 1");
    var args = new ArrayList<Object>();
    if (!clean(query.status()).isBlank()) {
      sql.append(" and status = ?");
      args.add(clean(query.status()));
    }
    if (!clean(query.channel()).isBlank()) {
      sql.append(" and channel = ?");
      args.add(clean(query.channel()));
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
