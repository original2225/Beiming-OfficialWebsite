package dev.beiming.community;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RateLimitRepository {
  private final JdbcTemplate jdbc;

  RateLimitRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean tryAcquire(String scope, long bucketStart, int maxRequests) {
    if (maxRequests <= 0) return false;
    try {
      return jdbc.update(
        """
          insert into beiming_community_rate_limits (scope, bucket_start, request_count, updated_at)
          values (?, ?, 1, ?)
        """,
        scope,
        bucketStart,
        CommunityRules.now()
      ) > 0;
    } catch (DuplicateKeyException ignored) {
      return jdbc.update(
        """
          update beiming_community_rate_limits
          set request_count = request_count + 1, updated_at = ?
          where scope = ? and bucket_start = ? and request_count < ?
        """,
        CommunityRules.now(),
        scope,
        bucketStart,
        maxRequests
      ) > 0;
    }
  }

  void deleteOlderThan(long updatedBefore) {
    jdbc.update("delete from beiming_community_rate_limits where updated_at < ?", updatedBefore);
  }
}
