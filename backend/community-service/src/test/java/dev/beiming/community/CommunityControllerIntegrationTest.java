package dev.beiming.community;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:community-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "beiming.services.auth-url=http://127.0.0.1:8792",
  "beiming.services.profile-url=http://127.0.0.1:8793"
})
class CommunityControllerIntegrationTest {
  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  ObjectMapper mapper;

  @Autowired
  FakeAuthClient auth;

  @Autowired
  FakeProfileClient profiles;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute("delete from beiming_community_poll_votes");
    jdbc.execute("delete from beiming_community_poll_options");
    jdbc.execute("delete from beiming_community_polls");
    jdbc.execute("delete from beiming_community_reports");
    jdbc.execute("delete from beiming_community_post_favorites");
    jdbc.execute("delete from beiming_community_comment_reactions");
    jdbc.execute("delete from beiming_community_post_reactions");
    jdbc.execute("delete from beiming_community_comments");
    jdbc.execute("delete from beiming_community_posts");
    jdbc.execute("delete from beiming_community_boards");
    seedBoards();
    auth.reset();
    profiles.reset();
  }

  @Test
  void healthReturnsCommunityServiceName() throws Exception {
    mvc.perform(get("/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.service").value("beiming-community-service"));
  }

  @Test
  void schemaSeedsBoards() {
    assertThat(jdbc.queryForObject("select count(*) from beiming_community_boards", Integer.class)).isGreaterThan(0);
  }

  @Test
  void adminCanCreateBoardAndMemberCannot() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(post("/api/community/admin/boards")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "slug", "creative",
          "name", "创造区",
          "description", "Creative builds",
          "visibility", "PUBLIC",
          "postingRole", "MEMBER",
          "sortOrder", 15
        ))))
      .andExpect(status().isForbidden());

    mvc.perform(post("/api/community/admin/boards")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "slug", "creative",
          "name", "创造区",
          "description", "Creative builds",
          "visibility", "MEMBER_ONLY",
          "postingRole", "MEMBER",
          "sortOrder", 15
        ))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.slug").value("creative"));
  }

  @Test
  void boardListRespectsVisibility() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    mvc.perform(post("/api/community/admin/boards")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "slug", "staff",
          "name", "管理区",
          "description", "Admin only",
          "visibility", "ADMIN_ONLY",
          "postingRole", "ADMIN",
          "sortOrder", 12
        ))))
      .andExpect(status().isOk());

    mvc.perform(post("/api/community/admin/boards")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "slug", "guild",
          "name", "成员区",
          "description", "Members only",
          "visibility", "MEMBER_ONLY",
          "postingRole", "MEMBER",
          "sortOrder", 13
        ))))
      .andExpect(status().isOk());

    mvc.perform(get("/api/community/boards"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data[?(@.slug=='archive')]").isEmpty())
      .andExpect(jsonPath("$.data[?(@.slug=='guild')]").isEmpty())
      .andExpect(jsonPath("$.data[?(@.slug=='staff')]").isEmpty());

    var memberBoards = readJson(mvc.perform(get("/api/community/boards").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString());
    assertThat(memberBoards.at("/data").toString()).contains("\"slug\":\"guild\"");
    assertThat(memberBoards.at("/data").toString()).doesNotContain("\"slug\":\"staff\"");

    var adminBoards = readJson(mvc.perform(get("/api/community/boards").header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString());
    assertThat(adminBoards.at("/data").toString()).contains("\"slug\":\"staff\"");
  }

  @Test
  void memberCanCreatePostAndPublicListFiltersDrafts() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    profiles.put("user-member", new AuthorSnapshot("user-member", "北冥玩家", "https://avatar.example/m.png", "NorthStar"));

    var boardId = boardId("modpacks");
    var published = createPost("member-token", boardId, "公开帖子", "hello community", "PUBLISHED", "PUBLIC", null);
    createPost("member-token", boardId, "草稿帖子", "hidden draft", "DRAFT", "PUBLIC", null);

    mvc.perform(get("/api/community/posts"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].title").value("公开帖子"));

    mvc.perform(get("/api/community/posts/" + published.at("/data/id").asText()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.authorDisplayName").value("北冥玩家"))
      .andExpect(jsonPath("$.data.authorMinecraftId").value("NorthStar"));
  }

  @Test
  void boardVisibilityAlsoProtectsPosts() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));

    var hiddenBoardId = boardId("archive");
    var postId = createPost("admin-token", hiddenBoardId, "归档帖子", "archive only", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    mvc.perform(get("/api/community/posts"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(0));

    mvc.perform(get("/api/community/posts/" + postId))
      .andExpect(status().isNotFound());

    mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("member-token")))
      .andExpect(status().isNotFound());

    mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("admin-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.title").value("归档帖子"));
  }

  @Test
  void invalidPollDoesNotLeavePostBehind() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var invalidPoll = Map.of(
      "question", "选哪个",
      "voteMode", "SINGLE",
      "resultVisibility", "ALWAYS",
      "options", List.of(Map.of("text", "只有一个选项"))
    );

    mvc.perform(post("/api/community/posts")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of(
          "boardId", boardId("events"),
          "title", "坏投票",
          "content", "should rollback",
          "status", "PUBLISHED",
          "visibility", "PUBLIC",
          "poll", invalidPoll
        ))))
      .andExpect(status().isBadRequest());

    assertThat(jdbc.queryForObject("select count(*) from beiming_community_posts where title = ?", Integer.class, "坏投票")).isZero();
  }

  @Test
  void postDetailIncrementsViewCount() throws Exception {
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var boardId = boardId("help");
    var postId = createPost("member-token", boardId, "求助", "need help", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    mvc.perform(get("/api/community/posts/" + postId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.viewCount").value(1));

    mvc.perform(get("/api/community/posts/" + postId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.viewCount").value(2));
  }

  @Test
  void authorAndAdminCanEditButOtherMemberCannot() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("other-token", new CurrentUserView("user-other", "Other", "other@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    var postId = createPost("author-token", boardId("building"), "初稿", "body", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    mvc.perform(put("/api/community/posts/" + postId)
        .header("Authorization", bearer("other-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("title", "篡改", "content", "bad"))))
      .andExpect(status().isForbidden());

    mvc.perform(put("/api/community/posts/" + postId)
        .header("Authorization", bearer("author-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("title", "作者更新", "content", "good"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.title").value("作者更新"));

    mvc.perform(put("/api/community/admin/posts/" + postId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("locked", true, "moderationNote", "cool down"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.locked").value(true));

    mvc.perform(put("/api/community/posts/" + postId)
        .header("Authorization", bearer("author-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("title", "再改", "content", "no"))))
      .andExpect(status().isForbidden());

    mvc.perform(put("/api/community/posts/" + postId)
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("title", "管理员更新", "content", "admin edit"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.title").value("管理员更新"));
  }

  @Test
  void memberCanCommentAndLockedPostRejectsComment() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    var postId = createPost("author-token", boardId("help"), "问题", "请教", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    var commentId = mvc.perform(post("/api/community/posts/" + postId + "/comments")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("content", "先看日志"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.content").value("先看日志"))
      .andReturn()
      .getResponse()
      .getContentAsString();

    var parsed = mapper.readTree(commentId);
    var createdCommentId = parsed.at("/data/id").asText();

    mvc.perform(put("/api/community/comments/" + createdCommentId)
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("content", "补充答案"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.content").value("补充答案"));

    mvc.perform(put("/api/community/admin/posts/" + postId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("locked", true))))
      .andExpect(status().isOk());

    mvc.perform(post("/api/community/posts/" + postId + "/comments")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("content", "继续回复"))))
      .andExpect(status().isForbidden());
  }

  @Test
  void likesAndFavoritesAreIdempotent() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var postId = createPost("author-token", boardId("resources"), "资源发布", "download", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();
    var commentId = createComment("member-token", postId, "不错").at("/data/id").asText();

    mvc.perform(post("/api/community/posts/" + postId + "/reactions").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());
    mvc.perform(post("/api/community/posts/" + postId + "/reactions").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());

    mvc.perform(post("/api/community/comments/" + commentId + "/reactions").header("Authorization", bearer("author-token")))
      .andExpect(status().isOk());
    mvc.perform(post("/api/community/posts/" + postId + "/favorites").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());
    mvc.perform(post("/api/community/posts/" + postId + "/favorites").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());

    mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.likeCount").value(1))
      .andExpect(jsonPath("$.data.favoriteCount").value(1))
      .andExpect(jsonPath("$.data.liked").value(true))
      .andExpect(jsonPath("$.data.favorited").value(true));

    mvc.perform(get("/api/community/posts/" + postId + "/comments").header("Authorization", bearer("author-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data[0].likeCount").value(1));

    mvc.perform(delete("/api/community/posts/" + postId + "/reactions").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());
    mvc.perform(delete("/api/community/posts/" + postId + "/reactions").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());
    mvc.perform(delete("/api/community/posts/" + postId + "/favorites").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk());

    mvc.perform(get("/api/community/me/favorites").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.items.length()").value(0));
  }

  @Test
  void concurrentDuplicateLikesAndFavoritesStayIdempotent() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var postId = createPost("author-token", boardId("resources"), "并发资源", "download", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();
    var commentId = createComment("member-token", postId, "并发不错").at("/data/id").asText();
    var failures = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(12);
    for (var i = 0; i < 24; i++) {
      pool.submit(() -> {
        try {
          mvc.perform(post("/api/community/posts/" + postId + "/reactions").header("Authorization", bearer("member-token")))
            .andExpect(status().isOk());
          mvc.perform(post("/api/community/posts/" + postId + "/favorites").header("Authorization", bearer("member-token")))
            .andExpect(status().isOk());
          mvc.perform(post("/api/community/comments/" + commentId + "/reactions").header("Authorization", bearer("author-token")))
            .andExpect(status().isOk());
        } catch (Exception ignored) {
          failures.incrementAndGet();
        }
      });
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(failures.get()).isZero();

    mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.likeCount").value(1))
      .andExpect(jsonPath("$.data.favoriteCount").value(1));

    mvc.perform(get("/api/community/posts/" + postId + "/comments").header("Authorization", bearer("author-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data[0].likeCount").value(1));
  }

  @Test
  void favoriteListFiltersInvisiblePostsBeforePaginationAndCount() throws Exception {
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var visiblePostId = createPost("member-token", boardId("resources"), "可见收藏", "visible", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();
    var hiddenPostId = createPost("admin-token", boardId("archive"), "不可见收藏", "hidden", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    jdbc.update(
      "insert into beiming_community_post_favorites (id, post_id, user_id, created_at) values (?, ?, ?, ?)",
      "favorite-" + UUID.randomUUID(),
      visiblePostId,
      "user-member",
      10L
    );
    jdbc.update(
      "insert into beiming_community_post_favorites (id, post_id, user_id, created_at) values (?, ?, ?, ?)",
      "favorite-" + UUID.randomUUID(),
      hiddenPostId,
      "user-member",
      20L
    );

    mvc.perform(get("/api/community/me/favorites?pageSize=1").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.total").value(1))
      .andExpect(jsonPath("$.data.items.length()").value(1))
      .andExpect(jsonPath("$.data.items[0].title").value("可见收藏"));
  }

  @Test
  void memberCanReportAndAdminResolveReport() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    var postId = createPost("author-token", boardId("vanilla"), "刷屏", "spam text", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();

    var response = mvc.perform(post("/api/community/posts/" + postId + "/reports")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("reason", "SPAM", "detail", "重复发帖"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("OPEN"))
      .andReturn()
      .getResponse()
      .getContentAsString();

    var reportId = mapper.readTree(response).at("/data/id").asText();

    mvc.perform(post("/api/community/posts/" + postId + "/reports")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("reason", "SPAM", "detail", "重复发帖"))))
      .andExpect(status().isConflict());

    mvc.perform(put("/api/community/admin/reports/" + reportId)
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("status", "RESOLVED", "reviewNote", "已处理"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("RESOLVED"));
  }

  @Test
  void pollVoteCanBeReplacedAndRetracted() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var boardId = boardId("events");
    var pollBody = Map.of(
      "question", "下次活动选什么",
      "voteMode", "SINGLE",
      "resultVisibility", "AFTER_VOTE",
      "options", List.of(
        Map.of("text", "建筑接力"),
        Map.of("text", "跑酷比赛")
      )
    );
    var postId = createPost("author-token", boardId, "活动投票", "来投票", "PUBLISHED", "PUBLIC", pollBody).at("/data/id").asText();

    var detail = readJson(mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString());
    var firstOption = detail.at("/data/poll/options/0/id").asText();
    var secondOption = detail.at("/data/poll/options/1/id").asText();

    mvc.perform(post("/api/community/posts/" + postId + "/poll/votes")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("optionIds", List.of(firstOption)))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.voted").value(true))
      .andExpect(jsonPath("$.data.totalVotes").value(1));

    mvc.perform(post("/api/community/posts/" + postId + "/poll/votes")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("optionIds", List.of(secondOption)))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.myOptionIds[0]").value(secondOption));

    mvc.perform(delete("/api/community/posts/" + postId + "/poll/votes").header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.voted").value(false));
  }

  @Test
  void expiredPollRejectsVotes() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    var pollBody = Map.of(
      "question", "过期投票",
      "voteMode", "SINGLE",
      "resultVisibility", "ALWAYS",
      "closesAt", 1,
      "options", List.of(
        Map.of("text", "A"),
        Map.of("text", "B")
      )
    );
    var postId = createPost("author-token", boardId("events"), "过期活动投票", "投票", "PUBLISHED", "PUBLIC", pollBody).at("/data/id").asText();
    var detail = readJson(mvc.perform(get("/api/community/posts/" + postId).header("Authorization", bearer("member-token")))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString());
    var optionId = detail.at("/data/poll/options/0/id").asText();

    mvc.perform(post("/api/community/posts/" + postId + "/poll/votes")
        .header("Authorization", bearer("member-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("optionIds", List.of(optionId)))))
      .andExpect(status().isBadRequest());
  }

  @Test
  void adminCanModeratePostAndComment() throws Exception {
    auth.login("author-token", new CurrentUserView("user-author", "Author", "author@example.com", "MEMBER"));
    auth.login("member-token", new CurrentUserView("user-member", "Member", "member@example.com", "MEMBER"));
    auth.login("admin-token", new CurrentUserView("user-admin", "Admin", "admin@example.com", "ADMIN"));
    var postId = createPost("author-token", boardId("building"), "作品", "作品内容", "PUBLISHED", "PUBLIC", null).at("/data/id").asText();
    var commentId = createComment("member-token", postId, "不合规").at("/data/id").asText();

    mvc.perform(put("/api/community/admin/comments/" + commentId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("hidden", true, "moderationNote", "先隐藏"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("HIDDEN"));

    mvc.perform(put("/api/community/admin/comments/" + commentId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("restore", true))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("VISIBLE"));

    mvc.perform(put("/api/community/admin/posts/" + postId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("hidden", true, "pinned", true, "locked", true, "moderationNote", "暂停展示"))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("HIDDEN"))
      .andExpect(jsonPath("$.data.pinned").value(true))
      .andExpect(jsonPath("$.data.locked").value(true));

    mvc.perform(get("/api/community/posts/" + postId))
      .andExpect(status().isNotFound());

    mvc.perform(put("/api/community/admin/posts/" + postId + "/moderation")
        .header("Authorization", bearer("admin-token"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("restore", true, "locked", false))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
  }

  private void seedBoards() {
    insertBoard("board-announcements", "announcements", "公告区", "官方公告和规则", "PUBLIC", "ADMIN", 10);
    insertBoard("board-modpacks", "modpacks", "整合包区", "整合包发布和讨论", "PUBLIC", "MEMBER", 20);
    insertBoard("board-vanilla", "vanilla", "原版生存区", "原版玩法和服务器生活", "PUBLIC", "MEMBER", 30);
    insertBoard("board-building", "building", "建筑区", "建筑作品和施工记录", "PUBLIC", "MEMBER", 40);
    insertBoard("board-redstone", "redstone", "红石技术区", "电路设计和机制研究", "PUBLIC", "MEMBER", 50);
    insertBoard("board-resources", "resources", "资源发布区", "地图、材质、工具和教程", "PUBLIC", "MEMBER", 60);
    insertBoard("board-help", "help", "求助答疑区", "问题求助和经验交流", "PUBLIC", "MEMBER", 70);
    insertBoard("board-events", "events", "活动赛事区", "社区活动和比赛", "PUBLIC", "MEMBER", 80);
    insertBoard("board-archive", "archive", "归档区", "历史内容归档", "HIDDEN", "ADMIN", 90);
  }

  private void insertBoard(String id, String slug, String name, String description, String visibility, String postingRole, int sortOrder) {
    jdbc.update(
      """
        insert into beiming_community_boards
        (id, slug, name, description, visibility, posting_role, sort_order, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 0, 0)
      """,
      id, slug, name, description, visibility, postingRole, sortOrder
    );
  }

  private String boardId(String slug) {
    return jdbc.queryForObject("select id from beiming_community_boards where slug = ?", String.class, slug);
  }

  private JsonNode createPost(String token, String boardId, String title, String content, String status, String visibility, Object poll) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("boardId", boardId);
    body.put("title", title);
    body.put("content", content);
    body.put("status", status);
    body.put("visibility", visibility);
    if (poll != null) body.put("poll", poll);
    var response = mvc.perform(post("/api/community/posts")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(body)))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return readJson(response);
  }

  private JsonNode createComment(String token, String postId, String content) throws Exception {
    var response = mvc.perform(post("/api/community/posts/" + postId + "/comments")
        .header("Authorization", bearer(token))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(Map.of("content", content))))
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return readJson(response);
  }

  private JsonNode readJson(String value) throws Exception {
    return mapper.readTree(value);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }

  @TestConfiguration
  static class FakeClients {
    @Bean
    @Primary
    FakeAuthClient fakeAuthClient() {
      return new FakeAuthClient();
    }

    @Bean
    @Primary
    FakeProfileClient fakeProfileClient() {
      return new FakeProfileClient();
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

  static class FakeProfileClient implements ProfileClient {
    private final Map<String, AuthorSnapshot> snapshots = new ConcurrentHashMap<>();

    void put(String userId, AuthorSnapshot snapshot) {
      snapshots.put(userId, snapshot);
    }

    void reset() {
      snapshots.clear();
    }

    @Override
    public AuthorSnapshot resolve(String authorization, CurrentUserView user) {
      return snapshots.getOrDefault(user.id(), AuthorSnapshot.fromUser(user));
    }
  }
}
