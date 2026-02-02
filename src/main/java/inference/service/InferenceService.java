package inference.service;

import inference.config.ConcurrencyConfig.InferenceConcurrencyProperties;
import inference.model.InferenceRequest;
import inference.model.InferenceResponse;
import inference.model.InferenceResponse.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class InferenceService {
  private final Clock clock;
  private final Semaphore semaphore;
  private final ThreadPoolTaskExecutor executor;
  private final InferenceConcurrencyProperties props;

  /**
   * 최소 구현: 메모리 기반 상태 저장소.
   * - K8s에서 scale-out 시에는 Redis/DB 등 외부 저장소로 교체 권장.
   */
  private final ConcurrentMap<String, InferenceResponse> store = new ConcurrentHashMap<>();

  public InferenceService(
      Clock clock,
      Semaphore inferenceSemaphore,
      ThreadPoolTaskExecutor inferenceExecutor,
      InferenceConcurrencyProperties props
  ) {
    this.clock = clock;
    this.semaphore = inferenceSemaphore;
    this.executor = inferenceExecutor;
    this.props = props;
  }

  public InferenceResponse submit(InferenceRequest request, String requestIdHint) {
    Instant receivedAt = Instant.now(clock);
    String requestId = normalizeOrGenerateRequestId(requestIdHint, request.getClientRequestId());

    InferenceResponse initial = InferenceResponse.queued(requestId, receivedAt);
    store.put(requestId, initial);

    executor.execute(() -> runInference(requestId, request));
    return initial;
  }

  public Optional<InferenceResponse> get(String requestId) {
    return Optional.ofNullable(store.get(requestId));
  }

  private void runInference(String requestId, InferenceRequest request) {
    InferenceResponse state = store.get(requestId);
    if (state == null) {
      return;
    }

    boolean acquired = false;
    Instant startedAt = null;
    try {
      acquired = semaphore.tryAcquire(props.acquireTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        state.setStatus(Status.REJECTED);
        state.setCompletedAt(Instant.now(clock));
        state.setLatencyMs(Duration.between(state.getReceivedAt(), state.getCompletedAt()).toMillis());
        state.setError("concurrency_limit_reached");
        store.put(requestId, state);
        return;
      }

      startedAt = Instant.now(clock);
      state.setStatus(Status.RUNNING);
      state.setStartedAt(startedAt);
      store.put(requestId, state);

      // 실제 LLM 호출 대신, 최소한의 더미 작업(운영 관점: 큐/동시성/추적이 핵심)
      // 여기서는 prompt 길이에 비례해 약간의 시간이 걸리는 것처럼 시뮬레이션.
      int sleepMs = Math.min(250, Math.max(10, request.getPrompt().length() / 50));
      Thread.sleep(sleepMs);

      String output = "ok: " + summarize(request.getPrompt());
      Instant completedAt = Instant.now(clock);

      state.setStatus(Status.SUCCEEDED);
      state.setOutput(output);
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.put(requestId, state);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      Instant completedAt = Instant.now(clock);
      state.setStatus(Status.FAILED);
      state.setError("interrupted");
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.put(requestId, state);
    } catch (Exception e) {
      Instant completedAt = Instant.now(clock);
      state.setStatus(Status.FAILED);
      state.setError("error: " + e.getClass().getSimpleName());
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.put(requestId, state);
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  private static String summarize(String prompt) {
    String p = prompt == null ? "" : prompt.trim();
    if (p.length() <= 80) return p;
    return p.substring(0, 77) + "...";
  }

  private static String normalizeOrGenerateRequestId(String requestIdHint, String clientRequestId) {
    String candidate = firstNonBlank(requestIdHint, clientRequestId);
    if (candidate != null) {
      // 지나치게 긴 값은 로깅/저장 비용을 키우므로 제한
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

