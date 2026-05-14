package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PostService {
  private final PostRepository posts;
  private final BoardRepository boards;
  private final InteractionRepository interactions;
  private final AuthClient auth;
  private final ProfileClient profiles;
  private final PollService polls;
  private final RateLimitService rateLimits;

  PostService(PostRepository posts, BoardRepository boards, InteractionRepository interactions, AuthClient auth, ProfileClient profiles, PollService polls, RateLimitService rateLimits) {
    this.posts = posts;
    this.boards = boards;
    this.interactions = interactions;
    this.auth = auth;
    this.profiles = profiles;
    this.polls = polls;
    this.rateLimits = rateLimits;
  }

  PageResult<PostSummaryView> publicPosts(String authorization, String boardId, int page, int pageSize, String q, String sort) {
    var viewer = auth.optionalUser(authorization);
    if (!CommunityRules.clean(boardId).isBlank()) {
      var board = requireBoard(boardId);
      if (!CommunityRules.canViewBoard(viewer, board.visibility())) throw new ApiException(HttpStatus.NOT_FOUND, "板块不存在");
    }
    var contentVisibilities = contentVisibilitiesFor(viewer);
    var boardVisibilities = boardVisibilitiesFor(viewer);
    var normalizedPage = CommunityRules.normalizePage(page);
    var normalizedSize = CommunityRules.normalizePageSize(pageSize);
    var items = posts.publicList(contentVisibilities, boardVisibilities, boardId, q, sort, normalizedPage, normalizedSize).stream()
      .map(PostSummaryView::fromRecord)
      .toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, posts.countPublic(contentVisibilities, boardVisibilities, boardId, q));
  }

  PostDetailView detail(String authorization, String postId, boolean incrementView) {
    var viewer = auth.optionalUser(authorization);
    var post = posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
    if (!canViewPost(viewer, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    if (incrementView) {
      posts.incrementViewCount(postId);
      post = posts.findById(postId).orElse(post);
    }
    return toDetail(post, viewer, authorization);
  }

  @Transactional
  public PostDetailView create(String authorization, CreatePostRequest request) {
    var user = auth.requireUser(authorization);
    rateLimits.posts(user);
    var board = requireBoard(CommunityRules.cleanRequired(request.boardId(), "板块不能为空"));
    if (!CommunityRules.canPostToBoard(user, board)) throw new ApiException(HttpStatus.FORBIDDEN, "没有该板块发帖权限");
    CommunityRules.validatePollRequest(request.poll());
    var status = CommunityRules.editablePostStatus(request.status());
    var visibility = CommunityRules.contentVisibility(request.visibility());
    if (visibility == ContentVisibility.ADMIN_ONLY && !user.isAdmin()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "没有权限发布管理员可见内容");
    }
    var now = CommunityRules.now();
    var author = profiles.resolve(authorization, user).normalized();
    var post = new PostRecord(
      "post-" + UUID.randomUUID().toString().substring(0, 8),
      board.id(),
      user.id(),
      author.displayName().isBlank() ? user.name() : author.displayName(),
      author.avatarUrl(),
      author.minecraftId(),
      CommunityRules.cleanRequired(request.title(), "帖子标题不能为空"),
      CommunityRules.cleanRequired(request.content(), "帖子内容不能为空"),
      status.name(),
      visibility.name(),
      ReviewStatus.APPROVED.name(),
      false,
      false,
      0L,
      0L,
      0L,
      0L,
      request.poll() != null,
      now,
      now,
      status == PostStatus.PUBLISHED ? now : 0L,
      0L,
      "",
      ""
    );
    posts.insert(post);
    if (request.poll() != null) polls.createForPost(post.id(), request.poll());
    return toDetail(posts.findById(post.id()).orElse(post), user, authorization);
  }

  @Transactional
  public PostDetailView update(String authorization, String postId, UpdatePostRequest request) {
    var user = auth.requireUser(authorization);
    rateLimits.posts(user);
    var current = posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
    CommunityRules.requireAuthorOrAdmin(user, current.authorUserId(), "没有权限编辑该帖子");
    if (current.locked() && !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "帖子已锁定");
    if (PostStatus.parse(current.status()) == PostStatus.HIDDEN && !user.isAdmin()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "隐藏帖子只能由管理员编辑");
    }
    if (PostStatus.parse(current.status()) == PostStatus.DELETED) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    var nextStatus = request.status() == null ? PostStatus.parse(current.status()) : CommunityRules.editablePostStatus(request.status());
    var nextVisibility = request.visibility() == null ? current.visibility() : CommunityRules.contentVisibility(request.visibility()).name();
    if ("ADMIN_ONLY".equals(nextVisibility) && !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "没有权限发布管理员可见内容");
    var next = new PostRecord(
      current.id(),
      current.boardId(),
      current.authorUserId(),
      current.authorDisplayName(),
      current.authorAvatarUrl(),
      current.authorMinecraftId(),
      request.title() == null ? current.title() : CommunityRules.cleanRequired(request.title(), "帖子标题不能为空"),
      request.content() == null ? current.content() : CommunityRules.cleanRequired(request.content(), "帖子内容不能为空"),
      nextStatus.name(),
      nextVisibility,
      current.reviewStatus(),
      current.pinned(),
      current.locked(),
      current.viewCount(),
      current.commentCount(),
      current.likeCount(),
      current.favoriteCount(),
      current.hasPoll(),
      current.createdAt(),
      CommunityRules.now(),
      nextStatus == PostStatus.PUBLISHED ? (current.publishedAt() > 0 ? current.publishedAt() : CommunityRules.now()) : 0L,
      current.deletedAt(),
      current.lastModeratedBy(),
      current.moderationNote()
    );
    posts.update(next);
    return toDetail(next, user, authorization);
  }

  @Transactional
  public void softDelete(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    rateLimits.posts(user);
    var current = posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
    CommunityRules.requireAuthorOrAdmin(user, current.authorUserId(), "没有权限删除该帖子");
    if (PostStatus.parse(current.status()) == PostStatus.DELETED) return;
    var next = new PostRecord(
      current.id(), current.boardId(), current.authorUserId(), current.authorDisplayName(), current.authorAvatarUrl(), current.authorMinecraftId(),
      current.title(), current.content(), PostStatus.DELETED.name(), current.visibility(), current.reviewStatus(), current.pinned(), current.locked(),
      current.viewCount(), current.commentCount(), current.likeCount(), current.favoriteCount(), current.hasPoll(),
      current.createdAt(), CommunityRules.now(), current.publishedAt(), CommunityRules.now(), current.lastModeratedBy(), current.moderationNote()
    );
    posts.update(next);
  }

  PageResult<PostSummaryView> adminPosts(String authorization, String boardId, String status, String authorUserId, String q, int page, int pageSize) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var normalizedPage = CommunityRules.normalizePage(page);
    var normalizedSize = CommunityRules.normalizePageSize(pageSize);
    var items = posts.adminList(boardId, status, authorUserId, q, normalizedPage, normalizedSize).stream().map(PostSummaryView::fromRecord).toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, posts.countAdmin(boardId, status, authorUserId, q));
  }

  @Transactional
  public PostDetailView moderate(String authorization, String postId, ModeratePostRequest request) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var current = posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
    var hidden = Boolean.TRUE.equals(request.hidden());
    var restore = Boolean.TRUE.equals(request.restore());
    var status = current.status();
    if (hidden) status = PostStatus.HIDDEN.name();
    if (restore) status = PostStatus.PUBLISHED.name();
    var next = new PostRecord(
      current.id(),
      current.boardId(),
      current.authorUserId(),
      current.authorDisplayName(),
      current.authorAvatarUrl(),
      current.authorMinecraftId(),
      current.title(),
      current.content(),
      status,
      request.visibility() == null ? current.visibility() : CommunityRules.contentVisibility(request.visibility()).name(),
      request.reviewStatus() == null ? current.reviewStatus() : ReviewStatus.parse(request.reviewStatus()).name(),
      request.pinned() == null ? current.pinned() : request.pinned(),
      request.locked() == null ? current.locked() : request.locked(),
      current.viewCount(),
      current.commentCount(),
      current.likeCount(),
      current.favoriteCount(),
      current.hasPoll(),
      current.createdAt(),
      CommunityRules.now(),
      "PUBLISHED".equals(status) ? (current.publishedAt() > 0 ? current.publishedAt() : CommunityRules.now()) : current.publishedAt(),
      restore ? 0L : current.deletedAt(),
      user.id(),
      request.moderationNote() == null ? current.moderationNote() : CommunityRules.clean(request.moderationNote())
    );
    posts.update(next);
    return toDetail(next, user, authorization);
  }

  PageResult<PostSummaryView> favorites(String authorization, int page, int pageSize) {
    var user = auth.requireUser(authorization);
    var normalizedPage = CommunityRules.normalizePage(page);
    var normalizedSize = CommunityRules.normalizePageSize(pageSize);
    var contentVisibilities = contentVisibilitiesFor(user);
    var boardVisibilities = boardVisibilitiesFor(user);
    var items = posts.favoriteList(user.id(), contentVisibilities, boardVisibilities, normalizedPage, normalizedSize).stream()
      .map(PostSummaryView::fromRecord)
      .toList();
    return new PageResult<>(items, normalizedPage, normalizedSize, posts.countFavorites(user.id(), contentVisibilities, boardVisibilities));
  }

  PostRecord requirePost(String postId) {
    return posts.findById(postId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "帖子不存在"));
  }

  boolean canViewPost(CurrentUserView viewer, PostRecord post) {
    if (!CommunityRules.canViewPost(viewer, post)) return false;
    var board = boards.findById(post.boardId()).orElse(null);
    return board != null && CommunityRules.canViewBoard(viewer, board.visibility());
  }

  private BoardRecord requireBoard(String boardId) {
    return boards.findById(boardId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "板块不存在"));
  }

  private List<String> contentVisibilitiesFor(CurrentUserView viewer) {
    return viewer != null && viewer.isAdmin()
      ? List.of("PUBLIC", "MEMBER_ONLY", "ADMIN_ONLY")
      : viewer != null ? List.of("PUBLIC", "MEMBER_ONLY") : List.of("PUBLIC");
  }

  private List<String> boardVisibilitiesFor(CurrentUserView viewer) {
    return viewer != null && viewer.isAdmin()
      ? List.of("PUBLIC", "MEMBER_ONLY", "ADMIN_ONLY", "HIDDEN")
      : viewer != null ? List.of("PUBLIC", "MEMBER_ONLY") : List.of("PUBLIC");
  }

  PostDetailView toDetail(PostRecord post, CurrentUserView viewer, String authorization) {
    var liked = viewer != null && interactions.hasPostLike(post.id(), viewer.id());
    var favorited = viewer != null && interactions.hasFavorite(post.id(), viewer.id());
    return PostDetailView.fromRecord(post, liked, favorited, polls.viewForPost(authorization, post));
  }
}
