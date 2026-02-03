# llm-inference-platform

Kubernetes 운영 관점(동시성 제어, 요청 추적, 확장성)에 초점을 둔 **최소 Spring Boot 기반 LLM inference 플랫폼 스켈레톤**입니다.  
모델 정확도/품질은 다루지 않고, **요청 큐잉/제한/추적**을 우선합니다.

## 구조

요청하신 패키지 구조를 유지합니다:

```
llm-inference-platform
 └─ src/main/java
    └─ inference
       ├─ controller
       │   └─ InferenceController.java
       ├─ service
       │   └─ InferenceService.java
       ├─ model
       │   └─ InferenceRequest.java
       ├─ model
       │   └─ InferenceResponse.java
       └─ config
           └─ ConcurrencyConfig.java
```

추가로 Spring Boot 구동을 위한 `inference/InferenceApplication.java`, 설정 파일 `src/main/resources/application.yml`, `pom.xml`이 포함됩니다.

## API

- `POST /v1/inference`
  - 비동기 제출 (202 Accepted)
  - 응답 헤더 `X-Request-Id`, `Location: /v1/inference/{requestId}`
- `GET /v1/inference/{requestId}`
  - 상태/결과 조회 (200/404)

## 아키텍처(요약)

이 서버는 “HTTP 접수 레이어”와 “비동기 실행/동시성 제어 레이어”를 분리한 **Job API** 형태입니다.

- **Controller (`InferenceController`)**:
  - `requestId` 생성/정규화(`X-Request-Id` 또는 `clientRequestId` → 없으면 UUID)
  - `POST`는 즉시 `202`(또는 큐 포화 시 `429`)로 반환
  - `GET`으로 requestId 상태/결과 조회
- **Service (`InferenceService`)**:
  - bounded executor로 비동기 실행(유한 큐)
  - `Semaphore`로 동시 실행 수 제한
  - 시뮬레이션 지연 + 처리 timeout 적용
  - 상태/결과를 `store(requestId → InferenceResponse)`에 기록(현재 메모리, 운영에서는 Redis/DB로 교체 권장)

## Logging (운영 로그)

- **requestId 출력**: `logback-spring.xml`에서 MDC의 `requestId`(`%X{requestId}`)를 로그 패턴에 포함합니다.
- **structured/event 로그**: 주요 이벤트는 `event=... key=value` 형태로 출력합니다.
- **result 필드**: 완료 로그에서 `result=SUCCESS|TIMEOUT|REJECTED|FAILED` 형태로 구분되도록 설계했습니다.

예시(형태):

- `event=inference.submit_accepted requestId=... status=QUEUED ...`
- `event=inference.completed requestId=... status=SUCCEEDED result=SUCCESS latencyMs=...`
- `event=inference.completed requestId=... status=FAILED result=TIMEOUT reason=timeout ...`
- `event=inference.rejected requestId=... status=REJECTED result=REJECTED reason=concurrency_limit_reached ...`

## timeout vs rejected 차이

- **REJECTED**: “처리를 시작하기 전에” 용량/정책 때문에 거절된 경우
  - 예: executor 큐가 가득 참(`error=queue_full`) → `POST`에서 즉시 `429`
  - 예: 동시 실행 permit을 제때 못 얻음(`error=concurrency_limit_reached`) → 작업이 시작 전에 거절(클라이언트는 이후 `GET`에서 상태 확인)
- **TIMEOUT(=FAILED + error=timeout)**: “처리를 시작했지만” 지정된 처리 시간(`inference.processing.timeoutMs`) 내에 끝나지 못한 경우

## 동시 요청 수 초과 시 무슨 일이 일어나나?

초과 상황은 2단계에서 발생합니다.

- **1) 큐 포화(즉시 거절)**:
  - `ThreadPoolTaskExecutor`의 유한 큐가 가득 차면 제출이 거절됩니다.
  - 서버 동작: `status=REJECTED`, `error=queue_full`, `HTTP 429`
  - 운영 의미: “지금은 더 못 받음”을 빠르게 신호 → 클라이언트 백오프/재시도 유도

- **2) 동시 실행 제한 초과(permit 획득 실패)**:
  - 큐에는 들어갔지만, 실행 직전에 `Semaphore` permit을 `acquireTimeoutMs` 안에 못 얻으면 거절됩니다.
  - 서버 동작: `status=REJECTED`, `error=concurrency_limit_reached`
  - 클라이언트 관점: `POST`는 202로 받았더라도, 이후 `GET` 결과가 REJECTED일 수 있음

## 실패/장애 시나리오(클라이언트/운영 관점)

- **잘못된 요청(JSON 파싱 실패)**:
  - 응답: `400` (ProblemDetail, `title=malformed_json`, `requestId` 포함)
  - 로그: `event=api.bad_request type=malformed_json`

- **요청 검증 실패(prompt 누락 등)**:
  - 응답: `400` (ProblemDetail, `title=validation_failed`, `fields` 포함)
  - 로그: `event=api.bad_request type=validation_failed`

- **트래픽 급증으로 큐 포화**:
  - 응답: `429` + `status=REJECTED` + `error=queue_full`
  - 로그: `event=inference.submit_rejected ... reason=queue_full`
  - 권장 클라이언트 동작: 지수 백오프 후 재시도

- **동시 실행 수 초과**:
  - 응답(POST): `202`(접수)일 수 있으나, 이후 결과가 `REJECTED(concurrency_limit_reached)`로 바뀔 수 있음
  - 로그: `event=inference.rejected ... reason=concurrency_limit_reached`
  - 권장 클라이언트 동작: 재시도/백오프(또는 모델별/테넌트별 큐 분리 설계)

- **처리 타임아웃**:
  - 결과: `status=FAILED`, `error=timeout`
  - 로그: `event=inference.completed ... result=TIMEOUT reason=timeout`
  - 권장 대응: timeoutMs 조정, 워커/리소스 확장, 추론 경량화(또는 큐 기반 워커 분리)

- **Pod 재시작/스케일아웃으로 store 유실(현재 메모리 store의 한계)**:
  - 증상: 기존 requestId 조회 시 `404`가 날 수 있음
  - 대응: 상태 저장소를 Redis/DB로 교체 + TTL 적용

## Health check (K8s liveness/readiness)

Spring Boot Actuator 기반으로 다음 엔드포인트를 제공합니다:

- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness`

Kubernetes 예시:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
```

## 실행

```bash
mvn spring-boot:run
```

예시 호출:

```bash
curl -i -X POST "http://localhost:8080/v1/inference" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: demo-1" \
  -d '{"prompt":"hello k8s","model":"dummy"}'
```

상태 조회:

```bash
curl -s "http://localhost:8080/v1/inference/demo-1" | jq .
```

