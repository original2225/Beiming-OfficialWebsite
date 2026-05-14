package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ReportRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<ReportRecord> mapper = (rs, rowNum) -> new ReportRecord(
    rs.getString("id"),
    rs.getString("target_type"),
    rs.getString("target_id"),
    rs.getString("reporter_user_id"),
    rs.getString("reporter_display_name"),
    rs.getString("reason"),
    rs.getString("detail"),
    rs.getString("status"),
    rs.getString("reviewer_user_id"),
    rs.getString("review_note"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("resolved_at")
  );

  ReportRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean hasOpenDuplicate(String targetType, String targetId, String reporterUserId, String reason) {
    var count = jdbc.queryForObject(
      """
        select count(*) from beiming_community_reports
        where target_type = ? and target_id = ? and reporter_user_id = ? and reason = ? and status = 'OPEN'
      """,
      Integer.class,
      targetType,
      targetId,
      reporterUserId,
      reason
    );
    return count != null && count > 0;
  }

  void insert(ReportRecord record) {
    jdbc.update(
      """
        insert into beiming_community_reports
        (id, target_type, target_id, reporter_user_id, reporter_display_name, reason, detail, status, reviewer_user_id, review_note, created_at, updated_at, resolved_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.targetType(),
      record.targetId(),
      record.reporterUserId(),
      record.reporterDisplayName(),
      record.reason(),
      record.detail(),
      record.status(),
      record.reviewerUserId(),
      record.reviewNote(),
      record.createdAt(),
      record.updatedAt(),
      record.resolvedAt()
    );
  }

  Optional<ReportRecord> findById(String reportId) {
    return jdbc.query("select * from beiming_community_reports where id = ?", mapper, reportId).stream().findFirst();
  }

  List<ReportRecord> list(String status, int page, int pageSize) {
    var sql = new StringBuilder("select * from beiming_community_reports where 1 = 1");
    var args = new ArrayList<Object>();
    if (!CommunityRules.clean(status).isBlank()) {
      sql.append(" and status = ?");
      args.add(CommunityRules.clean(status).toUpperCase());
    }
    sql.append(" order by created_at desc limit ? offset ?");
    args.add(pageSize);
    args.add((page - 1) * pageSize);
    return jdbc.query(sql.toString(), mapper, args.toArray());
  }

  int count(String status) {
    if (CommunityRules.clean(status).isBlank()) {
      var count = jdbc.queryForObject("select count(*) from beiming_community_reports", Integer.class);
      return count == null ? 0 : count;
    }
    var count = jdbc.queryForObject("select count(*) from beiming_community_reports where status = ?", Integer.class, CommunityRules.clean(status).toUpperCase());
    return count == null ? 0 : count;
  }

  void update(ReportRecord record) {
    jdbc.update(
      """
        update beiming_community_reports
        set status = ?, reviewer_user_id = ?, review_note = ?, updated_at = ?, resolved_at = ?
        where id = ?
      """,
      record.status(),
      record.reviewerUserId(),
      record.reviewNote(),
      record.updatedAt(),
      record.resolvedAt(),
      record.id()
    );
  }
}
