package dev.beiming.community;

public record ModeratePostRequest(
  Boolean pinned,
  Boolean locked,
  Boolean hidden,
  Boolean restore,
  String visibility,
  String reviewStatus,
  String moderationNote
) {
}
