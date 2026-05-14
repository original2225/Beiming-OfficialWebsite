package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReactionService {
  private final InteractionRepository interactions;
  private final PostService posts;
  private final PostRepository postRepository;
  private final CommentService comments;
  private final CommentRepository commentRepository;
  private final AuthClient auth;

  ReactionService(InteractionRepository interactions, PostService posts, PostRepository postRepository, CommentService comments, CommentRepository commentRepository, AuthClient auth) {
    this.interactions = interactions;
    this.posts = posts;
    this.postRepository = postRepository;
    this.comments = comments;
    this.commentRepository = commentRepository;
    this.auth = auth;
  }

  @Transactional
  public void likePost(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    var post = posts.requirePost(postId);
    if (!posts.canViewPost(user, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    if (interactions.addPostLike(postId, user.id())) {
      postRepository.adjustLikeCount(postId, 1);
    }
  }

  @Transactional
  public void unlikePost(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    if (interactions.removePostLike(postId, user.id())) {
      postRepository.adjustLikeCount(postId, -1);
    }
  }

  @Transactional
  public void likeComment(String authorization, String commentId) {
    var user = auth.requireUser(authorization);
    var comment = comments.requireComment(commentId);
    var post = posts.requirePost(comment.postId());
    if (!posts.canViewPost(user, post) || CommentStatus.parse(comment.status()) != CommentStatus.VISIBLE) {
      throw new ApiException(HttpStatus.NOT_FOUND, "评论不存在");
    }
    if (interactions.addCommentLike(commentId, user.id())) {
      commentRepository.adjustLikeCount(commentId, 1);
    }
  }

  @Transactional
  public void unlikeComment(String authorization, String commentId) {
    var user = auth.requireUser(authorization);
    if (interactions.removeCommentLike(commentId, user.id())) {
      commentRepository.adjustLikeCount(commentId, -1);
    }
  }
}
