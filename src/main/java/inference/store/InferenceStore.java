package inference.store;

import inference.model.InferenceResponse;
import java.util.Optional;

/**
 * 인퍼런스 요청/응답 상태를 저장하는 추상 저장소.
 *
 * <p>
 * - 현재 구현은 인메모리 기반이지만, K8s에서 실제 운영 시에는
 *   Redis/DB 등 외부 저장소 구현으로 교체하는 것이 목표이다.
 * - key는 {@link InferenceResponse#getRequestId()} 를 사용한다.
 * </p>
 */
public interface InferenceStore {

  /**
   * 상태를 저장하거나 갱신한다 (upsert).
   */
  void save(InferenceResponse response);

  /**
   * requestId로 상태를 조회한다.
   */
  Optional<InferenceResponse> find(String requestId);
}

