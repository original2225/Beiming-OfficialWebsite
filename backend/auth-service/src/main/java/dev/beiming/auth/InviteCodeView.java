package dev.beiming.auth;

public record InviteCodeView(
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
) {}
