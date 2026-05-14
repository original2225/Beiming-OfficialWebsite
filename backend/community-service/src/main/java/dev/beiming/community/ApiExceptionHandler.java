package dev.beiming.community;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiEnvelope<Void>> handleApiException(ApiException error) {
    return ResponseEntity.status(error.status()).body(ApiEnvelope.error(error.getMessage()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiEnvelope<Void>> handleNoResource(NoResourceFoundException error) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiEnvelope.error("Not found"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiEnvelope<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException error) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiEnvelope.error(error.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiEnvelope<Void>> handleException(Exception error) {
    return ResponseEntity.internalServerError().body(ApiEnvelope.error(error.getMessage()));
  }
}
