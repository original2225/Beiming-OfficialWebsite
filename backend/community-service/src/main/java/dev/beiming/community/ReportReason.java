package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum ReportReason {
  SPAM,
  ABUSE,
  OFF_TOPIC,
  COPYRIGHT,
  OTHER;

  static ReportReason parse(String value) {
    try {
      return value == null || value.isBlank() ? OTHER : ReportReason.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "举报原因不正确");
    }
  }
}
