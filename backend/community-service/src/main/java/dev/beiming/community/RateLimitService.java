package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
  private static final long MINUTE = 60_000L;
  private static final long CLEANUP_INTERVAL = 10 * MINUTE;
  private static final long RETENTION = 2 * 24 * 60 * MINUTE;

  private final RateLimitRepository limits;
  private long nextCleanupAt = 0L;

  RateLimitService(RateLimitRepository limits) {
    this.limits = limits;
  }

  void posts(CurrentUserView user) {
    requireAllowed(user, "posts", 8, MINUTE);
  }

  void comments(CurrentUserView user) {
    requireAllowed(user, "comments", 30, MINUTE);
  }

  void reactions(CurrentUserView user) {
    requireAllowed(user, "reactions", 180, MINUTE);
  }

  void favorites(CurrentUserView user) {
    requireAllowed(user, "favorites", 90, MINUTE);
  }

  void reports(CurrentUserView user) {
    requireAllowed(user, "reports", 6, 10 * MINUTE);
  }

  void polls(CurrentUserView user) {
    requireAllowed(user, "polls", 60, MINUTE);
  }

  private void requireAllowed(CurrentUserView user, String action, int maxRequests, long windowMillis) {
    if (user != null && user.isAdmin()) return;
    var userId = user == null ? "anonymous" : user.id();
    var now = CommunityRules.now();
    cleanupIfNeeded(now);
    var bucketStart = now - (now % windowMillis);
    var scope = action + ":" + userId;
    if (!limits.tryAcquire(scope, bucketStart, maxRequests)) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
    }
  }

  private synchronized void cleanupIfNeeded(long now) {
    if (now < nextCleanupAt) return;
    nextCleanupAt = now + CLEANUP_INTERVAL;
    limits.deleteOlderThan(now - RETENTION);
  }
}
