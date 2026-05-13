package dev.beiming.profile;

import org.springframework.http.HttpStatus;

enum ProfileVisibility {
  PUBLIC,
  MEMBER_ONLY,
  PRIVATE;

  static ProfileVisibility parse(String value) {
    var normalized = value == null || value.isBlank() ? PUBLIC.name() : value.trim().toUpperCase();
    try {
      return ProfileVisibility.valueOf(normalized);
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "公开范围不正确");
    }
  }
}
