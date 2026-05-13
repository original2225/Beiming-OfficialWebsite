package dev.beiming.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProfileRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<MemberProfileRecord> mapper = (rs, rowNum) -> new MemberProfileRecord(
    rs.getString("id"),
    rs.getString("user_id"),
    rs.getString("display_name"),
    rs.getString("bio"),
    rs.getString("avatar_url"),
    rs.getString("minecraft_id"),
    rs.getString("minecraft_id_key"),
    rs.getString("minecraft_uuid"),
    rs.getString("member_group"),
    rs.getString("member_status"),
    rs.getString("visibility"),
    rs.getLong("joined_at"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getString("admin_note"),
    rs.getBoolean("featured"),
    rs.getString("created_by"),
    rs.getString("updated_by")
  );

  ProfileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<MemberProfileRecord> findByUserId(String userId) {
    return jdbc.query("select * from beiming_member_profiles where user_id = ?", mapper, userId).stream().findFirst();
  }

  Optional<MemberProfileRecord> findById(String id) {
    return jdbc.query("select * from beiming_member_profiles where id = ?", mapper, id).stream().findFirst();
  }

  Optional<MemberProfileRecord> findByMinecraftIdKey(String minecraftIdKey) {
    return jdbc.query("select * from beiming_member_profiles where minecraft_id_key = ?", mapper, minecraftIdKey).stream().findFirst();
  }

  void insert(MemberProfileRecord record) {
    jdbc.update(
      """
        insert into beiming_member_profiles
        (id, user_id, display_name, bio, avatar_url, minecraft_id, minecraft_id_key, minecraft_uuid, member_group, member_status, visibility, joined_at, created_at, updated_at, admin_note, featured, created_by, updated_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.userId(),
      record.displayName(),
      record.bio(),
      record.avatarUrl(),
      record.minecraftId(),
      record.minecraftIdKey(),
      record.minecraftUuid(),
      record.memberGroup(),
      record.memberStatus(),
      record.visibility(),
      record.joinedAt(),
      record.createdAt(),
      record.updatedAt(),
      record.adminNote(),
      record.featured(),
      record.createdBy(),
      record.updatedBy()
    );
  }

  void update(MemberProfileRecord record) {
    jdbc.update(
      """
        update beiming_member_profiles
        set display_name = ?, bio = ?, avatar_url = ?, minecraft_id = ?, minecraft_id_key = ?, minecraft_uuid = ?,
            member_group = ?, member_status = ?, visibility = ?, joined_at = ?, updated_at = ?, admin_note = ?, featured = ?, updated_by = ?
        where id = ?
      """,
      record.displayName(),
      record.bio(),
      record.avatarUrl(),
      record.minecraftId(),
      record.minecraftIdKey(),
      record.minecraftUuid(),
      record.memberGroup(),
      record.memberStatus(),
      record.visibility(),
      record.joinedAt(),
      record.updatedAt(),
      record.adminNote(),
      record.featured(),
      record.updatedBy(),
      record.id()
    );
  }

  List<MemberProfileRecord> publicProfiles(int page, int pageSize, String q) {
    var term = queryTerm(q);
    return jdbc.query(
      """
        select * from beiming_member_profiles
        where visibility = 'PUBLIC'
          and member_status = 'ACTIVE'
          and (? = '' or lower(display_name) like ? or lower(minecraft_id) like ?)
        order by featured desc, joined_at desc, created_at desc
        limit ? offset ?
      """,
      mapper,
      term.raw(),
      term.like(),
      term.like(),
      pageSize,
      offset(page, pageSize)
    );
  }

  int countPublicProfiles(String q) {
    var term = queryTerm(q);
    var count = jdbc.queryForObject(
      """
        select count(*) from beiming_member_profiles
        where visibility = 'PUBLIC'
          and member_status = 'ACTIVE'
          and (? = '' or lower(display_name) like ? or lower(minecraft_id) like ?)
      """,
      Integer.class,
      term.raw(),
      term.like(),
      term.like()
    );
    return count == null ? 0 : count;
  }

  List<MemberProfileRecord> allProfiles(int page, int pageSize, String q) {
    var term = queryTerm(q);
    return jdbc.query(
      """
        select * from beiming_member_profiles
        where ? = '' or lower(display_name) like ? or lower(minecraft_id) like ? or lower(user_id) like ?
        order by created_at asc
        limit ? offset ?
      """,
      mapper,
      term.raw(),
      term.like(),
      term.like(),
      term.like(),
      pageSize,
      offset(page, pageSize)
    );
  }

  int countAllProfiles(String q) {
    var term = queryTerm(q);
    var count = jdbc.queryForObject(
      """
        select count(*) from beiming_member_profiles
        where ? = '' or lower(display_name) like ? or lower(minecraft_id) like ? or lower(user_id) like ?
      """,
      Integer.class,
      term.raw(),
      term.like(),
      term.like(),
      term.like()
    );
    return count == null ? 0 : count;
  }

  private int offset(int page, int pageSize) {
    return (Math.max(page, 1) - 1) * pageSize;
  }

  private QueryTerm queryTerm(String q) {
    var raw = q == null ? "" : q.trim().toLowerCase();
    return new QueryTerm(raw, raw.isBlank() ? "" : "%" + raw + "%");
  }

  private record QueryTerm(String raw, String like) {
  }
}
