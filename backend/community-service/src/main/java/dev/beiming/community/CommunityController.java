package dev.beiming.community;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CommunityController {
  private final BoardService boards;
  private final PostService posts;
  private final CommentService comments;
  private final ReactionService reactions;
  private final FavoriteService favorites;
  private final ReportService reports;
  private final PollService polls;

  CommunityController(
    BoardService boards,
    PostService posts,
    CommentService comments,
    ReactionService reactions,
    FavoriteService favorites,
    ReportService reports,
    PollService polls
  ) {
    this.boards = boards;
    this.posts = posts;
    this.comments = comments;
    this.reactions = reactions;
    this.favorites = favorites;
    this.reports = reports;
    this.polls = polls;
  }

  @GetMapping("/health")
  ApiEnvelope<Map<String, Object>> health() {
    return ApiEnvelope.ok(Map.of("service", "beiming-community-service"));
  }

  @GetMapping("/api/community/boards")
  ApiEnvelope<List<BoardView>> boardList(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
    return ApiEnvelope.ok(boards.publicBoards(authorization));
  }

  @GetMapping("/api/community/posts")
  ApiEnvelope<PageResult<PostSummaryView>> postList(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "") String boardId,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize,
    @RequestParam(defaultValue = "") String q,
    @RequestParam(defaultValue = "latest") String sort
  ) {
    return ApiEnvelope.ok(posts.publicPosts(authorization, boardId, page, pageSize, q, sort));
  }

  @GetMapping("/api/community/posts/{postId}")
  ApiEnvelope<PostDetailView> postDetail(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    return ApiEnvelope.ok(posts.detail(authorization, postId, true));
  }

  @PostMapping("/api/community/posts")
  ApiEnvelope<PostDetailView> createPost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestBody CreatePostRequest body
  ) {
    return ApiEnvelope.ok(posts.create(authorization, body));
  }

  @PutMapping("/api/community/posts/{postId}")
  ApiEnvelope<PostDetailView> updatePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestBody UpdatePostRequest body
  ) {
    return ApiEnvelope.ok(posts.update(authorization, postId, body));
  }

  @DeleteMapping("/api/community/posts/{postId}")
  ApiEnvelope<Map<String, Object>> deletePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    posts.softDelete(authorization, postId);
    return ApiEnvelope.ok(Map.of("deleted", true));
  }

  @GetMapping("/api/community/posts/{postId}/comments")
  ApiEnvelope<PageResult<CommentView>> commentList(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(comments.list(authorization, postId, page, pageSize));
  }

  @PostMapping("/api/community/posts/{postId}/comments")
  ApiEnvelope<CommentView> createComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestBody CreateCommentRequest body
  ) {
    return ApiEnvelope.ok(comments.create(authorization, postId, body));
  }

  @PutMapping("/api/community/comments/{commentId}")
  ApiEnvelope<CommentView> updateComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId,
    @RequestBody UpdateCommentRequest body
  ) {
    return ApiEnvelope.ok(comments.update(authorization, commentId, body));
  }

  @DeleteMapping("/api/community/comments/{commentId}")
  ApiEnvelope<Map<String, Object>> deleteComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId
  ) {
    comments.softDelete(authorization, commentId);
    return ApiEnvelope.ok(Map.of("deleted", true));
  }

  @PostMapping("/api/community/posts/{postId}/reactions")
  ApiEnvelope<Map<String, Object>> likePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    reactions.likePost(authorization, postId);
    return ApiEnvelope.ok(Map.of("liked", true));
  }

  @DeleteMapping("/api/community/posts/{postId}/reactions")
  ApiEnvelope<Map<String, Object>> unlikePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    reactions.unlikePost(authorization, postId);
    return ApiEnvelope.ok(Map.of("liked", false));
  }

  @PostMapping("/api/community/comments/{commentId}/reactions")
  ApiEnvelope<Map<String, Object>> likeComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId
  ) {
    reactions.likeComment(authorization, commentId);
    return ApiEnvelope.ok(Map.of("liked", true));
  }

  @DeleteMapping("/api/community/comments/{commentId}/reactions")
  ApiEnvelope<Map<String, Object>> unlikeComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId
  ) {
    reactions.unlikeComment(authorization, commentId);
    return ApiEnvelope.ok(Map.of("liked", false));
  }

  @PostMapping("/api/community/posts/{postId}/favorites")
  ApiEnvelope<Map<String, Object>> favoritePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    favorites.favorite(authorization, postId);
    return ApiEnvelope.ok(Map.of("favorited", true));
  }

  @DeleteMapping("/api/community/posts/{postId}/favorites")
  ApiEnvelope<Map<String, Object>> unfavoritePost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    favorites.unfavorite(authorization, postId);
    return ApiEnvelope.ok(Map.of("favorited", false));
  }

  @GetMapping("/api/community/me/favorites")
  ApiEnvelope<PageResult<PostSummaryView>> favoriteList(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int pageSize
  ) {
    return ApiEnvelope.ok(posts.favorites(authorization, page, pageSize));
  }

  @PostMapping("/api/community/posts/{postId}/reports")
  ApiEnvelope<ReportView> reportPost(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestBody CreateReportRequest body
  ) {
    return ApiEnvelope.ok(reports.reportPost(authorization, postId, body));
  }

  @PostMapping("/api/community/comments/{commentId}/reports")
  ApiEnvelope<ReportView> reportComment(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String commentId,
    @RequestBody CreateReportRequest body
  ) {
    return ApiEnvelope.ok(reports.reportComment(authorization, commentId, body));
  }

  @PostMapping("/api/community/posts/{postId}/poll/votes")
  ApiEnvelope<PollView> votePoll(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId,
    @RequestBody CastPollVoteRequest body
  ) {
    return ApiEnvelope.ok(polls.vote(authorization, postId, body));
  }

  @DeleteMapping("/api/community/posts/{postId}/poll/votes")
  ApiEnvelope<PollView> retractPollVote(
    @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
    @PathVariable String postId
  ) {
    return ApiEnvelope.ok(polls.retract(authorization, postId));
  }
}
