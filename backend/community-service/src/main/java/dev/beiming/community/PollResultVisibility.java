package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum PollResultVisibility {
  AFTER_VOTE,
  AFTER_CLOSE,
  ALWAYS;

  static PollResultVisibility parse(String value) {
    try {
      return value == null || value.isBlank() ? AFTER_VOTE : PollResultVisibility.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "投票结果可见性不正确");
    }
  }
}
