# 로컬 Envoy Gateway + FluxGate 시연

이 예제는 Java 판정 서비스가 Redis의 **전역 버킷**(2회/60초)을 사용해 `/api` 경로를 보호한다. 클라이언트가 보낸 `X-Forwarded-For`, 사용자 ID, rule-set ID는 판정에 사용하지 않는다. 사용자별 할당량은 신뢰 가능한 프록시 체인과 인증된 ID 전파를 별도로 설계한 다음 추가해야 한다.

## 준비 및 배포

Docker Desktop 데몬을 켠 뒤 다음을 실행한다. 모든 Kubernetes 명령은 독립된 `kind-fluxgate-eg` 컨텍스트를 명시한다.

```bash
brew install kind
kind create cluster --name fluxgate-eg --image kindest/node:v1.35.0@sha256:452d707d4862f52530247495d180205e029056831160e22870e37e3f6c1ac31f --wait 5m
helm --kube-context kind-fluxgate-eg install eg oci://docker.io/envoyproxy/gateway-helm --version v1.9.2 -n envoy-gateway-system --create-namespace
kubectl --context kind-fluxgate-eg -n envoy-gateway-system wait deployment/envoy-gateway --for=condition=Available --timeout=180s

./mvnw -q -pl fluxgate-envoy-extauth -am package -DskipTests
docker build -f fluxgate-envoy-extauth/Dockerfile -t fluxgate-envoy-extauth:local .
kind load docker-image fluxgate-envoy-extauth:local --name fluxgate-eg
kubectl --context kind-fluxgate-eg apply -f deploy/envoy-gateway-local/pilot.yaml
kubectl --context kind-fluxgate-eg -n fluxgate-pilot rollout status deployment/redis --timeout=180s
kubectl --context kind-fluxgate-eg -n fluxgate-pilot rollout status deployment/fluxgate-authz --timeout=180s
kubectl --context kind-fluxgate-eg -n fluxgate-pilot rollout status deployment/echo --timeout=180s
kubectl --context kind-fluxgate-eg -n fluxgate-pilot get gateway,httproute,securitypolicy
```

kind의 기본 환경에서는 외부 LoadBalancer 주소가 배정되지 않아 Gateway 최상위 `Programmed=False`(`AddressNotAssigned`)일 수 있다. 이 경우에도 listener의 `Programmed=True`, HTTPRoute의 `Accepted=True`·`ResolvedRefs=True`, SecurityPolicy의 `Accepted=True`와 아래 포트포워드 응답을 함께 확인한다.

별도 터미널에서 Envoy Gateway가 생성한 서비스로 포트포워드한다.

```bash
ENVOY_SERVICE=$(kubectl --context kind-fluxgate-eg -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-namespace=fluxgate-pilot,gateway.envoyproxy.io/owning-gateway-name=fluxgate-gateway -o jsonpath='{.items[0].metadata.name}')
kubectl --context kind-fluxgate-eg -n envoy-gateway-system port-forward "service/${ENVOY_SERVICE}" 8888:80
```

다른 터미널에서 Redis의 새 버킷을 쓰도록 약 60초 기다리거나 로컬 실험 전용 Redis를 재시작한 뒤 테스트한다. 첫 두 요청은 `200`, 세 번째는 `429`와 `Retry-After`가 예상된다.

```bash
curl -i http://127.0.0.1:8888/api/items
curl -i http://127.0.0.1:8888/api/items
curl -i http://127.0.0.1:8888/api/items
```

Redis 장애 시 판정 서비스의 응답은 `503`이어야 한다. Pod가 완전히 사라지기 전에 요청하면 기존 연결로 `429`가 나올 수 있으므로 삭제 완료를 기다린다. `OPTIONS`도 같은 판정을 거친다. 실험이 끝나면 Redis를 복구한다.

```bash
kubectl --context kind-fluxgate-eg -n fluxgate-pilot scale deployment/redis --replicas=0
kubectl --context kind-fluxgate-eg -n fluxgate-pilot wait --for=delete pod -l app=redis --timeout=90s
curl -i -X OPTIONS http://127.0.0.1:8888/api/items # 503
curl -i http://127.0.0.1:8888/api/items            # 503
kubectl --context kind-fluxgate-eg -n fluxgate-pilot scale deployment/redis --replicas=1
kubectl --context kind-fluxgate-eg -n fluxgate-pilot rollout status deployment/redis --timeout=120s
```

판정 서비스를 `--replicas=0`으로 줄여 엔드포인트를 없앴을 때 이 조합의 실측 응답은 `500`이었다. `statusOnError: 503`을 설정해도 이 경우까지 503으로 고정되지는 않았다. 요청은 차단된다. 운영 정책에는 5xx 전체를 판정 계층 장애로 분류해야 한다.

ACL `403`은 판정 서비스에 포함된 로컬 전용 프로필을 켜서 확인한다. `kubectl --context kind-fluxgate-eg -n fluxgate-pilot set env deployment/fluxgate-authz SPRING_PROFILES_ACTIVE=acl-demo` 후 `rollout status`를 기다려 요청하면 전역 키가 거부된다. `kubectl --context kind-fluxgate-eg -n fluxgate-pilot set env deployment/fluxgate-authz SPRING_PROFILES_ACTIVE-`로 원래 설정을 복구한다.

이 샘플은 kind 기본 CNI에 NetworkPolicy 강제가 없으므로 echo 서비스에 대한 클러스터 내부 직접 접속까지 차단하지 않는다. 실제 배포에서는 NetworkPolicy를 집행하는 CNI에서 Gateway 프록시만 백엔드로 들어오게 제한하고, 서비스 간 통신·mTLS·프록시 신뢰 경계를 별도로 설정해야 한다. 여기서의 `200`은 Envoy extAuth **허용** 의미이며 애플리케이션 자체의 성공 상태와는 별개다.
