package dev.beiming.auth;

public enum InviteCodeStatus {
  ACTIVE,
  DISABLED;

  static InviteCodeStatus normalize(String value) {
    if (value == null || value.trim().isBlank()) return ACTIVE;
    return InviteCodeStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
