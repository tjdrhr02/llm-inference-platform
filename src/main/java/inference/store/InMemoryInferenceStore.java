package inference.store;

import inference.model.InferenceResponse;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 단일 인스턴스 / 로컬 개발용 인메모리 구현.
 *
 * <p>
 * - Pod 재시작/스케일아웃 시 데이터는 유실된다.
 * - 실제 운영 시에는 Redis/DB 기반 구현으로 교체해야 한다.
 * </p>
 */
@Component
public class InMemoryInferenceStore implements InferenceStore {

  private final ConcurrentMap<String, InferenceResponse> store = new ConcurrentHashMap<>();

  @Override
  public void save(InferenceResponse response) {
    if (response == null || response.getRequestId() == null) {
      throw new IllegalArgumentException("response and requestId must not be null");
    }
    store.put(response.getRequestId(), response);
  }

  @Override
  public Optional<InferenceResponse> find(String requestId) {
    if (requestId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(store.get(requestId));
  }
}

