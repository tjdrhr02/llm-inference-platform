package inference.controller;

import inference.model.InferenceRequest;
import inference.model.InferenceResponse;
import inference.model.InferenceResponse.Status;
import inference.service.InferenceService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inference")
public class InferenceController {
  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

  private final InferenceService inferenceService;

  public InferenceController(InferenceService inferenceService) {
    this.inferenceService = inferenceService;
  }

  /**
   * 비동기 제출:
   * - 202 Accepted 반환 (QUEUED)
   * - Location: /v1/inference/{requestId}
   * - X-Request-Id 헤더로 추적 가능
   */
  @PostMapping
  public ResponseEntity<InferenceResponse> submit(
      @Valid @RequestBody InferenceRequest request,
      @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId
  ) {
    String rid = normalizeOrGenerateRequestId(requestId, request.getClientRequestId());
    try (var ignored = MDC.putCloseable("requestId", rid)) {
      InferenceResponse queued = inferenceService.submit(rid, request);

      HttpHeaders headers = new HttpHeaders();
      headers.set(REQUEST_ID_HEADER, queued.getRequestId());
      headers.setLocation(URI.create("/v1/inference/" + queued.getRequestId()));

      if (queued.getStatus() == Status.REJECTED) {
        // 큐 포화 등으로 접수 자체가 거절된 경우 (클라이언트는 백오프 후 재시도)
        log.warn("event=inference.submit_rejected requestId={} status={} result={} reason={}",
            rid, queued.getStatus(), "REJECTED", queued.getError());
        return new ResponseEntity<>(queued, headers, HttpStatus.TOO_MANY_REQUESTS);
      }

      log.info("event=inference.submit_accepted requestId={} status={} model={} promptChars={}",
          rid, queued.getStatus(), request.getModel(), request.getPrompt() == null ? 0 : request.getPrompt().length());
      return new ResponseEntity<>(queued, headers, HttpStatus.ACCEPTED);
    }
  }

  /**
   * 상태/결과 조회:
   * - 200: 존재함
   * - 404: 모름(만료/스케일아웃/서버 재시작 등)
   */
  @GetMapping("/{requestId}")
  public ResponseEntity<InferenceResponse> get(@PathVariable String requestId) {
    try (var ignored = MDC.putCloseable("requestId", requestId)) {
      Optional<InferenceResponse> r = inferenceService.get(requestId);
      if (r.isEmpty()) {
        log.info("event=inference.get_not_found requestId={}", requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      log.info("event=inference.get requestId={} status={} latencyMs={}",
          requestId, r.get().getStatus(), r.get().getLatencyMs());
      return ResponseEntity.ok()
          .header(REQUEST_ID_HEADER, r.get().getRequestId())
          .body(r.get());
    }
  }

  private static String normalizeOrGenerateRequestId(String headerRequestId, String clientRequestId) {
    String candidate = firstNonBlank(headerRequestId, clientRequestId);
    if (candidate != null) {
      return candidate.length() <= 128 ? candidate : candidate.substring(0, 128);
    }
    return UUID.randomUUID().toString();
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return null;
  }
}

