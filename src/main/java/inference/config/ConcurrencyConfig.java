package inference.config;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    // 큐가 꽉 차면 호출자 스레드에서 실행 -> 백프레셔(느리게 만들기) 역할
    exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    exec.initialize();
    return exec;
  }

  public record InferenceConcurrencyProperties(
      int maxConcurrent,
      int workerThreads,
      int queueCapacity,
      long acquireTimeoutMs
  ) {}
}

