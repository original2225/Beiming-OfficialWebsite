package dev.beiming.auth;

public record SessionRecord(
  String tokenHash,
  String userId,
  long createdAt,
  long expiresAt
) {}
