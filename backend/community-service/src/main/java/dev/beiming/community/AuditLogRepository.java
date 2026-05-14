package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AuditLogRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<AuditLogRecord> mapper = (rs, rowNum) -> new AuditLogRecord(
    rs.getString("id"),
    rs.getString("actor_user_id"),
    rs.getString("actor_display_name"),
    rs.getString("action"),
    rs.getString("target_type"),
    rs.getString("target_id"),
    rs.getString("detail"),
    rs.getLong("created_at")
  );

  AuditLogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void insert(AuditLogRecord record) {
    jdbc.update(
      """
        insert into beiming_community_audit_logs
        (id, actor_user_id, actor_display_name, action, target_type, target_id, detail, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.actorUserId(),
      record.actorDisplayName(),
      record.action(),
      record.targetType(),
      record.targetId(),
      record.detail(),
      record.createdAt()
    );
  }

  List<AuditLogRecord> list(int page, int pageSize) {
    return jdbc.query(
      """
        select * from beiming_community_audit_logs
        order by created_at desc
        limit ? offset ?
      """,
      mapper,
      pageSize,
      (page - 1) * pageSize
    );
  }

  int count() {
    var count = jdbc.queryForObject("select count(*) from beiming_community_audit_logs", Integer.class);
    return count == null ? 0 : count;
  }
}
