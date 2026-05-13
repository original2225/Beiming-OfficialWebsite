package dev.beiming.profile;

import org.springframework.http.HttpStatus;

enum MemberStatus {
  ACTIVE,
  INACTIVE,
  LEFT,
  HIDDEN;

  static MemberStatus parse(String value) {
    var normalized = value == null || value.isBlank() ? ACTIVE.name() : value.trim().toUpperCase();
    try {
      return MemberStatus.valueOf(normalized);
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "成员状态不正确");
    }
  }
}
