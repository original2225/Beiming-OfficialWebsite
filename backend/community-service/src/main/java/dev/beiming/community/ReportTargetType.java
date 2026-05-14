package dev.beiming.community;

import org.springframework.http.HttpStatus;

public enum ReportTargetType {
  POST,
  COMMENT;

  static ReportTargetType parse(String value) {
    try {
      return ReportTargetType.valueOf(value.trim().toUpperCase());
    } catch (Exception error) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "举报目标不正确");
    }
  }
}
