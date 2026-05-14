package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum ReportStatus {
  OPEN,
  RESOLVED,
  REJECTED;

  static ReportStatus parse(String value) {
    try {
      return value == null || value.isBlank() ? OPEN : ReportStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "举报处理状态不正确");
    }
  }
}
