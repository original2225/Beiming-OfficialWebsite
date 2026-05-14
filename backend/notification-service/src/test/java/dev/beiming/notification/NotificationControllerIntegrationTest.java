package dev.beiming.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:notification-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.services.auth-url=http://127.0.0.1:8792"
})
class NotificationControllerIntegrationTest {
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
    jdbc.execute("delete from beiming_notification_deliveries");
    jdbc.execute("delete from beiming_notifications");
    jdbc.execute("delete from beiming_notification_events");
    auth.reset();
  }

  @Test
  void healthReturnsNotificationServiceName() throws Exception {
    mvc.perform(get("/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.service").value("beiming-notification-service"));
  }

  @Test
  void flywayCreatesNotificationTables() {
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_events", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_deliveries", Integer.class)).isZero();
  }

  @Test
  void internalEventCreatesUnreadNotification() throws Exception {
    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(validEvent())))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.created").value(true))
      .andExpect(jsonPath("$.data.duplicated").value(false));

    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_events", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_deliveries", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select status from beiming_notifications", String.class)).isEqualTo("UNREAD");
    assertThat(jdbc.queryForObject("select payload_json from beiming_notifications", String.class)).contains("\"commentId\":\"comment-123\"");
  }

  @Test
  void duplicateEventKeyDoesNotCreateDuplicateNotification() throws Exception {
    var request = validEvent();

    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.created").value(true))
      .andExpect(jsonPath("$.data.duplicated").value(false));

    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.created").value(false))
      .andExpect(jsonPath("$.data.duplicated").value(true));

    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_events", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_deliveries", Integer.class)).isEqualTo(1);
  }

  @Test
  void selfTriggeredEventDoesNotCreateNotification() throws Exception {
    var request = new LinkedHashMap<>(validEvent());
    request.put("recipientUserId", "user-123");

    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.created").value(false))
      .andExpect(jsonPath("$.data.duplicated").value(false));

    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_events", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_deliveries", Integer.class)).isZero();
  }

  @Test
  void notificationListOnlyReturnsCurrentUserNotifications() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    auth.login("other-token", new CurrentUserView("user-789", "Other", "other@example.com", "MEMBER"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));

    mvc.perform(get("/api/notifications")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(2))
      .andExpect(jsonPath("$.data.total").value(2))
      .andExpect(jsonPath("$.data.items[0].recipientUserId").value("user-456"));
  }

  @Test
  void userCanFilterNotificationsByStatusTypeAndCreatedRange() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    var targetCreatedAt = jdbc.queryForObject(
      "select created_at from beiming_notifications where recipient_user_id = ? and type = ?",
      Long.class,
      "user-456",
      "COMMENT_LIKED"
    );

    mvc.perform(get("/api/notifications?status=UNREAD&type=COMMENT_LIKED&createdAfter=" + (targetCreatedAt - 1) + "&createdBefore=" + (targetCreatedAt + 1))
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].type").value("COMMENT_LIKED"))
      .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void unreadCountOnlyCountsCurrentUserUnreadNotifications() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));
    var firstId = jdbc.queryForObject(
      "select id from beiming_notifications where recipient_user_id = ? order by created_at asc limit 1",
      String.class,
      "user-456"
    );
    jdbc.update("update beiming_notifications set status = 'READ', read_at = ? where id = ?", 123L, firstId);

    mvc.perform(get("/api/notifications/unread-count")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.unreadCount").value(1));
  }

  @Test
  void userCanMarkOwnNotificationRead() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());
    var notificationId = jdbc.queryForObject("select id from beiming_notifications where recipient_user_id = ?", String.class, "user-456");

    mvc.perform(put("/api/notifications/" + notificationId + "/read")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.read").value(true));

    assertThat(jdbc.queryForObject("select status from beiming_notifications where id = ?", String.class, notificationId)).isEqualTo("READ");
  }

  @Test
  void userCannotMarkOtherUserNotificationRead() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    auth.login("other-token", new CurrentUserView("user-789", "Other", "other@example.com", "MEMBER"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));
    var notificationId = jdbc.queryForObject("select id from beiming_notifications where recipient_user_id = ?", String.class, "user-789");

    mvc.perform(put("/api/notifications/" + notificationId + "/read")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isNotFound());
  }

  @Test
  void readAllOnlyAffectsCurrentUser() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));

    mvc.perform(put("/api/notifications/read-all")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.updated").value(2));

    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications where recipient_user_id = ? and status = 'READ'", Integer.class, "user-456")).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from beiming_notifications where recipient_user_id = ? and status = 'UNREAD'", Integer.class, "user-789")).isEqualTo(1);
  }

  @Test
  void deleteArchivesOwnNotification() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());
    var notificationId = jdbc.queryForObject("select id from beiming_notifications where recipient_user_id = ?", String.class, "user-456");

    mvc.perform(delete("/api/notifications/" + notificationId)
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.archived").value(true));

    assertThat(jdbc.queryForObject("select status from beiming_notifications where id = ?", String.class, notificationId)).isEqualTo("ARCHIVED");
  }

  @Test
  void adminCanListEvents() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));

    mvc.perform(get("/api/notifications/admin/events")
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(2))
      .andExpect(jsonPath("$.data.total").value(2));
  }

  @Test
  void adminCanFilterEventsByTypeAndRecipient() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));

    mvc.perform(get("/api/notifications/admin/events?eventType=COMMENT_LIKED&recipientUserId=user-456")
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].eventType").value("COMMENT_LIKED"))
      .andExpect(jsonPath("$.data.items[0].recipientUserId").value("user-456"))
      .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void adminCanFilterEventsByCreatedRange() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));
    var middleCreatedAt = jdbc.queryForObject(
      "select created_at from beiming_notification_events where event_type = ?",
      Long.class,
      "COMMENT_LIKED"
    );

    mvc.perform(get("/api/notifications/admin/events?createdAfter=" + (middleCreatedAt - 1) + "&createdBefore=" + (middleCreatedAt + 10))
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(2))
      .andExpect(jsonPath("$.data.total").value(2));
  }

  @Test
  void memberCannotListEvents() throws Exception {
    auth.login("member-token", new CurrentUserView("user-456", "Member", "member@example.com", "MEMBER"));
    createEvent(validEvent());

    mvc.perform(get("/api/notifications/admin/events")
        .header("Authorization", bearer("member-token")))
      .andExpect(status().isForbidden());
  }

  @Test
  void adminCanListDeliveries() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));

    mvc.perform(get("/api/notifications/admin/deliveries")
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(2))
      .andExpect(jsonPath("$.data.total").value(2));
  }

  @Test
  void adminCanFilterDeliveriesByStatusChannelAndRecipient() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));
    jdbc.update(
      "update beiming_notification_deliveries set status = ?, channel = ? where recipient_user_id = ?",
      "FAILED",
      "WEBHOOK",
      "user-789"
    );

    mvc.perform(get("/api/notifications/admin/deliveries?status=FAILED&channel=WEBHOOK&recipientUserId=user-789")
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
      .andExpect(jsonPath("$.data.items[0].channel").value("WEBHOOK"))
      .andExpect(jsonPath("$.data.items[0].recipientUserId").value("user-789"))
      .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void adminCanFilterDeliveriesByCreatedRange() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    createEvent(validEvent());
    createEvent(eventFor("community:comment-liked:comment-456:user-456", "COMMENT_LIKED", "comment-456", "COMMENT", "comment-456", "user-456"));
    createEvent(eventFor("community:post-liked:post-111:user-789", "POST_LIKED", "reaction-999", "POST", "post-111", "user-789"));
    var middleCreatedAt = jdbc.queryForObject(
      "select created_at from beiming_notification_deliveries where recipient_user_id = ? order by created_at desc limit 1",
      Long.class,
      "user-456"
    );

    mvc.perform(get("/api/notifications/admin/deliveries?createdAfter=" + (middleCreatedAt - 20) + "&createdBefore=" + (middleCreatedAt + 1))
        .header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(2))
      .andExpect(jsonPath("$.data.total").value(2));
  }

  private Map<String, Object> validEvent() {
    return Map.ofEntries(
      Map.entry("eventKey", "community:post-commented:comment-123:user-456"),
      Map.entry("eventType", "POST_COMMENTED"),
      Map.entry("sourceService", "community-service"),
      Map.entry("sourceId", "comment-123"),
      Map.entry("actorUserId", "user-123"),
      Map.entry("actorDisplayName", "Alex"),
      Map.entry("actorAvatarUrl", ""),
      Map.entry("recipientUserId", "user-456"),
      Map.entry("targetType", "POST"),
      Map.entry("targetId", "post-789"),
      Map.entry("title", "有人评论了你的帖子"),
      Map.entry("body", "Alex 评论了《红石电梯教程》"),
      Map.entry("actionUrl", "/community/posts/post-789"),
      Map.entry("payload", Map.of(
        "postId", "post-789",
        "commentId", "comment-123"
      ))
    );
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }

  private void createEvent(Map<String, Object> request) throws Exception {
    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(request)))
      .andExpect(status().isOk());
  }

  private Map<String, Object> eventFor(String eventKey, String eventType, String sourceId, String targetType, String targetId, String recipientUserId) {
    return Map.ofEntries(
      Map.entry("eventKey", eventKey),
      Map.entry("eventType", eventType),
      Map.entry("sourceService", "community-service"),
      Map.entry("sourceId", sourceId),
      Map.entry("actorUserId", "user-123"),
      Map.entry("actorDisplayName", "Alex"),
      Map.entry("actorAvatarUrl", ""),
      Map.entry("recipientUserId", recipientUserId),
      Map.entry("targetType", targetType),
      Map.entry("targetId", targetId),
      Map.entry("title", "通知标题"),
      Map.entry("body", "通知正文"),
      Map.entry("actionUrl", "/community/posts/" + targetId),
      Map.entry("payload", Map.of("targetId", targetId))
    );
  }

  private String bearer(String token) {
    return "Bearer " + token;
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
