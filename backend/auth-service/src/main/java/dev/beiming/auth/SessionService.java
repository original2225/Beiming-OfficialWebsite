package dev.beiming.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class SessionService {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final long sessionTtlMs;

  private final RowMapper<UserRecord> userMapper = (rs, rowNum) -> new UserRecord(
    rs.getString("id"),
    rs.getString("name"),
    rs.getString("email"),
    rs.getString("password_hash"),
    rs.getString("password_salt"),
    rs.getString("role"),
    rs.getString("status"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("last_login_at")
  ).normalized();

  SessionService(JdbcTemplate jdbc, @Value("${beiming.session-ttl-hours}") long sessionTtlHours) {
    this.jdbc = jdbc;
    this.sessionTtlMs = Math.max(1, sessionTtlHours) * 60L * 60L * 1000L;
  }

  String createSession(UserRecord user) {
    var token = randomToken(36);
    var now = now();
    jdbc.update(
      "insert into beiming_sessions (token_hash, user_id, created_at, expires_at) values (?, ?, ?, ?)",
      hashToken(token),
      user.id(),
      now,
      now + sessionTtlMs
    );
    return token;
  }

  void logout(String token) {
    if (token == null || token.isBlank()) return;
    jdbc.update("delete from beiming_sessions where token_hash = ?", hashToken(token));
  }

  void revokeUserSessions(String userId) {
    jdbc.update("delete from beiming_sessions where user_id = ?", userId);
  }

  void revokeOtherSessions(String userId, String token) {
    jdbc.update("delete from beiming_sessions where user_id = ? and token_hash <> ?", userId, hashToken(token));
  }

  UserRecord requireUser(String token) {
    if (token == null || token.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
    cleanupExpiredSessions();
    var rows = jdbc.query(
      """
        select u.* from beiming_sessions s
        join beiming_users u on u.id = s.user_id
        where s.token_hash = ? and s.expires_at > ?
      """,
      userMapper,
      hashToken(token),
      now()
    );
    if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期");
    var user = rows.get(0);
    if (!UserStatus.ACTIVE.name().equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
    return user;
  }

  private void cleanupExpiredSessions() {
    jdbc.update("delete from beiming_sessions where expires_at <= ?", now());
  }

  private String hashToken(String token) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Token 校验失败");
    }
  }

  private String randomToken(int bytes) {
    var value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
