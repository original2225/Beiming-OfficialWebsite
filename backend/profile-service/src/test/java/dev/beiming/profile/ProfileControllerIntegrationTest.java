package dev.beiming.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:profile-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.services.auth-url=http://127.0.0.1:8792"
})
class ProfileControllerIntegrationTest {
  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  ObjectMapper mapper;

  @Autowired
  FakeAuthClient auth;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute("delete from beiming_member_profiles");
    auth.reset();
  }

  @Test
  void healthReturnsProfileServiceName() throws Exception {
    mvc.perform(get("/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.service").value("beiming-profile-service"));
  }

  @Test
  void meReturnsDefaultProfileWhenProfileDoesNotExist() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(get("/api/profile/me").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.userId").value("user-member"))
      .andExpect(jsonPath("$.data.displayName").value("Member"))
      .andExpect(jsonPath("$.data.exists").value(false))
      .andExpect(jsonPath("$.data.email").doesNotExist());
  }

  @Test
  void userCanUpdateOwnPublicProfile() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(put("/api/profile/me")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "displayName", "North Star",
          "bio", "Builder and redstone maintainer",
          "avatarUrl", "https://example.com/avatar.png",
          "minecraftId", "NorthStar",
          "minecraftUuid", "uuid-1",
          "visibility", "PUBLIC"
        ))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.exists").value(true))
      .andExpect(jsonPath("$.data.displayName").value("North Star"))
      .andExpect(jsonPath("$.data.minecraftId").value("NorthStar"))
      .andExpect(jsonPath("$.data.skinUrl").value("https://minotar.net/skin/uuid-1"));
  }

  @Test
  void userCannotChangeAdminOnlyFields() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(put("/api/profile/me")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "displayName", "Member",
          "bio", "Bio",
          "avatarUrl", "",
          "minecraftId", "MemberOne",
          "memberGroup", "ADMIN",
          "memberStatus", "LEFT",
          "adminNote", "hidden"
        ))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.memberGroup").value("MEMBER"))
      .andExpect(jsonPath("$.data.memberStatus").value("ACTIVE"));

    assertThat(jdbc.queryForObject("select admin_note from beiming_member_profiles where user_id = ?", String.class, "user-member")).isEmpty();
  }

  @Test
  void minecraftIdMustBeUnique() throws Exception {
    auth.login("first-token", new CurrentUserView("user-first", "First", "first@example.com", "MEMBER"));
    auth.login("second-token", new CurrentUserView("user-second", "Second", "second@example.com", "MEMBER"));
    upsertOwnProfile("first-token", "FirstPlayer");

    mvc.perform(put("/api/profile/me")
        .header("Authorization", bearer("second-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("displayName", "Second", "bio", "Bio", "avatarUrl", "", "minecraftId", "firstplayer"))))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.ok").value(false));
  }

  @Test
  void publicMembersOnlyReturnsPublicActiveProfiles() throws Exception {
    auth.login("one-token", new CurrentUserView("user-one", "One", "one@example.com", "MEMBER"));
    auth.login("two-token", new CurrentUserView("user-two", "Two", "two@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    upsertOwnProfile("one-token", "OnePlayer");
    upsertOwnProfile("two-token", "TwoPlayer");
    var hiddenId = profileIdForUser("user-two");
    adminUpdate("admin-token", hiddenId, Map.of("memberStatus", "HIDDEN"));

    mvc.perform(get("/api/profile/members"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].minecraftId").value("OnePlayer"))
      .andExpect(jsonPath("$.data.items[0].adminNote").doesNotExist());
  }

  @Test
  void privateProfileIsHiddenFromPublicDetail() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    upsertOwnProfile("member-token", "PrivatePlayer", "PRIVATE");
    var profileId = profileIdForUser("user-member");

    mvc.perform(get("/api/profile/members/" + profileId))
      .andExpect(status().isNotFound());

    mvc.perform(get("/api/profile/members/" + profileId).header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.minecraftId").value("PrivatePlayer"));
  }

  @Test
  void adminCanListAllProfiles() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    upsertOwnProfile("member-token", "MemberPlayer", "PRIVATE");

    mvc.perform(get("/api/profile/admin/members").header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].visibility").value("PRIVATE"));
  }

  @Test
  void adminCanUpdateMemberStatusAndGroup() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    upsertOwnProfile("member-token", "MemberPlayer");
    var profileId = profileIdForUser("user-member");

    mvc.perform(put("/api/profile/admin/members/" + profileId)
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "memberGroup", "TRAINEE",
          "memberStatus", "INACTIVE",
          "joinedAt", 12345,
          "featured", true,
          "adminNote", "needs review"
        ))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.memberGroup").value("TRAINEE"))
      .andExpect(jsonPath("$.data.memberStatus").value("INACTIVE"))
      .andExpect(jsonPath("$.data.joinedAt").value(12345))
      .andExpect(jsonPath("$.data.featured").value(true));

    assertThat(jdbc.queryForObject("select admin_note from beiming_member_profiles where id = ?", String.class, profileId)).isEqualTo("needs review");
    assertThat(jdbc.queryForObject("select updated_by from beiming_member_profiles where id = ?", String.class, profileId)).isEqualTo("user-admin");
  }

  @Test
  void memberCannotUseAdminEndpoint() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(get("/api/profile/admin/members").header("Authorization", bearer("member-token")))
      .andExpect(status().isForbidden());
  }

  @Test
  void profileSchemaIsManagedByFlywayAndTracksProfileWriters() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    upsertOwnProfile("member-token", "WriterPlayer");

    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select created_by from beiming_member_profiles where user_id = ?", String.class, "user-member")).isEqualTo("user-member");
    assertThat(jdbc.queryForObject("select updated_by from beiming_member_profiles where user_id = ?", String.class, "user-member")).isEqualTo("user-member");
  }

  private void upsertOwnProfile(String token, String minecraftId) throws Exception {
    upsertOwnProfile(token, minecraftId, "PUBLIC");
  }

  private void upsertOwnProfile(String token, String minecraftId, String visibility) throws Exception {
    mvc.perform(put("/api/profile/me")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("displayName", minecraftId, "bio", "Bio", "avatarUrl", "", "minecraftId", minecraftId, "visibility", visibility))))
      .andExpect(status().isOk());
  }

  private void adminUpdate(String token, String profileId, Map<String, Object> body) throws Exception {
    mvc.perform(put("/api/profile/admin/members/" + profileId)
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(body)))
      .andExpect(status().isOk());
  }

  private String profileIdForUser(String userId) {
    return jdbc.queryForObject("select id from beiming_member_profiles where user_id = ?", String.class, userId);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }

  @TestConfiguration
  static class FakeAuthConfig {
    @Bean
    @Primary
    FakeAuthClient fakeAuthClient() {
      return new FakeAuthClient();
    }
  }

  static class FakeAuthClient implements AuthClient {
    private final Map<String, CurrentUserView> users = new ConcurrentHashMap<>();

    void login(String token, CurrentUserView user) {
      users.put(token, user);
    }

    void reset() {
      users.clear();
    }

    @Override
    public CurrentUserView requireUser(String authorization) {
      var value = authorization == null ? "" : authorization.trim();
      var token = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
      var user = users.get(token);
      if (user == null) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
      return user;
    }

    @Override
    public CurrentUserView optionalUser(String authorization) {
      try {
        return requireUser(authorization);
      } catch (ApiException ignored) {
        return null;
      }
    }
  }
}
