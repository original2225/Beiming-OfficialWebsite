package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum PostStatus {
  DRAFT,
  PUBLISHED,
  HIDDEN,
  DELETED;

  static PostStatus parse(String value) {
    try {
      return value == null || value.isBlank() ? PUBLISHED : PostStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "帖子状态不正确");
    }
  }
}
