package inference.controller;

import inference.model.InferenceRequest;
import inference.model.InferenceResponse;
import inference.service.InferenceService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
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
    InferenceResponse queued = inferenceService.submit(request, requestId);

    HttpHeaders headers = new HttpHeaders();
    headers.set(REQUEST_ID_HEADER, queued.getRequestId());
    headers.setLocation(URI.create("/v1/inference/" + queued.getRequestId()));

    return new ResponseEntity<>(queued, headers, HttpStatus.ACCEPTED);
  }

  /**
   * 상태/결과 조회:
   * - 200: 존재함
   * - 404: 모름(만료/스케일아웃/서버 재시작 등)
   */
  @GetMapping("/{requestId}")
  public ResponseEntity<InferenceResponse> get(@PathVariable String requestId) {
    Optional<InferenceResponse> r = inferenceService.get(requestId);
    if (r.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    return ResponseEntity.ok()
        .header(REQUEST_ID_HEADER, r.get().getRequestId())
        .body(r.get());
  }
}

