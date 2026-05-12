package dev.beiming.auth;

public record UserRecord(
  String id,
  String name,
  String email,
  String passwordHash,
  String passwordSalt,
  String role,
  String status,
  long createdAt,
  long updatedAt,
  long lastLoginAt
) {
  UserRecord normalized() {
    return new UserRecord(
      clean(id),
      clean(name),
      clean(email).toLowerCase(),
      clean(passwordHash),
      clean(passwordSalt),
      UserRole.normalize(role).name(),
      UserStatus.normalize(status).name(),
      createdAt,
      updatedAt,
      lastLoginAt
    );
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
