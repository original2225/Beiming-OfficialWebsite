package dev.beiming.community;

public record UpdatePostRequest(
  String title,
  String content,
  String status,
  String visibility
) {
}
