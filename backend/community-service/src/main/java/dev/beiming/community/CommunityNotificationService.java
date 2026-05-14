package dev.beiming.community;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CommunityNotificationService {
  private static final Logger log = LoggerFactory.getLogger(CommunityNotificationService.class);
  private static final String SOURCE_SERVICE = "community-service";

  private final NotificationClient notificationClient;

  CommunityNotificationService(NotificationClient notificationClient) {
    this.notificationClient = notificationClient;
  }

  void notifyPostCommented(CurrentUserView actor, PostRecord post, CommentRecord comment) {
    if (actor.id().equals(post.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:post-commented:" + comment.id() + ":" + post.authorUserId(),
      "POST_COMMENTED",
      SOURCE_SERVICE,
      comment.id(),
      actor.id(),
      safeActorName(actor, comment.authorDisplayName()),
      comment.authorAvatarUrl(),
      post.authorUserId(),
      "POST",
      post.id(),
      "有人评论了你的帖子",
      safeActorName(actor, comment.authorDisplayName()) + " 评论了《" + post.title() + "》",
      postCommentUrl(post.id(), comment.id()),
      Map.of("postId", post.id(), "commentId", comment.id())
    ));
  }

  void notifyCommentReplied(CurrentUserView actor, PostRecord post, CommentRecord parentComment, CommentRecord replyComment) {
    if (actor.id().equals(parentComment.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:comment-replied:" + replyComment.id() + ":" + parentComment.authorUserId(),
      "COMMENT_REPLIED",
      SOURCE_SERVICE,
      replyComment.id(),
      actor.id(),
      safeActorName(actor, replyComment.authorDisplayName()),
      replyComment.authorAvatarUrl(),
      parentComment.authorUserId(),
      "COMMENT",
      parentComment.id(),
      "有人回复了你的评论",
      safeActorName(actor, replyComment.authorDisplayName()) + " 回复了你在《" + post.title() + "》下的评论",
      postCommentUrl(post.id(), replyComment.id()),
      Map.of("postId", post.id(), "commentId", replyComment.id(), "parentCommentId", parentComment.id())
    ));
  }

  void notifyPostLiked(CurrentUserView actor, PostRecord post) {
    if (actor.id().equals(post.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:post-liked:" + post.id() + ":" + actor.id() + ":" + post.authorUserId(),
      "POST_LIKED",
      SOURCE_SERVICE,
      post.id(),
      actor.id(),
      safeActorName(actor, post.authorDisplayName()),
      "",
      post.authorUserId(),
      "POST",
      post.id(),
      "有人点赞了你的帖子",
      safeActorName(actor, post.authorDisplayName()) + " 点赞了《" + post.title() + "》",
      "/community/posts/" + post.id(),
      Map.of("postId", post.id())
    ));
  }

  void notifyCommentLiked(CurrentUserView actor, PostRecord post, CommentRecord comment) {
    if (actor.id().equals(comment.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:comment-liked:" + comment.id() + ":" + actor.id() + ":" + comment.authorUserId(),
      "COMMENT_LIKED",
      SOURCE_SERVICE,
      comment.id(),
      actor.id(),
      safeActorName(actor, comment.authorDisplayName()),
      "",
      comment.authorUserId(),
      "COMMENT",
      comment.id(),
      "有人点赞了你的评论",
      safeActorName(actor, comment.authorDisplayName()) + " 点赞了你在《" + post.title() + "》下的评论",
      "/community/posts/" + post.id(),
      Map.of("postId", post.id(), "commentId", comment.id())
    ));
  }

  void notifyPostModerated(CurrentUserView admin, PostRecord post) {
    if (admin.id().equals(post.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:post-moderated:" + post.id() + ":" + post.updatedAt() + ":" + post.authorUserId(),
      "POST_MODERATED",
      SOURCE_SERVICE,
      post.id(),
      admin.id(),
      safeActorName(admin, admin.name()),
      "",
      post.authorUserId(),
      "POST",
      post.id(),
      "你的帖子状态有更新",
      "管理员更新了《" + post.title() + "》的可见状态",
      "/community/posts/" + post.id(),
      Map.of("postId", post.id(), "status", post.status(), "reviewStatus", post.reviewStatus(), "locked", post.locked())
    ));
  }

  void notifyCommentModerated(CurrentUserView admin, PostRecord post, CommentRecord comment) {
    if (admin.id().equals(comment.authorUserId())) return;
    send(new CreateNotificationEventRequest(
      "community:comment-moderated:" + comment.id() + ":" + comment.updatedAt() + ":" + comment.authorUserId(),
      "COMMENT_MODERATED",
      SOURCE_SERVICE,
      comment.id(),
      admin.id(),
      safeActorName(admin, admin.name()),
      "",
      comment.authorUserId(),
      "COMMENT",
      comment.id(),
      "你的评论状态有更新",
      "管理员更新了你在《" + post.title() + "》下评论的可见状态",
      "/community/posts/" + post.id(),
      Map.of("postId", post.id(), "commentId", comment.id(), "status", comment.status())
    ));
  }

  void notifyReportReviewed(CurrentUserView admin, ReportRecord report, String postId, String commentId) {
    if (admin.id().equals(report.reporterUserId())) return;
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("reportId", report.id());
    payload.put("targetType", report.targetType());
    payload.put("targetId", report.targetId());
    payload.put("status", report.status());
    if (!CommunityRules.clean(postId).isBlank()) payload.put("postId", postId);
    if (!CommunityRules.clean(commentId).isBlank()) payload.put("commentId", commentId);
    send(new CreateNotificationEventRequest(
      "community:report-reviewed:" + report.id() + ":" + report.updatedAt() + ":" + report.reporterUserId(),
      "REPORT_REVIEWED",
      SOURCE_SERVICE,
      report.id(),
      admin.id(),
      safeActorName(admin, admin.name()),
      "",
      report.reporterUserId(),
      report.targetType(),
      report.targetId(),
      "你提交的举报有结果了",
      "管理员处理了你提交的举报，当前状态为 " + report.status(),
      reportActionUrl(report, postId, commentId),
      payload
    ));
  }

  private void send(CreateNotificationEventRequest request) {
    try {
      notificationClient.createEvent(request);
    } catch (RuntimeException error) {
      log.warn("notification delivery skipped for event {}", request.eventKey(), error);
    }
  }

  private String safeActorName(CurrentUserView actor, String fallback) {
    var value = CommunityRules.clean(actor.name());
    if (!value.isBlank()) return value;
    return CommunityRules.clean(fallback);
  }

  private String postCommentUrl(String postId, String commentId) {
    return "/community/posts/" + postId + "?commentId=" + commentId;
  }

  private String reportActionUrl(ReportRecord report, String postId, String commentId) {
    return switch (report.targetType()) {
      case "COMMENT" -> postCommentUrl(postId, commentId);
      default -> "/community/posts/" + report.targetId();
    };
  }
}
