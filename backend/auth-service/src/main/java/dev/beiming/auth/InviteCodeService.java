package dev.beiming.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class InviteCodeService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long DEFAULT_INVITE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

  private final JdbcTemplate jdbc;

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

  InviteCodeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  InviteCodeView createInviteCode(UserRecord actor, CreateInviteCodeRequest body) {
    var role = normalizeRole(body.role());
    if (role == UserRole.SUPER_ADMIN) throw new ApiException(HttpStatus.BAD_REQUEST, "不能创建超级管理员邀请码");
    if (role == UserRole.ADMIN && !UserRole.SUPER_ADMIN.name().equals(actor.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限创建管理员邀请码");
    }
    var maxUses = Math.max(1, body.maxUses() == null ? 1 : body.maxUses());
    var now = now();
    var expiresAt = body.expiresAt() == null ? 0 : body.expiresAt();
    if (expiresAt <= 0) expiresAt = now + DEFAULT_INVITE_TTL_MS;
    if (expiresAt <= now) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码过期时间无效");
    var invite = new InviteCodeRecord(
      "invite-" + UUID.randomUUID().toString().substring(0, 8),
      uniqueInviteCode(),
      InviteCodeType.fromRole(role).name(),
      role.name(),
      InviteCodeStatus.ACTIVE.name(),
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

  List<InviteCodeView> inviteCodes() {
    return allInviteCodes().stream().map(this::inviteCodeView).toList();
  }

  InviteCodeView disableInviteCode(UserRecord actor, String inviteCodeId) {
    var invite = getInviteCode(inviteCodeId);
    if (UserRole.ADMIN.name().equals(actor.role()) && !UserRole.MEMBER.name().equals(invite.role())) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限禁用该邀请码");
    }
    var now = now();
    jdbc.update("update beiming_invite_codes set status = ?, updated_at = ? where id = ?", InviteCodeStatus.DISABLED.name(), now, invite.id());
    return inviteCodeView(getInviteCode(invite.id()));
  }

  String consumeInviteCode(String code) {
    var normalizedCode = string(code).trim();
    if (normalizedCode.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码不能为空");
    var invite = findInviteCodeByCode(normalizedCode)
      .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "邀请码无效"));
    if (!InviteCodeStatus.ACTIVE.name().equals(invite.status())) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已禁用");
    if (invite.expiresAt() <= now()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已过期");
    if (invite.usedCount() >= invite.maxUses()) throw new ApiException(HttpStatus.BAD_REQUEST, "邀请码已用完");
    return invite.role();
  }

  void recordInviteUsage(String code, String userId) {
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

  private Optional<InviteCodeRecord> findInviteCodeByCode(String code) {
    var rows = jdbc.query("select * from beiming_invite_codes where code = ?", inviteCodeMapper, code);
    return rows.stream().findFirst();
  }

  private InviteCodeRecord getInviteCode(String inviteCodeId) {
    var rows = jdbc.query("select * from beiming_invite_codes where id = ?", inviteCodeMapper, inviteCodeId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "邀请码不存在");
    return rows.get(0);
  }

  private List<InviteCodeRecord> allInviteCodes() {
    return jdbc.query("select * from beiming_invite_codes order by created_at asc", inviteCodeMapper);
  }

  private InviteCodeView inviteCodeView(InviteCodeRecord invite) {
    return new InviteCodeView(
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

  private String uniqueInviteCode() {
    for (var attempt = 0; attempt < 5; attempt++) {
      var code = randomToken(12);
      if (findInviteCodeByCode(code).isEmpty()) return code;
    }
    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "生成邀请码失败");
  }

  private String randomToken(int bytes) {
    var value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private UserRole normalizeRole(String value) {
    return string(value).trim().isBlank() ? UserRole.MEMBER : UserRole.parse(value);
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private long now() {
    return Instant.now().toEpochMilli();
  }
}
