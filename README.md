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

