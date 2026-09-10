package com.why.explainer.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global error handling: every error leaves the API as clean JSON
 * ({@code {"error": "...", "message": "..."}}) — never the default
 * Whitelabel error page.
 *
 * <p>Unmapped routes reach {@link #notFound} because the app sets
 * {@code spring.mvc.throw-exception-if-no-handler-found=true} and
 * disables static resource mappings.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** JSON error envelope shared by every handler below. */
  public record ErrorResponse(String error, String message) {
  }

  private static ResponseEntity<ErrorResponse> body(
      HttpStatus status, String error, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(error, message));
  }

  /** 404 — no controller or resource matches the path. */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ErrorResponse> notFound(Exception ex, HttpServletRequest request) {
    return body(HttpStatus.NOT_FOUND, "not_found",
        "no route for " + request.getMethod() + " " + request.getRequestURI() + ".");
  }

  /** 400 — malformed JSON, failed validation, bad params. */
  @ExceptionHandler({
      HttpMessageNotReadableException.class,
      MethodArgumentNotValidException.class,
      MissingServletRequestParameterException.class,
      MethodArgumentTypeMismatchException.class,
      ConstraintViolationException.class
  })
  public ResponseEntity<ErrorResponse> badRequest(Exception ex) {
    String message = "bad request.";
    if (ex instanceof MethodArgumentNotValidException manv) {
      String details = manv.getBindingResult().getFieldErrors().stream()
          .map(FieldError::getDefaultMessage)
          .collect(Collectors.joining(" "));
      if (!details.isBlank()) {
        message = details;
      }
    } else if (ex instanceof HttpMessageNotReadableException) {
      message = "malformed JSON request body.";
    } else if (ex instanceof MissingServletRequestParameterException missing) {
      message = "missing required parameter: " + missing.getParameterName() + ".";
    } else if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
      message = "invalid value for: " + mismatch.getName() + ".";
    } else if (ex instanceof ConstraintViolationException cve) {
      String details = cve.getConstraintViolations().stream()
          .map(v -> v.getMessage())
          .collect(Collectors.joining(" "));
      if (!details.isBlank()) {
        message = details;
      }
    }
    return body(HttpStatus.BAD_REQUEST, "bad_request", message);
  }

  /**
   * Errors thrown deliberately with a status (e.g. ExplainController's
   * 400s). The original reason is preserved as the message; only the
   * envelope is new.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> responseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    String message = ex.getReason() != null ? ex.getReason()
        : status.getReasonPhrase().toLowerCase() + ".";
    return body(status, toErrorCode(status), message);
  }

  /** 500 — anything unexpected. No internals leak into the message. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
        "something went wrong on our side.");
  }

  private static String toErrorCode(HttpStatus status) {
    if (status == HttpStatus.NOT_FOUND) {
      return "not_found";
    }
    if (status == HttpStatus.TOO_MANY_REQUESTS) {
      return "rate_limited";
    }
    if (status == HttpStatus.UNAUTHORIZED) {
      return "unauthorized";
    }
    if (status == HttpStatus.FORBIDDEN) {
      return "forbidden";
    }
    if (status.is4xxClientError()) {
      return "bad_request";
    }
    return "internal_error";
  }
}
