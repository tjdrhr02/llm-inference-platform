package inference.config;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ConcurrencyConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public InferenceConcurrencyProperties inferenceConcurrencyProperties(
      @Value("${inference.concurrency.maxConcurrent:8}") int maxConcurrent,
      @Value("${inference.concurrency.workerThreads:8}") int workerThreads,
      @Value("${inference.concurrency.queueCapacity:200}") int queueCapacity,
      @Value("${inference.concurrency.acquireTimeoutMs:50}") long acquireTimeoutMs
  ) {
    return new InferenceConcurrencyProperties(
        maxConcurrent,
        workerThreads,
        queueCapacity,
        acquireTimeoutMs
    );
  }

  @Bean
  public InferenceProcessingProperties inferenceProcessingProperties(
      @Value("${inference.processing.timeoutMs:1500}") long timeoutMs,
      @Value("${inference.processing.simulatedMinMs:80}") int simulatedMinMs,
      @Value("${inference.processing.simulatedMaxMs:2200}") int simulatedMaxMs
  ) {
    return new InferenceProcessingProperties(timeoutMs, simulatedMinMs, simulatedMaxMs);
  }

  @Bean
  public Semaphore inferenceSemaphore(InferenceConcurrencyProperties props) {
    // fair=true: 대기열 기반으로 공정하게 permit 분배 (폭주 시 starvation 완화)
    return new Semaphore(props.maxConcurrent(), true);
  }

  @Bean(name = "inferenceExecutor")
  public ThreadPoolTaskExecutor inferenceExecutor(InferenceConcurrencyProperties props) {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setThreadNamePrefix("inference-");
    exec.setCorePoolSize(props.workerThreads());
    exec.setMaxPoolSize(props.workerThreads());
    exec.setQueueCapacity(props.queueCapacity());
    exec.setTaskDecorator(mdcTaskDecorator());
    // 큐가 꽉 차면 즉시 거절 -> 비동기 API에서 호출자 스레드가 막히지 않게 함
    exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    exec.initialize();
    return exec;
  }

  @Bean
  public TaskDecorator mdcTaskDecorator() {
    return runnable -> {
      var captured = MDC.getCopyOfContextMap();
      return () -> {
        var previous = MDC.getCopyOfContextMap();
        try {
          if (captured != null) {
            MDC.setContextMap(captured);
          } else {
            MDC.clear();
          }
          runnable.run();
        } finally {
          if (previous != null) {
            MDC.setContextMap(previous);
          } else {
            MDC.clear();
          }
        }
      };
    };
  }

  public record InferenceConcurrencyProperties(
      int maxConcurrent,
      int workerThreads,
      int queueCapacity,
      long acquireTimeoutMs
  ) {}

  public record InferenceProcessingProperties(
      long timeoutMs,
      int simulatedMinMs,
      int simulatedMaxMs
  ) {}
}

