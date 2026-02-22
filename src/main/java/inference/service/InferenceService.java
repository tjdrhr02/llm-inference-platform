package inference.service;

import inference.config.ConcurrencyConfig.InferenceConcurrencyProperties;
import inference.config.ConcurrencyConfig.InferenceProcessingProperties;
import inference.model.InferenceRequest;
import inference.model.InferenceResponse;
import inference.model.InferenceResponse.Status;
import inference.store.InferenceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class InferenceService {
  private static final Logger log = LoggerFactory.getLogger(InferenceService.class);
  private static volatile long CPU_SINK = 0L;

  private final Clock clock;
  private final Semaphore semaphore;
  private final ThreadPoolTaskExecutor executor;
  private final InferenceConcurrencyProperties props;
  private final InferenceProcessingProperties processing;
  private final InferenceStore store;

  public InferenceService(
      Clock clock,
      Semaphore inferenceSemaphore,
      ThreadPoolTaskExecutor inferenceExecutor,
      InferenceConcurrencyProperties props,
      InferenceProcessingProperties processing,
      InferenceStore store
  ) {
    this.clock = clock;
    this.semaphore = inferenceSemaphore;
    this.executor = inferenceExecutor;
    this.props = props;
    this.processing = processing;
    this.store = store;
  }

  public InferenceResponse submit(String requestId, InferenceRequest request) {
    Instant receivedAt = Instant.now(clock);

    InferenceResponse initial = InferenceResponse.queued(requestId, receivedAt);
    store.save(initial);

    try {
      executor.execute(() -> {
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
          runInference(requestId, request);
        }
      });
      log.info("event=inference.submit_enqueued requestId={} status={} model={} promptChars={}",
          requestId,
          Status.QUEUED,
          request.getModel(),
          request.getPrompt() == null ? 0 : request.getPrompt().length());
      return initial;
    } catch (RejectedExecutionException ree) {
      // 큐가 꽉 찼을 때: 즉시 거절 (클라이언트는 백오프 후 재시도)
      Instant completedAt = Instant.now(clock);
      initial.setStatus(Status.REJECTED);
      initial.setError("queue_full");
      initial.setCompletedAt(completedAt);
      initial.setLatencyMs(Duration.between(initial.getReceivedAt(), completedAt).toMillis());
      store.save(initial);
      log.warn("event=inference.submit_rejected requestId={} status={} result={} reason=queue_full queueCapacity={} latencyMs={}",
          requestId, Status.REJECTED, "REJECTED", props.queueCapacity(), initial.getLatencyMs());
      return initial;
    }
  }

  public Optional<InferenceResponse> get(String requestId) {
    return store.find(requestId);
  }

  private void runInference(String requestId, InferenceRequest request) {
    InferenceResponse state = store.find(requestId).orElse(null);
    if (state == null) {
      return;
    }

    boolean acquired = false;
    try {
      acquired = semaphore.tryAcquire(props.acquireTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        state.setStatus(Status.REJECTED);
        state.setCompletedAt(Instant.now(clock));
        state.setLatencyMs(Duration.between(state.getReceivedAt(), state.getCompletedAt()).toMillis());
        state.setError("concurrency_limit_reached");
        store.save(state);
        log.warn("event=inference.rejected requestId={} status={} result={} reason=concurrency_limit_reached acquireTimeoutMs={} latencyMs={}",
            requestId, Status.REJECTED, "REJECTED", props.acquireTimeoutMs(), state.getLatencyMs());
        return;
      }

      Instant startedAt = Instant.now(clock);
      state.setStatus(Status.RUNNING);
      state.setStartedAt(startedAt);
      store.save(state);

      int plannedMs = computeSimulatedLatencyMs(request);
      log.info("event=inference.started requestId={} status={} plannedLatencyMs={} timeoutMs={} model={} promptChars={}",
          requestId,
          Status.RUNNING,
          plannedMs,
          processing.timeoutMs(),
          request.getModel(),
          request.getPrompt() == null ? 0 : request.getPrompt().length());

      simulateWorkWithTimeout(plannedMs, processing.timeoutMs());

      String output = "ok: " + summarize(request.getPrompt());
      Instant completedAt = Instant.now(clock);

      state.setStatus(Status.SUCCEEDED);
      state.setOutput(output);
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.save(state);
      log.info("event=inference.completed requestId={} status={} result={} latencyMs={}",
          requestId, Status.SUCCEEDED, "SUCCESS", state.getLatencyMs());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      Instant completedAt = Instant.now(clock);
      state.setStatus(Status.FAILED);
      state.setError("interrupted");
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.save(state);
      log.warn("event=inference.completed requestId={} status={} result={} reason=interrupted latencyMs={}",
          requestId, Status.FAILED, "FAILED", state.getLatencyMs());
    } catch (TimeoutException te) {
      Instant completedAt = Instant.now(clock);
      state.setStatus(Status.FAILED);
      state.setError("timeout");
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.save(state);
      log.warn("event=inference.completed requestId={} status={} result={} reason=timeout timeoutMs={} latencyMs={}",
          requestId, Status.FAILED, "TIMEOUT", processing.timeoutMs(), state.getLatencyMs());
    } catch (Exception e) {
      Instant completedAt = Instant.now(clock);
      state.setStatus(Status.FAILED);
      state.setError("error: " + e.getClass().getSimpleName());
      state.setCompletedAt(completedAt);
      state.setLatencyMs(Duration.between(state.getReceivedAt(), completedAt).toMillis());
      store.save(state);
      log.error("event=inference.completed requestId={} status={} result={} reason=exception exceptionType={} latencyMs={}",
          requestId, Status.FAILED, "FAILED", e.getClass().getName(), state.getLatencyMs(), e);
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  private int computeSimulatedLatencyMs(InferenceRequest request) {
    int promptChars = request.getPrompt() == null ? 0 : request.getPrompt().length();
    // 운영적으로 “길이가 길수록 느려지는” 형태를 모사 + 약간의 지터
    int base = processing.simulatedMinMs() + (promptChars / 25);
    int clamped = Math.min(processing.simulatedMaxMs(), Math.max(processing.simulatedMinMs(), base));
    int jitter = (int) Math.round(clamped * 0.20); // +-20%
    int lo = Math.max(processing.simulatedMinMs(), clamped - jitter);
    int hi = Math.min(processing.simulatedMaxMs(), clamped + jitter);
    return ThreadLocalRandom.current().nextInt(lo, hi + 1);
  }

  private void simulateWorkWithTimeout(int plannedMs, long timeoutMs) throws InterruptedException, TimeoutException {
    Instant deadline = Instant.now(clock).plusMillis(timeoutMs);
    int remaining = plannedMs;
    while (remaining > 0) {
      if (Instant.now(clock).isAfter(deadline)) {
        throw new TimeoutException();
      }
      int chunk = Math.min(
          Math.max(1, processing.cpuBurnChunkMs()),
          remaining
      );
      if (processing.cpuBurnEnabled()) {
        burnCpuMs(chunk);
      } else {
        Thread.sleep(chunk);
      }
      remaining -= chunk;
    }
  }

  private static void burnCpuMs(int ms) {
    long end = System.nanoTime() + (ms * 1_000_000L);
    long x = 0L;
    while (System.nanoTime() < end) {
      x ^= System.nanoTime();
    }
    CPU_SINK = x;
  }

  private static String summarize(String prompt) {
    String p = prompt == null ? "" : prompt.trim();
    if (p.length() <= 80) return p;
    return p.substring(0, 77) + "...";
  }

  private static final class TimeoutException extends Exception {
    private TimeoutException() {}
  }
}

