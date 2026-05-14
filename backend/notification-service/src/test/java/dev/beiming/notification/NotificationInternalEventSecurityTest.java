package dev.beiming.notification;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:notification-token;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.services.auth-url=http://127.0.0.1:8792",
  "beiming.internal.token=test-internal-token"
})
class NotificationInternalEventSecurityTest {
  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  ObjectMapper mapper;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute("delete from beiming_notification_deliveries");
    jdbc.execute("delete from beiming_notifications");
    jdbc.execute("delete from beiming_notification_events");
  }

  @Test
  void internalEventRequiresTokenWhenConfigured() throws Exception {
    mvc.perform(post("/api/internal/notifications/events")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(validEvent())))
      .andExpect(status().isForbidden());

    mvc.perform(post("/api/internal/notifications/events")
        .header("X-Beiming-Internal-Token", "wrong-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(validEvent())))
      .andExpect(status().isForbidden());

    mvc.perform(post("/api/internal/notifications/events")
        .header("X-Beiming-Internal-Token", "test-internal-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(validEvent())))
      .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("select count(*) from beiming_notification_events", Integer.class)).isEqualTo(1);
  }

  private Map<String, Object> validEvent() {
    return Map.ofEntries(
      Map.entry("eventKey", "community:post-liked:post-789:user-456"),
      Map.entry("eventType", "POST_LIKED"),
      Map.entry("sourceService", "community-service"),
      Map.entry("sourceId", "reaction-123"),
      Map.entry("actorUserId", "user-123"),
      Map.entry("actorDisplayName", "Alex"),
      Map.entry("actorAvatarUrl", ""),
      Map.entry("recipientUserId", "user-456"),
      Map.entry("targetType", "POST"),
      Map.entry("targetId", "post-789"),
      Map.entry("title", "有人点赞了你的帖子"),
      Map.entry("body", "Alex 点赞了《红石电梯教程》"),
      Map.entry("actionUrl", "/community/posts/post-789"),
      Map.entry("payload", Map.of("postId", "post-789"))
    );
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }
}
