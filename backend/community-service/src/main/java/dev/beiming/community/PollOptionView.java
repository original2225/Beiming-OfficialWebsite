package dev.beiming.community;

public record PollOptionView(String id, String optionText, int sortOrder, long voteCount) {
}
