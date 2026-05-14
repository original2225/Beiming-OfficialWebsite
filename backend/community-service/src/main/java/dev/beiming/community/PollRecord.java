package dev.beiming.community;

public record PollRecord(
  String id,
  String postId,
  String question,
  String voteMode,
  String resultVisibility,
  long closesAt,
  boolean closed,
  long createdAt,
  long updatedAt
) {
}
