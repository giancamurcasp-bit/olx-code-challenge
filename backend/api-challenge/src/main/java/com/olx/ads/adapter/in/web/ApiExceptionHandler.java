package com.olx.ads.adapter.in.web;

import com.olx.ads.application.AdNotFoundException;
import com.olx.ads.application.IdempotencyKeyReusedException;
import com.olx.ads.domain.InvalidAdException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps errors to RFC 9457 {@link ProblemDetail} responses. Validation errors carry an {@code
 * errors} list of {@code {field, message}}, whether they come from Bean Validation or from the
 * domain. Framework errors (malformed JSON, wrong path variable type) are handled by the base
 * class.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  record FieldError(String field, String message) {}

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getFieldErrors().stream()
            .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest().body(validationProblem("Invalid ad", errors));
  }

  @ExceptionHandler(InvalidAdException.class)
  ProblemDetail handleInvalidAd(InvalidAdException ex) {
    return validationProblem("Invalid ad", List.of(new FieldError(ex.getField(), ex.getMessage())));
  }

  @ExceptionHandler(InvalidParameterException.class)
  ProblemDetail handleInvalidParameter(InvalidParameterException ex) {
    return validationProblem(
        "Invalid query parameter", List.of(new FieldError(ex.getParameter(), ex.getMessage())));
  }

  @ExceptionHandler(AdNotFoundException.class)
  ProblemDetail handleNotFound(AdNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
  }

  private static ProblemDetail validationProblem(String detail, List<FieldError> errors) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setProperty("errors", errors);
    return problem;
  }
}
