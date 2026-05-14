package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum CommentStatus {
  VISIBLE,
  HIDDEN,
  DELETED;

  static CommentStatus parse(String value) {
    try {
      return value == null || value.isBlank() ? VISIBLE : CommentStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "评论状态不正确");
    }
  }
}
