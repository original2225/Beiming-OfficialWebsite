package dev.beiming.community;

public record CreateCommentRequest(String parentCommentId, String content) {
}
