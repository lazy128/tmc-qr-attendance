package tmc.access.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import tmc.shared.web.CorrelationIdFilter;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<ApiError> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        new ApiError("NOT_FOUND", "Resource not found", CorrelationIdFilter.currentId(), false)
    );
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> status(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode()).body(
        new ApiError("REQUEST_REJECTED", error.getReason(), CorrelationIdFilter.currentId(), false)
    );
  }

  @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ApiError> invalidInput() {
    return ResponseEntity.badRequest().body(
        new ApiError("INVALID_REQUEST", "Invalid request headers or body", CorrelationIdFilter.currentId(), false)
    );
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", safe(error), CorrelationIdFilter.currentId(), false));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> conflict(IllegalStateException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("INVALID_STATE", safe(error), CorrelationIdFilter.currentId(), false));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> failure(Exception error) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "Request could not be completed", CorrelationIdFilter.currentId(), true));
  }

  private String safe(Exception error) {
    return error.getMessage() == null ? "Request rejected" : error.getMessage();
  }
}
