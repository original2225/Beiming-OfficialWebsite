package dev.beiming.community;

public record AuthorSnapshot(String userId, String displayName, String avatarUrl, String minecraftId) {
  static AuthorSnapshot fromUser(CurrentUserView user) {
    return new AuthorSnapshot(user.id(), clean(user.name()), "", "");
  }

  AuthorSnapshot normalized() {
    return new AuthorSnapshot(clean(userId), clean(displayName), clean(avatarUrl), clean(minecraftId));
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
