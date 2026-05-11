package dev.beiming.api;

public record ApiEnvelope<T>(boolean ok, T data, String message) {
  public static <T> ApiEnvelope<T> ok(T data) {
    return new ApiEnvelope<>(true, data, null);
  }

  public static ApiEnvelope<Void> error(String message) {
    return new ApiEnvelope<>(false, null, message);
  }
}
