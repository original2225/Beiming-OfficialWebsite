package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum BoardVisibility {
  PUBLIC,
  MEMBER_ONLY,
  ADMIN_ONLY,
  HIDDEN;

  static BoardVisibility parse(String value) {
    try {
      return value == null || value.isBlank() ? PUBLIC : BoardVisibility.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "板块可见性不正确");
    }
  }
}
