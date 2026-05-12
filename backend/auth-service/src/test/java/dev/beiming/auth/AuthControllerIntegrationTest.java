package dev.beiming.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:auth-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.data-dir=${java.io.tmpdir}",
  "beiming.session-ttl-hours=1"
})
class AuthControllerIntegrationTest {
  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  ObjectMapper mapper;

  @BeforeEach
  void resetDatabase() {
    executeIfExists("delete from beiming_invite_code_usages");
    executeIfExists("delete from beiming_invite_codes");
    executeIfExists("delete from beiming_sessions");
    executeIfExists("delete from beiming_users");
  }

  @Test
  void firstUserBootstrapsAsSuperAdminAndSecondUserRequiresInvite() throws Exception {
    var admin = register("Owner", "owner@example.com", "password123", null);

    assertThat(admin.at("/data/user/role").asText()).isEqualTo("SUPER_ADMIN");

    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Member",
          "email", "member@example.com",
          "password", "password123"
        ))))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.ok").value(false));
  }

  @Test
  void superAdminCreatesInviteAndRegistrationConsumesIt() throws Exception {
    var adminToken = register("Owner", "owner@example.com", "password123", null).at("/data/token").asText();
    var invite = createInvite(adminToken, "MEMBER", 1);
    var code = invite.at("/data/code").asText();

    var member = register("Member", "member@example.com", "password123", code);

    assertThat(member.at("/data/user/role").asText()).isEqualTo("MEMBER");
    assertThat(jdbc.queryForObject("select used_count from beiming_invite_codes where code = ?", Integer.class, code)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_invite_code_usages", Integer.class)).isEqualTo(1);
  }

  @Test
  void registrationRejectsUnknownDisabledAndExhaustedInviteCodes() throws Exception {
    var adminToken = register("Owner", "owner@example.com", "password123", null).at("/data/token").asText();

    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Unknown",
          "email", "unknown@example.com",
          "password", "password123",
          "inviteCode", "missing-code"
        ))))
      .andExpect(status().isBadRequest());

    var disabledInvite = createInvite(adminToken, "MEMBER", 2);
    var disabledId = disabledInvite.at("/data/id").asText();
    var disabledCode = disabledInvite.at("/data/code").asText();
    mvc.perform(post("/api/invite-codes/" + disabledId + "/disable").header("Authorization", bearer(adminToken)))
      .andExpect(status().isOk());
    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Disabled",
          "email", "disabled@example.com",
          "password", "password123",
          "inviteCode", disabledCode
        ))))
      .andExpect(status().isBadRequest());

    jdbc.update(
      """
        insert into beiming_invite_codes
        (id, code, type, role, status, max_uses, used_count, expires_at, created_by, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """,
      "invite-expired",
      "expired-code",
      "MEMBER",
      "MEMBER",
      "ACTIVE",
      1,
      0,
      System.currentTimeMillis() - 1_000,
      "system",
      System.currentTimeMillis() - 2_000,
      System.currentTimeMillis() - 2_000
    );
    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Expired",
          "email", "expired@example.com",
          "password", "password123",
          "inviteCode", "expired-code"
        ))))
      .andExpect(status().isBadRequest());

    var singleUseCode = createInvite(adminToken, "MEMBER", 1).at("/data/code").asText();
    register("First", "first@example.com", "password123", singleUseCode);
    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Second",
          "email", "second@example.com",
          "password", "password123",
          "inviteCode", singleUseCode
        ))))
      .andExpect(status().isBadRequest());
  }

  @Test
  void inviteManagementFollowsRoleBoundaries() throws Exception {
    var superToken = register("Owner", "owner@example.com", "password123", null).at("/data/token").asText();
    var adminCode = createInvite(superToken, "ADMIN", 1).at("/data/code").asText();
    var adminToken = register("Admin", "admin@example.com", "password123", adminCode).at("/data/token").asText();

    createInvite(adminToken, "MEMBER", 1);

    mvc.perform(post("/api/invite-codes")
        .header("Authorization", bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("role", "ADMIN", "maxUses", 1))))
      .andExpect(status().isForbidden());

    mvc.perform(get("/api/invite-codes").header("Authorization", bearer(adminToken)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void userManagementFollowsRoleBoundariesAndDisabledUsersCannotLogin() throws Exception {
    var superAdmin = register("Owner", "owner@example.com", "password123", null);
    var superToken = superAdmin.at("/data/token").asText();
    var superId = superAdmin.at("/data/user/id").asText();
    var adminCode = createInvite(superToken, "ADMIN", 1).at("/data/code").asText();
    var memberCode = createInvite(superToken, "MEMBER", 1).at("/data/code").asText();
    var adminToken = register("Admin", "admin@example.com", "password123", adminCode).at("/data/token").asText();
    var member = register("Member", "member@example.com", "password123", memberCode);
    var memberToken = member.at("/data/token").asText();
    var memberId = member.at("/data/user/id").asText();

    mvc.perform(get("/api/users").header("Authorization", bearer(memberToken)))
      .andExpect(status().isForbidden());

    mvc.perform(patch("/api/users/" + superId)
        .header("Authorization", bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "DISABLED"))))
      .andExpect(status().isForbidden());

    mvc.perform(patch("/api/users/" + memberId)
        .header("Authorization", bearer(memberToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("name", "Renamed", "role", "ADMIN", "status", "DISABLED", "lastLoginAt", 1))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.name").value("Renamed"))
      .andExpect(jsonPath("$.data.role").value("MEMBER"))
      .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    assertThat(jdbc.queryForObject("select last_login_at from beiming_users where id = ?", Long.class, memberId)).isNotEqualTo(1L);

    mvc.perform(patch("/api/users/" + memberId)
        .header("Authorization", bearer(superToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "DISABLED"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("DISABLED"));

    mvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("email", "member@example.com", "password", "password123"))))
      .andExpect(status().isForbidden());
  }

  @Test
  void duplicateEmailWrongPasswordAndMissingTokenReturnExpectedErrors() throws Exception {
    register("Owner", "owner@example.com", "password123", null);
    var code = createInvite(login("owner@example.com", "password123").at("/data/token").asText(), "MEMBER", 1).at("/data/code").asText();
    register("Member", "member@example.com", "password123", code);

    mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "name", "Duplicate",
          "email", "member@example.com",
          "password", "password123",
          "inviteCode", code
        ))))
      .andExpect(status().isConflict());

    mvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("email", "member@example.com", "password", "wrongpass"))))
      .andExpect(status().isUnauthorized());

    mvc.perform(get("/api/auth/me"))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void userCanChangePasswordAndOldPasswordStopsWorking() throws Exception {
    var user = register("Owner", "owner@example.com", "password123", null);
    var token = user.at("/data/token").asText();

    mvc.perform(post("/api/auth/change-password")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("currentPassword", "badpass123", "newPassword", "newpass123"))))
      .andExpect(status().isUnauthorized());

    mvc.perform(post("/api/auth/change-password")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("currentPassword", "password123", "newPassword", "newpass123"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.passwordChanged").value(true));

    mvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("email", "owner@example.com", "password", "password123"))))
      .andExpect(status().isUnauthorized());

    login("owner@example.com", "newpass123");
  }

  @Test
  void logoutAllRevokesEverySessionForCurrentUser() throws Exception {
    register("Owner", "owner@example.com", "password123", null);
    var firstToken = login("owner@example.com", "password123").at("/data/token").asText();
    var secondToken = login("owner@example.com", "password123").at("/data/token").asText();

    mvc.perform(post("/api/auth/logout-all").header("Authorization", bearer(firstToken)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.loggedOut").value(true));

    mvc.perform(get("/api/auth/me").header("Authorization", bearer(firstToken)))
      .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").header("Authorization", bearer(secondToken)))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void adminCanReadUserAndSuperAdminCanRevokeUserSessions() throws Exception {
    var owner = register("Owner", "owner@example.com", "password123", null);
    var superToken = owner.at("/data/token").asText();
    var adminCode = createInvite(superToken, "ADMIN", 1).at("/data/code").asText();
    var memberCode = createInvite(superToken, "MEMBER", 1).at("/data/code").asText();
    var adminToken = register("Admin", "admin@example.com", "password123", adminCode).at("/data/token").asText();
    var member = register("Member", "member@example.com", "password123", memberCode);
    var memberToken = member.at("/data/token").asText();
    var memberId = member.at("/data/user/id").asText();

    mvc.perform(get("/api/users/" + memberId).header("Authorization", bearer(adminToken)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.email").value("member@example.com"));

    mvc.perform(get("/api/users/" + owner.at("/data/user/id").asText()).header("Authorization", bearer(memberToken)))
      .andExpect(status().isForbidden());

    mvc.perform(post("/api/users/" + memberId + "/sessions/revoke").header("Authorization", bearer(superToken)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.revoked").value(true));

    mvc.perform(get("/api/auth/me").header("Authorization", bearer(memberToken)))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void roleChangesProtectSuperAdminsAndAdminsFromBadLockouts() throws Exception {
    var owner = register("Owner", "owner@example.com", "password123", null);
    var superToken = owner.at("/data/token").asText();
    var superId = owner.at("/data/user/id").asText();
    var adminCode = createInvite(superToken, "ADMIN", 1).at("/data/code").asText();
    var memberCode = createInvite(superToken, "MEMBER", 1).at("/data/code").asText();
    var admin = register("Admin", "admin@example.com", "password123", adminCode);
    var adminToken = admin.at("/data/token").asText();
    var adminId = admin.at("/data/user/id").asText();
    var member = register("Member", "member@example.com", "password123", memberCode);
    var memberId = member.at("/data/user/id").asText();

    mvc.perform(patch("/api/users/" + superId)
        .header("Authorization", bearer(superToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "DISABLED"))))
      .andExpect(status().isBadRequest());

    mvc.perform(patch("/api/users/" + superId)
        .header("Authorization", bearer(superToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("role", "ADMIN"))))
      .andExpect(status().isBadRequest());

    mvc.perform(patch("/api/users/" + adminId)
        .header("Authorization", bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "DISABLED"))))
      .andExpect(status().isForbidden());

    mvc.perform(patch("/api/users/" + memberId)
        .header("Authorization", bearer(adminToken))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "DISABLED"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("DISABLED"));
  }

  private JsonNode register(String name, String email, String password, String inviteCode) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("name", name);
    body.put("email", email);
    body.put("password", password);
    if (inviteCode != null) body.put("inviteCode", inviteCode);
    var response = mvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(body)))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return mapper.readTree(response);
  }

  private JsonNode login(String email, String password) throws Exception {
    var response = mvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("email", email, "password", password))))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return mapper.readTree(response);
  }

  private JsonNode createInvite(String token, String role, int maxUses) throws Exception {
    var response = mvc.perform(post("/api/invite-codes")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("role", role, "maxUses", maxUses))))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return mapper.readTree(response);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }

  private void executeIfExists(String sql) {
    try {
      jdbc.execute(sql);
    } catch (Exception ignored) {
      // Tests run against evolving schema during red phase.
    }
  }
}
