## Day 8: k8s 배포 + HPA(CPU) 테스트

### 0) 전제

- **metrics-server 설치 필요**: HPA(CPU)는 클러스터에 metrics-server가 있어야 동작합니다.
  - 확인: `kubectl get apiservices | grep metrics`
- 이 프로젝트 Deployment 기본 이미지는 `llm-inference-platform:local` 입니다.
  - 로컬 클러스터(kind/minikube) 기준으로 바로 테스트하기 좋게 잡아둔 값이며,
  - 실제 레지스트리를 쓰면 Deployment의 `image:`만 원하는 값으로 바꿔주면 됩니다.

### 1) 이미지 빌드

```bash
docker build -t llm-inference-platform:local .
```

#### kind 사용 시(로컬 이미지 로드)

```bash
kind load docker-image llm-inference-platform:local
```

#### minikube 사용 시(로컬 이미지 로드)

```bash
minikube image load llm-inference-platform:local
```

### 2) 배포

```bash
kubectl apply -k k8s/base
kubectl rollout status deploy/inference-api
kubectl get deploy,svc,hpa
```

### 3) 동작 확인(포트포워딩)

```bash
kubectl port-forward svc/inference-api 8080:80
curl -s http://localhost:8080/actuator/health/readiness
```

### 4) 일부러 해야 할 테스트

#### A. Pod 죽이기(복구 확인)

```bash
kubectl get pods -l app=inference-api
kubectl delete pod -l app=inference-api
kubectl get pods -l app=inference-api -w
```

#### B. HPA scale-out 준비: CPU 부하 옵션 ON

현재 `/v1/inference`는 기본적으로 `sleep` 기반 지연이라 **트래픽만으로 CPU가 크게 오르지 않을 수 있습니다**.  
CPU 기준 HPA를 확실히 검증하기 위해 테스트용 옵션을 켤 수 있습니다.

```bash
kubectl set env deploy/inference-api INFERENCE_PROCESSING_CPUBURN_ENABLED=true
kubectl rollout status deploy/inference-api
```

#### C. 트래픽 몰아넣기(클러스터 내부에서)

아래는 클러스터 내부에서 Service를 향해 POST를 계속 쏘는 가장 단순한 방법입니다.

```bash
kubectl run -i --tty --rm loadgen \
  --image=curlimages/curl:8.6.0 \
  --restart=Never -- \
  sh -lc 'while true; do curl -s -o /dev/null -X POST http://inference-api/v1/inference -H "Content-Type: application/json" -d "{\"prompt\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"model\":\"dummy\"}"; done'
```

#### D. scale-out 확인

```bash
kubectl get hpa inference-api -w
kubectl get pods -l app=inference-api -w
```

추가로 현재 CPU/메모리 관측:

```bash
kubectl top pods -l app=inference-api
```

#### E. 테스트 종료(옵션 원복)

```bash
kubectl set env deploy/inference-api INFERENCE_PROCESSING_CPUBURN_ENABLED-
kubectl rollout status deploy/inference-api
```

