package dev.beiming.auth;

import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum UserRole {
  SUPER_ADMIN,
  ADMIN,
  MEMBER;

  static UserRole parse(String value) {
    try {
      return UserRole.valueOf(clean(value));
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "无效角色");
    }
  }

  static UserRole normalize(String value) {
    return clean(value).isBlank() ? MEMBER : parse(value);
  }

  boolean isAdminRole() {
    return this == SUPER_ADMIN || this == ADMIN;
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
