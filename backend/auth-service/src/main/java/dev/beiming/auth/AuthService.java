package dev.beiming.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
  private static final TypeReference<List<UserRecord>> USER_LIST = new TypeReference<>() {};

  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final PasswordService passwords;
  private final SessionService sessions;
  private final InviteCodeService invites;
  private final Path legacyUsersPath;

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

  AuthService(ObjectMapper mapper, JdbcTemplate jdbc, PasswordService passwords, SessionService sessions, InviteCodeService invites, @Value("${beiming.data-dir}") String dataDir) {
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.sessions = sessions;
    this.invites = invites;
    this.legacyUsersPath = Path.of(dataDir).resolve("users.json");
    migrateLegacyUsers();
  }

  synchronized LoginResponse register(RegisterRequest body) {
    var name = string(body.name()).trim();
    var email = normalizeEmail(body.email());
    var password = string(body.password());
    if (name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "用户名不能为空");
    validateEmail(email);
    validatePassword(password);
    if (findUserByEmail(email).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "邮箱已经注册");
    var firstUser = countUsers() == 0;
    var role = firstUser ? UserRole.SUPER_ADMIN.name() : invites.consumeInviteCode(string(body.inviteCode()));
    var now = now();
    var passwordHash = passwords.hash(password);
    var user = new UserRecord(
      "user-" + UUID.randomUUID().toString().substring(0, 8),
      name,
      email,
      passwordHash.passwordHash(),
      passwordHash.passwordSalt(),
      role,
      UserStatus.ACTIVE.name(),
      now,
      now,
      now
    ).normalized();
    insertUser(user);
    if (!firstUser) invites.recordInviteUsage(string(body.inviteCode()), user.id());
    return loginPayload(user);
  }

  synchronized LoginResponse login(LoginRequest body) {
    var email = normalizeEmail(body.email());
    var password = string(body.password());
    validateEmail(email);
    var user = findUserByEmail(email).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"));
    if (!UserStatus.ACTIVE.name().equals(user.status())) throw new ApiException(HttpStatus.FORBIDDEN, "账号已被禁用");
    if (!passwords.matches(password, user.passwordHash(), user.passwordSalt())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
    }
    updateUserLastLogin(user.id(), now());
    return loginPayload(getUserRecord(user.id()));
  }

  synchronized void logout(String token) {
    sessions.logout(token);
  }

  synchronized Map<String, Object> logoutAll(String token) {
    var user = sessions.requireUser(token);
    sessions.revokeUserSessions(user.id());
    return Map.of("loggedOut", true);
  }

  synchronized Map<String, Object> changePassword(String token, ChangePasswordRequest body) {
    var user = sessions.requireUser(token);
    var currentPassword = string(body.currentPassword());
    var newPassword = string(body.newPassword());
    validatePassword(newPassword);
    if (!passwords.matches(currentPassword, user.passwordHash(), user.passwordSalt())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
    }
    var now = now();
    var passwordHash = passwords.hash(newPassword);
    jdbc.update(
      "update beiming_users set password_hash = ?, password_salt = ?, updated_at = ? where id = ?",
      passwordHash.passwordHash(),
      passwordHash.passwordSalt(),
      now,
      user.id()
    );
    sessions.revokeOtherSessions(user.id(), token);
    return Map.of("passwordChanged", true);
  }

  synchronized PublicUserView me(String token) {
    return publicUser(sessions.requireUser(token));
  }

  synchronized List<PublicUserView> publicUsers(String token) {
    requireAdmin(token);
    return allUsers().stream().map(this::publicUser).toList();
  }

  synchronized PublicUserView publicUser(String token, String userId) {
    var actor = sessions.requireUser(token);
    var target = getUserRecord(userId);
    if (UserRole.MEMBER.name().equals(actor.role()) && !actor.id().equals(target.id())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限查看该用户");
    }
    if (UserRole.ADMIN.name().equals(actor.role()) && UserRole.SUPER_ADMIN.name().equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限查看超级管理员");
    }
    return publicUser(target);
  }

  synchronized InviteCodeView createInviteCode(String token, CreateInviteCodeRequest body) {
    var actor = requireAdmin(token);
    return invites.createInviteCode(actor, body);
  }

  synchronized List<InviteCodeView> inviteCodes(String token) {
    requireAdmin(token);
    return invites.inviteCodes();
  }

  synchronized InviteCodeView disableInviteCode(String token, String inviteCodeId) {
    var actor = requireAdmin(token);
    return invites.disableInviteCode(actor, inviteCodeId);
  }

  synchronized PublicUserView updateUser(String token, String userId, UpdateUserRequest body) {
    var actor = sessions.requireUser(token);
    var target = getUserRecord(userId);
    if (UserRole.MEMBER.name().equals(actor.role()) && !actor.id().equals(userId)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限修改该用户");
    }
    if (UserRole.ADMIN.name().equals(actor.role()) && UserRole.SUPER_ADMIN.name().equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "不能修改超级管理员");
    }
    if (UserRole.ADMIN.name().equals(actor.role()) && UserRole.ADMIN.name().equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "不能修改管理员");
    }
    var patch = new LinkedHashMap<String, Object>();
    if (body.name() != null) patch.put("name", body.name());
    if (UserRole.MEMBER.name().equals(actor.role())) {
      updateUser(userId, patch);
      return publicUser(getUserRecord(userId));
    }
    if (body.status() != null) patch.put("status", body.status());
    if (UserRole.SUPER_ADMIN.name().equals(actor.role()) && body.role() != null) patch.put("role", body.role());
    protectSuperAdminChange(target, patch);
    updateUser(userId, patch);
    return publicUser(getUserRecord(userId));
  }

  synchronized Map<String, Object> revokeUserSessions(String token, String userId) {
    var actor = requireAdmin(token);
    var target = getUserRecord(userId);
    if (UserRole.ADMIN.name().equals(actor.role()) && !UserRole.MEMBER.name().equals(target.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限踢出该用户");
    }
    sessions.revokeUserSessions(target.id());
    return Map.of("revoked", true);
  }

  synchronized Map<String, Object> validate(String token) {
    return Map.of("user", publicUser(sessions.requireUser(token)));
  }

  private LoginResponse loginPayload(UserRecord user) {
    var token = sessions.createSession(user);
    return new LoginResponse(token, publicUser(user));
  }

  UserRecord requireUser(String token) {
    return sessions.requireUser(token);
  }

  private UserRecord requireAdmin(String token) {
    var user = sessions.requireUser(token);
    if (!UserRole.parse(user.role()).isAdminRole()) {
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
    UserRole.parse(next.role());
    UserStatus.parse(next.status());
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

  private void protectSuperAdminChange(UserRecord target, Map<String, Object> patch) {
    if (!UserRole.SUPER_ADMIN.name().equals(target.role())) return;
    var nextRole = string(patch.getOrDefault("role", target.role())).trim();
    var nextStatus = string(patch.getOrDefault("status", target.status())).trim();
    if (!UserRole.SUPER_ADMIN.name().equals(nextRole) || !UserStatus.ACTIVE.name().equals(nextStatus)) {
      var activeSuperAdmins = jdbc.queryForObject(
        "select count(*) from beiming_users where role = ? and status = ?",
        Integer.class,
        UserRole.SUPER_ADMIN.name(),
        UserStatus.ACTIVE.name()
      );
      if (activeSuperAdmins == null || activeSuperAdmins <= 1) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "至少保留一个可用超级管理员");
      }
    }
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

  private PublicUserView publicUser(UserRecord user) {
    return new PublicUserView(
      user.id(),
      user.name(),
      user.email(),
      user.role(),
      user.status(),
      user.createdAt(),
      user.updatedAt(),
      user.lastLoginAt()
    );
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

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
