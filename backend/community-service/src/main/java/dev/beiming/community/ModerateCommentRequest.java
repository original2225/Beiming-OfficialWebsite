package dev.beiming.community;

public record ModerateCommentRequest(
  Boolean hidden,
  Boolean restore,
  String moderationNote
) {
}
