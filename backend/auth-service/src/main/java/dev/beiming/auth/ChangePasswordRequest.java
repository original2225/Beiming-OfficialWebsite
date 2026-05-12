package dev.beiming.auth;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}
