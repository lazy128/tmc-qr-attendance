package tmc.access.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> status(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode()).body(
        new ApiError("REQUEST_REJECTED", error.getReason(), UUID.randomUUID().toString(), false)
    );
  }

  @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ApiError> invalidInput() {
    return ResponseEntity.badRequest().body(
        new ApiError("INVALID_REQUEST", "Invalid request headers or body", UUID.randomUUID().toString(), false)
    );
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", safe(error), UUID.randomUUID().toString(), false));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> conflict(IllegalStateException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("INVALID_STATE", safe(error), UUID.randomUUID().toString(), false));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> failure(Exception error) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "Request could not be completed", UUID.randomUUID().toString(), true));
  }

  private String safe(Exception error) {
    return error.getMessage() == null ? "Request rejected" : error.getMessage();
  }
}
