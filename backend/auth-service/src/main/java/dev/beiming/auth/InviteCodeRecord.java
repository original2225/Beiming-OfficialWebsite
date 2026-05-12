package dev.beiming.auth;

public record InviteCodeRecord(
  String id,
  String code,
  String type,
  String role,
  String status,
  int maxUses,
  int usedCount,
  long expiresAt,
  String createdBy,
  long createdAt,
  long updatedAt
) {
  InviteCodeRecord normalized() {
    return new InviteCodeRecord(
      clean(id),
      clean(code),
      InviteCodeType.normalize(type).name(),
      UserRole.normalize(role).name(),
      InviteCodeStatus.normalize(status).name(),
      Math.max(1, maxUses),
      Math.max(0, usedCount),
      expiresAt,
      clean(createdBy),
      createdAt,
      updatedAt
    );
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
