package dev.beiming.auth;

public record PublicUserView(
  String id,
  String name,
  String email,
  String role,
  String status,
  long createdAt,
  long updatedAt,
  long lastLoginAt
) {}
