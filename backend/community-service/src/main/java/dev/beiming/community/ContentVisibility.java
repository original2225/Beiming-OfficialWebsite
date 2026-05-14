package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum ContentVisibility {
  PUBLIC,
  MEMBER_ONLY,
  ADMIN_ONLY;

  static ContentVisibility parse(String value) {
    try {
      return value == null || value.isBlank() ? PUBLIC : ContentVisibility.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "内容可见性不正确");
    }
  }
}
