package dev.beiming.auth;

import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum UserStatus {
  ACTIVE,
  DISABLED;

  static UserStatus parse(String value) {
    try {
      return UserStatus.valueOf(clean(value));
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "无效状态");
    }
  }

  static UserStatus normalize(String value) {
    return clean(value).isBlank() ? ACTIVE : parse(value);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
