package dev.beiming.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
  private static final TypeReference<List<UserRecord>> USER_LIST = new TypeReference<>() {};
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final Path legacyUsersPath;
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

  AuthService(ObjectMapper mapper, JdbcTemplate jdbc, @Value("${beiming.data-dir}") String dataDir, @Value("${beiming.session-ttl-hours}") long sessionTtlHours) {
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.legacyUsersPath = Path.of(dataDir).resolve("users.json");
    this.sessionTtlMs = Math.max(1, sessionTtlHours) * 60L * 60L * 1000L;
    initializeSchema();
    migrateLegacyUsers();
  }

  synchronized Map<String, Object> register(Map<String, Object> body) {
    var name = string(body.get("name")).trim();
    var email = normalizeEmail(body.get("email"));
    var password = string(body.get("password"));
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "用户名不能为空");
    validateEmail(email);
    validatePassword(password);
    if (findUserByEmail(email).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "邮箱已经注册");
    var now = now();
    var salt = randomToken(18);
    var user = new UserRecord(
      "user-" + UUID.randomUUID().toString().substring(0, 8),
      name,
      email,
      hashPassword(password, salt),
      salt,
      countUsers() == 0 ? "SUPER_ADMIN" : "MEMBER",
      "ACTIVE",
      now,
      now,
      now
    ).normalized();
    insertUser(user);
    return loginPayload(user);
  }

  synchronized Map<String, Object> login(Map<String, Object> body) {
    var email = normalizeEmail(body.get("email"));
    var password = string(body.get("password"));
    validateEmail(email);
    var user = findUserByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"));
    if (!"ACTIVE".equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
    if (!MessageDigest.isEqual(hashPassword(password, user.passwordSalt()).getBytes(StandardCharsets.UTF_8), user.passwordHash().getBytes(StandardCharsets.UTF_8))) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
    }
    updateUserLastLogin(user.id(), now());
    return loginPayload(getUserRecord(user.id()));
  }

  synchronized void logout(String token) {
    if (token == null || token.isBlank()) return;
    jdbc.update("delete from beiming_sessions where token_hash = ?", hashToken(token));
  }

  synchronized Map<String, Object> me(String token) {
    return publicUser(requireUser(token));
  }

  synchronized List<Map<String, Object>> publicUsers(String token) {
    requireUser(token);
    return allUsers().stream().map(this::publicUser).toList();
  }

  synchronized Map<String, Object> updateUser(String token, String userId, Map<String, Object> body) {
    var actor = requireUser(token);
    if (!"SUPER_ADMIN".equals(actor.role()) && !actor.id().equals(userId)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限修改该用户");
    }
    var patch = new LinkedHashMap<>(body);
    if (!"SUPER_ADMIN".equals(actor.role())) {
      patch.remove("role");
      patch.remove("status");
    }
    updateUser(userId, patch);
    return publicUser(getUserRecord(userId));
  }

  synchronized Map<String, Object> validate(String token) {
    return Map.of("user", publicUser(requireUser(token)));
  }

  private Map<String, Object> loginPayload(UserRecord user) {
    var token = randomToken(36);
    var now = now();
    jdbc.update(
      "insert into beiming_sessions (token_hash, user_id, created_at, expires_at) values (?, ?, ?, ?)",
      hashToken(token),
      user.id(),
      now,
      now + sessionTtlMs
    );
    return Map.of("token", token, "user", publicUser(user));
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
    return rows.get(0);
  }

  private void updateUser(String userId, Map<String, Object> patch) {
    var user = getUserRecord(userId);
    var next = new UserRecord(
      user.id(),
      string(patch.getOrDefault("name", user.name())).trim(),
      user.email(),
      user.passwordHash(),
      user.passwordSalt(),
      string(patch.getOrDefault("role", user.role())).trim(),
      string(patch.getOrDefault("status", user.status())).trim(),
      user.createdAt(),
      now(),
      number(patch.getOrDefault("lastLoginAt", user.lastLoginAt()))
    ).normalized();
    if (next.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "用户名不能为空");
    if (!Set.of("SUPER_ADMIN", "ADMIN", "MEMBER").contains(next.role())) throw new ApiException(HttpStatus.BAD_REQUEST, "无效角色");
    if (!Set.of("ACTIVE", "DISABLED").contains(next.status())) throw new ApiException(HttpStatus.BAD_REQUEST, "无效状态");
    jdbc.update(
      "update beiming_users set name = ?, role = ?, status = ?, updated_at = ?, last_login_at = ? where id = ?",
      next.name(),
      next.role(),
      next.status(),
      next.updatedAt(),
      next.lastLoginAt(),
      next.id()
    );
  }

  private void initializeSchema() {
    jdbc.execute("""
      create table if not exists beiming_users (
        id varchar(64) primary key,
        name varchar(120) not null,
        email varchar(255) not null unique,
        password_hash text not null,
        password_salt text not null,
        role varchar(32) not null,
        status varchar(32) not null,
        created_at bigint not null,
        updated_at bigint not null,
        last_login_at bigint not null
      )
    """);
    jdbc.execute("""
      create table if not exists beiming_sessions (
        token_hash varchar(128) primary key,
        user_id varchar(64) not null references beiming_users(id) on delete cascade,
        created_at bigint not null,
        expires_at bigint not null
      )
    """);
    jdbc.execute("create index if not exists idx_beiming_sessions_user_id on beiming_sessions(user_id)");
    jdbc.execute("create index if not exists idx_beiming_sessions_expires_at on beiming_sessions(expires_at)");
  }

  private void migrateLegacyUsers() {
    if (countUsers() > 0 || !Files.exists(legacyUsersPath)) return;
    try {
      var legacyUsers = mapper.readValue(Files.readString(legacyUsersPath), USER_LIST);
      for (var user : legacyUsers) {
        var normalized = user.normalized();
        if (!normalized.id().isBlank() && !normalized.email().isBlank() && findUserByEmail(normalized.email()).isEmpty()) {
          insertUser(normalized);
        }
      }
    } catch (Exception error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "迁移本地用户失败: " + error.getMessage());
    }
  }

  private void insertUser(UserRecord user) {
    jdbc.update(
      """
        insert into beiming_users
        (id, name, email, password_hash, password_salt, role, status, created_at, updated_at, last_login_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      user.id(),
      user.name(),
      user.email(),
      user.passwordHash(),
      user.passwordSalt(),
      user.role(),
      user.status(),
      user.createdAt(),
      user.updatedAt(),
      user.lastLoginAt()
    );
  }

  private Optional<UserRecord> findUserByEmail(String email) {
    var rows = jdbc.query("select * from beiming_users where email = ?", userMapper, email);
    return rows.stream().findFirst();
  }

  private UserRecord getUserRecord(String userId) {
    var rows = jdbc.query("select * from beiming_users where id = ?", userMapper, userId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期");
    return rows.get(0);
  }

  private List<UserRecord> allUsers() {
    return jdbc.query("select * from beiming_users order by created_at asc", userMapper);
  }

  private int countUsers() {
    var count = jdbc.queryForObject("select count(*) from beiming_users", Integer.class);
    return count == null ? 0 : count;
  }

  private void updateUserLastLogin(String userId, long value) {
    jdbc.update("update beiming_users set last_login_at = ?, updated_at = ? where id = ?", value, value, userId);
  }

  private void cleanupExpiredSessions() {
    jdbc.update("delete from beiming_sessions where expires_at <= ?", now());
  }

  private Map<String, Object> publicUser(UserRecord user) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", user.id());
    item.put("name", user.name());
    item.put("email", user.email());
    item.put("role", user.role());
    item.put("status", user.status());
    item.put("createdAt", user.createdAt());
    item.put("updatedAt", user.updatedAt());
    item.put("lastLoginAt", user.lastLoginAt());
    return item;
  }

  private String hashPassword(String password, String salt) {
    try {
      var spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 120_000, 256);
      return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
    } catch (Exception error) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密码哈希失败");
    }
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

  private void validateEmail(String email) {
    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ApiException(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
  }

  private void validatePassword(String password) {
    if (password.length() < 8) throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少 8 位");
  }

  private String normalizeEmail(Object value) {
    return string(value).trim().toLowerCase();
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private long number(Object value) {
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(string(value));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
