package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum PostingRole {
  MEMBER,
  ADMIN;

  static PostingRole parse(String value) {
    try {
      return value == null || value.isBlank() ? MEMBER : PostingRole.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "发帖权限不正确");
    }
  }
}
