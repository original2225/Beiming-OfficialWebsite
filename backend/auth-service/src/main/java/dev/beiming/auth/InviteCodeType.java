package dev.beiming.auth;

public enum InviteCodeType {
  ADMIN,
  MEMBER;

  static InviteCodeType fromRole(UserRole role) {
    return role == UserRole.ADMIN ? ADMIN : MEMBER;
  }

  static InviteCodeType normalize(String value) {
    if (value == null || value.trim().isBlank()) return MEMBER;
    return InviteCodeType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
