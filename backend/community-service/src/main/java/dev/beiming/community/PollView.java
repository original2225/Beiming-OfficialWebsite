package dev.beiming.community;

import java.util.List;

public record PollView(
  String id,
  String question,
  String voteMode,
  String resultVisibility,
  long closesAt,
  boolean closed,
  boolean voted,
  boolean resultsVisible,
  long totalVotes,
  List<String> myOptionIds,
  List<PollOptionView> options
) {
}
