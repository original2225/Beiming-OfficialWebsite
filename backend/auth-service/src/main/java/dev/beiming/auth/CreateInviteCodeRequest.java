package dev.beiming.auth;

public record CreateInviteCodeRequest(String role, Integer maxUses, Long expiresAt) {}
