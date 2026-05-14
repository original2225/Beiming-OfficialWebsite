package dev.beiming.community;

import java.util.List;

public record CreatePollRequest(
  String question,
  String voteMode,
  String resultVisibility,
  Long closesAt,
  List<PollOptionRequest> options
) {
}
