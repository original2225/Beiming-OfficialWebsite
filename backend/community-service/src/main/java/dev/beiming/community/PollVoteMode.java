package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum PollVoteMode {
  SINGLE,
  MULTIPLE;

  static PollVoteMode parse(String value) {
    try {
      return value == null || value.isBlank() ? SINGLE : PollVoteMode.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "投票模式不正确");
    }
  }
}
