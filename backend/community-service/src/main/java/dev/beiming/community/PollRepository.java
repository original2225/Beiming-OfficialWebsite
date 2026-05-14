package dev.beiming.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PollRepository {
  private final JdbcTemplate jdbc;

  private final RowMapper<PollRecord> pollMapper = (rs, rowNum) -> new PollRecord(
    rs.getString("id"),
    rs.getString("post_id"),
    rs.getString("question"),
    rs.getString("vote_mode"),
    rs.getString("result_visibility"),
    rs.getLong("closes_at"),
    rs.getBoolean("closed"),
    rs.getLong("created_at"),
    rs.getLong("updated_at")
  );

  private final RowMapper<PollOptionRecord> optionMapper = (rs, rowNum) -> new PollOptionRecord(
    rs.getString("id"),
    rs.getString("poll_id"),
    rs.getString("option_text"),
    rs.getInt("sort_order")
  );

  PollRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<PollRecord> findByPostId(String postId) {
    return jdbc.query("select * from beiming_community_polls where post_id = ?", pollMapper, postId).stream().findFirst();
  }

  List<PollOptionRecord> options(String pollId) {
    return jdbc.query("select * from beiming_community_poll_options where poll_id = ? order by sort_order asc", optionMapper, pollId);
  }

  boolean hasVotes(String pollId) {
    var count = jdbc.queryForObject("select count(*) from beiming_community_poll_votes where poll_id = ?", Integer.class, pollId);
    return count != null && count > 0;
  }

  List<String> userOptionIds(String pollId, String userId) {
    return jdbc.query(
      "select option_id from beiming_community_poll_votes where poll_id = ? and user_id = ? order by created_at asc",
      (rs, rowNum) -> rs.getString(1),
      pollId,
      userId
    );
  }

  long countVotesForOption(String optionId) {
    var count = jdbc.queryForObject("select count(*) from beiming_community_poll_votes where option_id = ?", Long.class, optionId);
    return count == null ? 0L : count;
  }

  long totalVotes(String pollId) {
    var count = jdbc.queryForObject("select count(*) from beiming_community_poll_votes where poll_id = ?", Long.class, pollId);
    return count == null ? 0L : count;
  }

  void insert(PollRecord poll, List<PollOptionRecord> options) {
    jdbc.update(
      """
        insert into beiming_community_polls
        (id, post_id, question, vote_mode, result_visibility, closes_at, closed, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      poll.id(),
      poll.postId(),
      poll.question(),
      poll.voteMode(),
      poll.resultVisibility(),
      poll.closesAt(),
      poll.closed(),
      poll.createdAt(),
      poll.updatedAt()
    );
    for (var option : options) {
      jdbc.update(
        "insert into beiming_community_poll_options (id, poll_id, option_text, sort_order) values (?, ?, ?, ?)",
        option.id(),
        option.pollId(),
        option.optionText(),
        option.sortOrder()
      );
    }
  }

  void replaceVotes(String pollId, String userId, List<String> optionIds) {
    jdbc.update("delete from beiming_community_poll_votes where poll_id = ? and user_id = ?", pollId, userId);
    for (var optionId : optionIds) {
      jdbc.update(
        "insert into beiming_community_poll_votes (id, poll_id, option_id, user_id, created_at) values (?, ?, ?, ?, ?)",
        "poll-vote-" + java.util.UUID.randomUUID().toString().substring(0, 8),
        pollId,
        optionId,
        userId,
        CommunityRules.now()
      );
    }
  }

  void removeVotes(String pollId, String userId) {
    jdbc.update("delete from beiming_community_poll_votes where poll_id = ? and user_id = ?", pollId, userId);
  }
}
