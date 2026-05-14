package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CommentService {
  private final CommentRepository comments;
  private final PostService posts;
  private final PostRepository postRepository;
  private final InteractionRepository interactions;
  private final AuthClient auth;
  private final ProfileClient profiles;

  CommentService(CommentRepository comments, PostService posts, PostRepository postRepository, InteractionRepository interactions, AuthClient auth, ProfileClient profiles) {
    this.comments = comments;
    this.posts = posts;
    this.postRepository = postRepository;
    this.interactions = interactions;
    this.auth = auth;
    this.profiles = profiles;
  }

  List<CommentView> list(String authorization, String postId) {
    var viewer = auth.optionalUser(authorization);
    var post = posts.requirePost(postId);
    if (!posts.canViewPost(viewer, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    return comments.byPostId(postId).stream()
      .filter(comment -> CommunityRules.canViewComment(viewer, comment))
      .map(comment -> CommentView.fromRecord(comment, viewer != null && interactions.hasCommentLike(comment.id(), viewer.id())))
      .toList();
  }

  @Transactional
  public synchronized CommentView create(String authorization, String postId, CreateCommentRequest request) {
    var user = auth.requireUser(authorization);
    var post = posts.requirePost(postId);
    if (!posts.canViewPost(user, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    if (post.locked() && !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "帖子已锁定");
    var parentId = CommunityRules.clean(request.parentCommentId());
    if (!parentId.isBlank()) {
      var parent = comments.findById(parentId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "父评论不存在"));
      if (!parent.postId().equals(postId)) throw new ApiException(HttpStatus.BAD_REQUEST, "父评论不属于该帖子");
      if (!parent.parentCommentId().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "只支持两级评论");
    }
    var author = profiles.resolve(authorization, user).normalized();
    var now = CommunityRules.now();
    var comment = new CommentRecord(
      "comment-" + UUID.randomUUID().toString().substring(0, 8),
      postId,
      parentId,
      user.id(),
      author.displayName().isBlank() ? user.name() : author.displayName(),
      author.avatarUrl(),
      author.minecraftId(),
      CommunityRules.cleanRequired(request.content(), "评论内容不能为空"),
      CommentStatus.VISIBLE.name(),
      0L,
      now,
      now,
      0L,
      "",
      ""
    );
    comments.insert(comment);
    postRepository.adjustCommentCount(postId, 1);
    return CommentView.fromRecord(comment, false);
  }

  @Transactional
  public synchronized CommentView update(String authorization, String commentId, UpdateCommentRequest request) {
    var user = auth.requireUser(authorization);
    var current = comments.findById(commentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评论不存在"));
    CommunityRules.requireAuthorOrAdmin(user, current.authorUserId(), "没有权限编辑该评论");
    var post = posts.requirePost(current.postId());
    if (post.locked() && !user.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "帖子已锁定");
    if (CommentStatus.parse(current.status()) != CommentStatus.VISIBLE && !user.isAdmin()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "隐藏评论只能由管理员编辑");
    }
    if (CommentStatus.parse(current.status()) == CommentStatus.DELETED) throw new ApiException(HttpStatus.NOT_FOUND, "评论不存在");
    var next = new CommentRecord(
      current.id(),
      current.postId(),
      current.parentCommentId(),
      current.authorUserId(),
      current.authorDisplayName(),
      current.authorAvatarUrl(),
      current.authorMinecraftId(),
      CommunityRules.cleanRequired(request.content(), "评论内容不能为空"),
      current.status(),
      current.likeCount(),
      current.createdAt(),
      CommunityRules.now(),
      current.deletedAt(),
      current.lastModeratedBy(),
      current.moderationNote()
    );
    comments.update(next);
    return CommentView.fromRecord(next, user != null && interactions.hasCommentLike(next.id(), user.id()));
  }

  @Transactional
  public synchronized void softDelete(String authorization, String commentId) {
    var user = auth.requireUser(authorization);
    var current = comments.findById(commentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评论不存在"));
    CommunityRules.requireAuthorOrAdmin(user, current.authorUserId(), "没有权限删除该评论");
    if (CommentStatus.parse(current.status()) == CommentStatus.DELETED) return;
    comments.update(new CommentRecord(
      current.id(), current.postId(), current.parentCommentId(), current.authorUserId(), current.authorDisplayName(),
      current.authorAvatarUrl(), current.authorMinecraftId(), current.content(), CommentStatus.DELETED.name(), current.likeCount(),
      current.createdAt(), CommunityRules.now(), CommunityRules.now(), current.lastModeratedBy(), current.moderationNote()
    ));
    if (CommentStatus.parse(current.status()) == CommentStatus.VISIBLE) {
      postRepository.adjustCommentCount(current.postId(), -1);
    }
  }

  @Transactional
  public synchronized CommentView moderate(String authorization, String commentId, ModerateCommentRequest request) {
    var user = auth.requireUser(authorization);
    CommunityRules.requireAdmin(user);
    var current = comments.findById(commentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评论不存在"));
    var nextStatus = current.status();
    if (Boolean.TRUE.equals(request.hidden())) nextStatus = CommentStatus.HIDDEN.name();
    if (Boolean.TRUE.equals(request.restore())) nextStatus = CommentStatus.VISIBLE.name();
    comments.update(new CommentRecord(
      current.id(), current.postId(), current.parentCommentId(), current.authorUserId(), current.authorDisplayName(),
      current.authorAvatarUrl(), current.authorMinecraftId(), current.content(), nextStatus, current.likeCount(),
      current.createdAt(), CommunityRules.now(), "VISIBLE".equals(nextStatus) ? 0L : current.deletedAt(), user.id(),
      request.moderationNote() == null ? current.moderationNote() : CommunityRules.clean(request.moderationNote())
    ));
    if (CommentStatus.parse(current.status()) == CommentStatus.VISIBLE && CommentStatus.parse(nextStatus) != CommentStatus.VISIBLE) {
      postRepository.adjustCommentCount(current.postId(), -1);
    }
    if (CommentStatus.parse(current.status()) != CommentStatus.VISIBLE && CommentStatus.parse(nextStatus) == CommentStatus.VISIBLE) {
      postRepository.adjustCommentCount(current.postId(), 1);
    }
    var next = comments.findById(commentId).orElseThrow();
    return CommentView.fromRecord(next, false);
  }

  CommentRecord requireComment(String commentId) {
    return comments.findById(commentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评论不存在"));
  }
}
