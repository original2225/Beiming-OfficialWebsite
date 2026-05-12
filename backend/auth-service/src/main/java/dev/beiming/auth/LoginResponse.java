package dev.beiming.auth;

public record LoginResponse(String token, PublicUserView user) {}
