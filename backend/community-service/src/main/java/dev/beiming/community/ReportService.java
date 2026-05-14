package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReportService {
  private final ReportRepository reports;
  private final PostService posts;
  private final CommentService comments;
  private final AuthClient auth;
  private final RateLimitService rateLimits;

  ReportService(ReportRepository reports, PostService posts, CommentService comments, AuthClient auth, RateLimitService rateLimits) {
    this.reports = reports;
    this.posts = posts;
    this.comments = comments;
    this.auth = auth;
    this.rateLimits = rateLimits;
  }

  @Transactional
  public synchronized ReportView reportPost(String authorization, String postId, CreateReportRequest request) {
    var user = auth.requireUser(authorization);
    rateLimits.reports(user);
    var post = posts.requirePost(postId);
    if (!posts.canViewPost(user, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    return createReport(user, ReportTargetType.POST.name(), postId, request);
  }

  @Transactional
  public synchronized ReportView reportComment(String authorization, String commentId, CreateReportRequest request) {
    var user = auth.requireUser(authorization);
    rateLimits.reports(user);
    var comment = comments.requireComment(commentId);
    var post = posts.requirePost(comment.postId());
    if (!posts.canViewPost(user, post) || CommentStatus.parse(comment.status()) != CommentStatus.VISIBLE) {
      throw new ApiException(HttpStatus.NOT_FOUND, "评论不存在");
    }
    return createReport(user, ReportTargetType.COMMENT.name(), commentId, request);
  }

  PageResult<ReportView> adminReports(String authorization, String status, int page, int pageSize) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var normalizedPage = CommunityRules.normalizePage(page);
    var normalizedSize = CommunityRules.normalizePageSize(pageSize);
    var items = reports.list(status, normalizedPage, normalizedSize).stream().map(ReportView::fromRecord).toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, reports.count(status));
  }

  @Transactional
  public synchronized ReportView review(String authorization, String reportId, ReviewReportRequest request) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var current = reports.findById(reportId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "举报不存在"));
    var status = ReportStatus.parse(request.status());
    if (status == ReportStatus.OPEN) throw new ApiException(HttpStatus.BAD_REQUEST, "举报处理状态不正确");
    var next = new ReportRecord(
      current.id(),
      current.targetType(),
      current.targetId(),
      current.reporterUserId(),
      current.reporterDisplayName(),
      current.reason(),
      current.detail(),
      status.name(),
      user.id(),
      CommunityRules.clean(request.reviewNote()),
      current.createdAt(),
      CommunityRules.now(),
      CommunityRules.now()
    );
    reports.update(next);
    return ReportView.fromRecord(next);
  }

  private ReportView createReport(CurrentUserView user, String targetType, String targetId, CreateReportRequest request) {
    var reason = ReportReason.parse(request == null ? null : request.reason()).name();
    if (reports.hasOpenDuplicate(targetType, targetId, user.id(), reason)) {
      throw new ApiException(HttpStatus.CONFLICT, "相同原因的举报已经提交");
    }
    var now = CommunityRules.now();
    var record = new ReportRecord(
      "report-" + UUID.randomUUID().toString().substring(0, 8),
      targetType,
      targetId,
      user.id(),
      CommunityRules.clean(user.name()),
      reason,
      CommunityRules.clean(request == null ? "" : request.detail()),
      ReportStatus.OPEN.name(),
      "",
      "",
      now,
      now,
      0L
    );
    reports.insert(record);
    return ReportView.fromRecord(record);
  }
}
