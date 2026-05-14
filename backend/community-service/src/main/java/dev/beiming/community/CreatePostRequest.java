package dev.beiming.community;

public record CreatePostRequest(
  String boardId,
  String title,
  String content,
  String status,
  String visibility,
  CreatePollRequest poll
) {
}
