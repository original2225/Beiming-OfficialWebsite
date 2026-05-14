package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
  private final InteractionRepository interactions;
  private final PostService posts;
  private final PostRepository postRepository;
  private final AuthClient auth;
  private final RateLimitService rateLimits;

  FavoriteService(InteractionRepository interactions, PostService posts, PostRepository postRepository, AuthClient auth, RateLimitService rateLimits) {
    this.interactions = interactions;
    this.posts = posts;
    this.postRepository = postRepository;
    this.auth = auth;
    this.rateLimits = rateLimits;
  }

  @Transactional
  public void favorite(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    rateLimits.favorites(user);
    var post = posts.requirePost(postId);
    if (!posts.canViewPost(user, post)) throw new ApiException(HttpStatus.NOT_FOUND, "帖子不存在");
    if (interactions.addFavorite(postId, user.id())) {
      postRepository.adjustFavoriteCount(postId, 1);
    }
  }

  @Transactional
  public void unfavorite(String authorization, String postId) {
    var user = auth.requireUser(authorization);
    rateLimits.favorites(user);
    if (interactions.removeFavorite(postId, user.id())) {
      postRepository.adjustFavoriteCount(postId, -1);
    }
  }
}
