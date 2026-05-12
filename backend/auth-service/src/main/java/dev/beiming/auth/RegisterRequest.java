package dev.beiming.auth;

public record RegisterRequest(String name, String email, String password, String inviteCode) {}
