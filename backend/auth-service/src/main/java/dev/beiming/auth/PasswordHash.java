package dev.beiming.auth;

public record PasswordHash(String passwordHash, String passwordSalt) {}
