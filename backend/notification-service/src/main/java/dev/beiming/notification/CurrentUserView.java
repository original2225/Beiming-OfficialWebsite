package dev.beiming.notification;

public record CurrentUserView(String id, String name, String email, String role) {
  boolean isAdmin() {
    return "SUPER_ADMIN".equals(role) || "ADMIN".equals(role);
  }
}
