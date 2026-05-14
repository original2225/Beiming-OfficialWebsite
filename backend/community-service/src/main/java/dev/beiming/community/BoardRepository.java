package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class BoardRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<BoardRecord> mapper = (rs, rowNum) -> new BoardRecord(
    rs.getString("id"),
    rs.getString("slug"),
    rs.getString("name"),
    rs.getString("description"),
    rs.getString("visibility"),
    rs.getString("posting_role"),
    rs.getInt("sort_order"),
    rs.getLong("created_at"),
    rs.getLong("updated_at")
  );

  BoardRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<BoardRecord> all() {
    return jdbc.query("select * from beiming_community_boards order by sort_order asc, created_at asc", mapper);
  }

  Optional<BoardRecord> findById(String boardId) {
    return jdbc.query("select * from beiming_community_boards where id = ?", mapper, boardId).stream().findFirst();
  }

  Optional<BoardRecord> findBySlug(String slug) {
    return jdbc.query("select * from beiming_community_boards where slug = ?", mapper, slug).stream().findFirst();
  }

  void insert(BoardRecord record) {
    jdbc.update(
      """
        insert into beiming_community_boards
        (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      record.id(),
      record.slug(),
      record.name(),
      record.description(),
      record.visibility(),
      record.postingRole(),
      record.sortOrder(),
      record.createdAt(),
      record.updatedAt()
    );
  }

  void update(BoardRecord record) {
    jdbc.update(
      """
        update beiming_community_boards
        set name = ?, description = ?, visibility = ?, posting_role = ?, sort_order = ?, updated_at = ?
        where id = ?
      """,
      record.name(),
      record.description(),
      record.visibility(),
      record.postingRole(),
      record.sortOrder(),
      record.updatedAt(),
      record.id()
    );
  }
}
