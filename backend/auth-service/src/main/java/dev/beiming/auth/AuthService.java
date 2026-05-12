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
  private static final Set<String> ROLES = Set.of("SUPER_ADMIN", "ADMIN", "MEMBER");
  private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "DISABLED");
  private static final long DEFAULT_INVITE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

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

  private final RowMapper<InviteCodeRecord> inviteCodeMapper = (rs, rowNum) -> new InviteCodeRecord(
    rs.getString("id"),
    rs.getString("code"),
    rs.getString("type"),
    rs.getString("role"),
    rs.getString("status"),
    rs.getInt("max_uses"),
    rs.getInt("used_count"),
    rs.getLong("expires_at"),
    rs.getString("created_by"),
    rs.getLong("created_at"),
    rs.getLong("updated_at")
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
    var firstUser = countUsers() == 0;
    var role = firstUser ? "SUPER_ADMIN" : consumeInviteCode(string(body.get("inviteCode")));
    var now = now();
    var salt = randomToken(18);
    var user = new UserRecord(
      "user-" + UUID.randomUUID().toString().substring(0, 8),
      name,
      email,
      hashPassword(password, salt),
      salt,
      role,
      "ACTIVE",
      now,
      now,
      now
    ).normalized();
    insertUser(user);
    if (!firstUser) recordInviteUsage(string(body.get("inviteCode")), user.id());
    return loginPayload(user);
  }

  synchronized Map<String, Object> login(Map<String, Object> body) {
    var email = normalizeEmail(body.get("email"));
    var password = string(body.get("password"));
    validateEmail(email);
    var user = findUserByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"));
    if (!"ACTIVE".equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
    if (!passwordMatches(password, user)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
    }
    updateUserLastLogin(user.id(), now());
    return loginPayload(getUserRecord(user.id()));
  }

  synchronized void logout(String token) {
    if (token == null || token.isBlank()) return;
    jdbc.update("delete from beiming_sessions where token_hash = ?", hashToken(token));
  }

  synchronized Map<String, Object> logoutAll(String token) {
    var user = requireUser(token);
    jdbc.update("delete from beiming_sessions where user_id = ?", user.id());
    return Map.of("loggedOut", true);
  }

  synchronized Map<String, Object> changePassword(String token, Map<String, Object> body) {
    var user = requireUser(token);
    var currentPassword = string(body.get("currentPassword"));
    var newPassword = string(body.get("newPassword"));
    validatePassword(newPassword);
    if (!passwordMatches(currentPassword, user)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
    }
    var now = now();
    var salt = randomToken(18);
    jdbc.update(
      "update beiming_users set password_hash = ?, password_salt = ?, updated_at = ? where id = ?",
      hashPassword(newPassword, salt),
      salt,
      now,
      user.id()
    );
    jdbc.update("delete from beiming_sessions where user_id = ? and token_hash <> ?", user.id(), hashToken(token));
    return Map.of("passwordChanged", true);
  }

  synchronized Map<String, Object> me(String token) {
    return publicUser(requireUser(token));
  }

  synchronized List<Map<String, Object>> publicUsers(String token) {
    requireAdmin(token);
    return allUsers().stream().map(this::publicUser).toList();
  }

  synchronized Map<String, Object> publicUser(String token, String userId) {
    var actor = requireUser(token);
    var target = getUserRecord(userId);
    if ("MEMBER".equals(actor.role()) && !actor.id().equals(target.id())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限查看该用户");
    }
    if ("ADMIN".equals(actor.role()) && "SUPER_ADMIN".equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限查看超级管理员");
    }
    return publicUser(target);
  }

  synchronized Map<String, Object> createInviteCode(String token, Map<String, Object> body) {
    var actor = requireAdmin(token);
    var role = normalizeRole(body.getOrDefault("role", "MEMBER"));
    if ("SUPER_ADMIN".equals(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "不能创建超级管理员邀请码");
    if ("ADMIN".equals(role) && !"SUPER_ADMIN".equals(actor.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限创建管理员邀请码");
    }
    var maxUses = (int) Math.max(1, number(body.getOrDefault("maxUses", 1)));
    var now = now();
    var expiresAt = number(body.get("expiresAt"));
    if (expiresAt <= 0) expiresAt = now + DEFAULT_INVITE_TTL_MS;
    if (expiresAt <= now) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码过期时间无效");
    var invite = new InviteCodeRecord(
      "invite-" + UUID.randomUUID().toString().substring(0, 8),
      uniqueInviteCode(),
      "ADMIN".equals(role) ? "ADMIN" : "MEMBER",
      role,
      "ACTIVE",
      maxUses,
      0,
      expiresAt,
      actor.id(),
      now,
      now
    ).normalized();
    insertInviteCode(invite);
    return inviteCodeView(invite);
  }

  synchronized List<Map<String, Object>> inviteCodes(String token) {
    requireAdmin(token);
    return allInviteCodes().stream().map(this::inviteCodeView).toList();
  }

  synchronized Map<String, Object> disableInviteCode(String token, String inviteCodeId) {
    var actor = requireAdmin(token);
    var invite = getInviteCode(inviteCodeId);
    if ("ADMIN".equals(actor.role()) && !"MEMBER".equals(invite.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限禁用该邀请码");
    }
    var now = now();
    jdbc.update("update beiming_invite_codes set status = ?, updated_at = ? where id = ?", "DISABLED", now, invite.id());
    return inviteCodeView(getInviteCode(invite.id()));
  }

  synchronized Map<String, Object> updateUser(String token, String userId, Map<String, Object> body) {
    var actor = requireUser(token);
    var target = getUserRecord(userId);
    if ("MEMBER".equals(actor.role()) && !actor.id().equals(userId)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限修改该用户");
    }
    if ("ADMIN".equals(actor.role()) && "SUPER_ADMIN".equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "不能修改超级管理员");
    }
    if ("ADMIN".equals(actor.role()) && "ADMIN".equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "不能修改管理员");
    }
    var patch = new LinkedHashMap<String, Object>();
    if (body.containsKey("name")) patch.put("name", body.get("name"));
    if ("MEMBER".equals(actor.role())) {
      updateUser(userId, patch);
      return publicUser(getUserRecord(userId));
    }
    if (body.containsKey("status")) patch.put("status", body.get("status"));
    if ("SUPER_ADMIN".equals(actor.role()) && body.containsKey("role")) patch.put("role", body.get("role"));
    protectSuperAdminChange(target, patch);
    updateUser(userId, patch);
    return publicUser(getUserRecord(userId));
  }

  synchronized Map<String, Object> revokeUserSessions(String token, String userId) {
    var actor = requireAdmin(token);
    var target = getUserRecord(userId);
    if ("ADMIN".equals(actor.role()) && !"MEMBER".equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限踢出该用户");
    }
    jdbc.update("delete from beiming_sessions where user_id = ?", target.id());
    return Map.of("revoked", true);
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
    var user = rows.get(0);
    if (!"ACTIVE".equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
    return user;
  }

  private UserRecord requireAdmin(String token) {
    var user = requireUser(token);
    if (!Set.of("SUPER_ADMIN", "ADMIN").contains(user.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有管理员权限");
    }
    return user;
  }

  private void updateUser(String userId, Map<String, Object> patch) {
    var user = getUserRecord(userId);
    var role = string(patch.getOrDefault("role", user.role())).trim();
    var status = string(patch.getOrDefault("status", user.status())).trim();
    var next = new UserRecord(
      user.id(),
      string(patch.getOrDefault("name", user.name())).trim(),
      user.email(),
      user.passwordHash(),
      user.passwordSalt(),
      role,
      status,
      user.createdAt(),
      now(),
      user.lastLoginAt()
    ).normalized();
    if (next.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "用户名不能为空");
    if (!ROLES.contains(next.role())) throw new ApiException(HttpStatus.BAD_REQUEST, "无效角色");
    if (!USER_STATUSES.contains(next.status())) throw new ApiException(HttpStatus.BAD_REQUEST, "无效状态");
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
    jdbc.execute("""
      create table if not exists beiming_invite_codes (
        id varchar(64) primary key,
        code varchar(64) not null unique,
        type varchar(32) not null,
        role varchar(32) not null,
        status varchar(32) not null,
        max_uses int not null,
        used_count int not null,
        expires_at bigint not null,
        created_by varchar(64) not null,
        created_at bigint not null,
        updated_at bigint not null
      )
    """);
    jdbc.execute("""
      create table if not exists beiming_invite_code_usages (
        id varchar(64) primary key,
        invite_code_id varchar(64) not null,
        user_id varchar(64) not null,
        used_at bigint not null
      )
    """);
    jdbc.execute("create index if not exists idx_beiming_invite_codes_code on beiming_invite_codes(code)");
    jdbc.execute("create index if not exists idx_beiming_invite_code_usages_invite_code_id on beiming_invite_code_usages(invite_code_id)");
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

  private void insertInviteCode(InviteCodeRecord invite) {
    jdbc.update(
      """
        insert into beiming_invite_codes
        (id, code, type, role, status, max_uses, used_count, expires_at, created_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      invite.id(),
      invite.code(),
      invite.type(),
      invite.role(),
      invite.status(),
      invite.maxUses(),
      invite.usedCount(),
      invite.expiresAt(),
      invite.createdBy(),
      invite.createdAt(),
      invite.updatedAt()
    );
  }

  private void protectSuperAdminChange(UserRecord target, Map<String, Object> patch) {
    if (!"SUPER_ADMIN".equals(target.role())) return;
    var nextRole = string(patch.getOrDefault("role", target.role())).trim();
    var nextStatus = string(patch.getOrDefault("status", target.status())).trim();
    if (!"SUPER_ADMIN".equals(nextRole) || !"ACTIVE".equals(nextStatus)) {
      var activeSuperAdmins = jdbc.queryForObject(
        "select count(*) from beiming_users where role = ? and status = ?",
        Integer.class,
        "SUPER_ADMIN",
        "ACTIVE"
      );
      if (activeSuperAdmins == null || activeSuperAdmins <= 1) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "至少保留一个可用超级管理员");
      }
    }
  }

  private String consumeInviteCode(String code) {
    var normalizedCode = string(code).trim();
    if (normalizedCode.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码不能为空");
    var invite = findInviteCodeByCode(normalizedCode)
      .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "邀请码无效"));
    if (!"ACTIVE".equals(invite.status())) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已禁用");
    if (invite.expiresAt() <= now()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已过期");
    if (invite.usedCount() >= invite.maxUses()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已用完");
    return invite.role();
  }

  private void recordInviteUsage(String code, String userId) {
    var invite = findInviteCodeByCode(code.trim())
      .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "邀请码无效"));
    var now = now();
    jdbc.update(
      "update beiming_invite_codes set used_count = used_count + 1, updated_at = ? where id = ?",
      now,
      invite.id()
    );
    jdbc.update(
      "insert into beiming_invite_code_usages (id, invite_code_id, user_id, used_at) values (?, ?, ?, ?)",
      "invite-use-" + UUID.randomUUID().toString().substring(0, 8),
      invite.id(),
      userId,
      now
    );
  }

  private Optional<UserRecord> findUserByEmail(String email) {
    var rows = jdbc.query("select * from beiming_users where email = ?", userMapper, email);
    return rows.stream().findFirst();
  }

  private Optional<InviteCodeRecord> findInviteCodeByCode(String code) {
    var rows = jdbc.query("select * from beiming_invite_codes where code = ?", inviteCodeMapper, code);
    return rows.stream().findFirst();
  }

  private InviteCodeRecord getInviteCode(String inviteCodeId) {
    var rows = jdbc.query("select * from beiming_invite_codes where id = ?", inviteCodeMapper, inviteCodeId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "邀请码不存在");
    return rows.get(0);
  }

  private UserRecord getUserRecord(String userId) {
    var rows = jdbc.query("select * from beiming_users where id = ?", userMapper, userId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期");
    return rows.get(0);
  }

  private List<UserRecord> allUsers() {
    return jdbc.query("select * from beiming_users order by created_at asc", userMapper);
  }

  private List<InviteCodeRecord> allInviteCodes() {
    return jdbc.query("select * from beiming_invite_codes order by created_at asc", inviteCodeMapper);
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

  private Map<String, Object> inviteCodeView(InviteCodeRecord invite) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", invite.id());
    item.put("code", invite.code());
    item.put("type", invite.type());
    item.put("role", invite.role());
    item.put("status", invite.status());
    item.put("maxUses", invite.maxUses());
    item.put("usedCount", invite.usedCount());
    item.put("expiresAt", invite.expiresAt());
    item.put("createdBy", invite.createdBy());
    item.put("createdAt", invite.createdAt());
    item.put("updatedAt", invite.updatedAt());
    return item;
  }

  private boolean passwordMatches(String password, UserRecord user) {
    return MessageDigest.isEqual(
      hashPassword(password, user.passwordSalt()).getBytes(StandardCharsets.UTF_8),
      user.passwordHash().getBytes(StandardCharsets.UTF_8)
    );
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

  private String uniqueInviteCode() {
    for (var attempt = 0; attempt < 5; attempt++) {
      var code = randomToken(12);
      if (findInviteCodeByCode(code).isEmpty()) return code;
    }
    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "生成邀请码失败");
  }

  private String normalizeRole(Object value) {
    var role = string(value).trim().toUpperCase(Locale.ROOT);
    if (!ROLES.contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "无效角色");
    return role;
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
