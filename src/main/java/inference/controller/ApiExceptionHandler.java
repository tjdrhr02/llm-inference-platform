package inference.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * 운영 관점에서 "무슨 실패가 났는지"를 클라이언트/로그에서 즉시 파악하기 위한 예외 처리.
 * - 표준 포맷: RFC7807 Problem Details
 * - requestId를 항상 포함
 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setTitle("validation_failed");
    pd.setDetail("Request validation failed");
    pd.setProperty("requestId", MDC.get("requestId"));

    Map<String, String> fields = new LinkedHashMap<>();
    for (FieldError fe : e.getBindingResult().getFieldErrors()) {
      fields.put(fe.getField(), fe.getDefaultMessage());
    }
    pd.setProperty("fields", fields);

    log.info("event=api.bad_request type=validation_failed fields={}", fields.keySet());
    return pd;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleBadJson(HttpMessageNotReadableException e) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    pd.setTitle("malformed_json");
    pd.setDetail("Malformed JSON request body");
    pd.setProperty("requestId", MDC.get("requestId"));

    log.info("event=api.bad_request type=malformed_json");
    return pd;
  }

  @ExceptionHandler(ErrorResponseException.class)
  public ProblemDetail handleSpringErrorResponse(ErrorResponseException e, WebRequest request) {
    // Spring이 이미 ProblemDetail을 만들어주는 경우가 많아서 그대로 보강해서 반환
    ProblemDetail pd = e.getBody();
    if (pd == null) {
      pd = ProblemDetail.forStatus(e.getStatusCode());
      pd.setTitle("error");
      pd.setDetail(e.getMessage());
    }
    pd.setProperty("requestId", MDC.get("requestId"));

    HttpStatus status = HttpStatus.resolve(pd.getStatus());
    int code = status == null ? pd.getStatus() : status.value();
    if (code >= 500) {
      log.error("event=api.error status={} title={}", code, pd.getTitle(), e);
    } else {
      log.info("event=api.client_error status={} title={}", code, pd.getTitle());
    }
    return pd;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    pd.setTitle("internal_error");
    pd.setDetail("Unexpected server error");
    pd.setProperty("requestId", MDC.get("requestId"));

    log.error("event=api.error status=500 title=internal_error", e);
    return pd;
  }
}

