package dev.beiming.community;

public record PollVoteRecord(String id, String pollId, String optionId, String userId, long createdAt) {
}
