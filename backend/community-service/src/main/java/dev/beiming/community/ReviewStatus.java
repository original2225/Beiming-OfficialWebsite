package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum ReviewStatus {
  APPROVED,
  PENDING_REVIEW,
  REJECTED;

  static ReviewStatus parse(String value) {
    try {
      return value == null || value.isBlank() ? APPROVED : ReviewStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "审核状态不正确");
    }
  }
}
