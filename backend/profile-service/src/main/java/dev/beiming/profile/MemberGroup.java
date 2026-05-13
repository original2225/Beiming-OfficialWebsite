package dev.beiming.profile;

import org.springframework.http.HttpStatus;

enum MemberGroup {
  MEMBER,
  TRAINEE,
  ADMIN;

  static MemberGroup parse(String value) {
    var normalized = value == null || value.isBlank() ? MEMBER.name() : value.trim().toUpperCase();
    try {
      return MemberGroup.valueOf(normalized);
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "成员身份组不正确");
    }
  }
}
