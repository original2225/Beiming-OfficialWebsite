package dev.beiming.community;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommunityAdminController {
  private final BoardService boards;
  private final PostService posts;
  private final CommentService comments;
  private final ReportService reports;
  private final AuditLogService auditLogs;

  CommunityAdminController(BoardService boards, PostService posts, CommentService comments, ReportService reports, AuditLogService auditLogs) {
    this.boards = boards;
    this.posts = posts;
    this.comments = comments;
    this.reports = reports;
    this.auditLogs = auditLogs;
  }

  @PostMapping("/api/community/admin/boards")
  ApiEnvelope<BoardView> createBoard(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody CreateBoardRequest body
  ) {
    return ApiEnvelope.ok(boards.create(authorization, body));
  }

  @PutMapping("/api/community/admin/boards/{boardId}")
  ApiEnvelope<BoardView> updateBoard(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String boardId,
    @RequestBody UpdateBoardRequest body
  ) {
    return ApiEnvelope.ok(boards.update(authorization, boardId, body));
  }

  @GetMapping("/api/community/admin/posts")
  ApiEnvelope<PageResult<PostSummaryView>> adminPosts(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "") String boardId,
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "") String authorUserId,
    @RequestParam(defaultValue = "") String q,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(posts.adminPosts(authorization, boardId, status, authorUserId, q, page, pageSize));
  }

  @PutMapping("/api/community/admin/posts/{postId}/moderation")
  ApiEnvelope<PostDetailView> moderatePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestBody ModeratePostRequest body
  ) {
    return ApiEnvelope.ok(posts.moderate(authorization, postId, body));
  }

  @PutMapping("/api/community/admin/comments/{commentId}/moderation")
  ApiEnvelope<CommentView> moderateComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId,
    @RequestBody ModerateCommentRequest body
  ) {
    return ApiEnvelope.ok(comments.moderate(authorization, commentId, body));
  }

  @GetMapping("/api/community/admin/reports")
  ApiEnvelope<PageResult<ReportView>> adminReports(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(reports.adminReports(authorization, status, page, pageSize));
  }

  @PutMapping("/api/community/admin/reports/{reportId}")
  ApiEnvelope<ReportView> reviewReport(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String reportId,
    @RequestBody ReviewReportRequest body
  ) {
    return ApiEnvelope.ok(reports.review(authorization, reportId, body));
  }

  @GetMapping("/api/community/admin/audit-logs")
  ApiEnvelope<PageResult<AuditLogView>> auditLogs(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(auditLogs.list(authorization, page, pageSize));
  }
}
