# 로컬 Envoy Gateway + FluxGate 시연

이 예제에서 Envoy는 MongoDB나 Redis에 직접 연결하지 않는다. Envoy가 Java 판정 서비스로 요청을 보내면, 서비스가 MongoDB의 `fluxgate.rate_limit_rules`에서 규칙을 읽고 Redis의 버킷을 집행한다. 판정 이벤트는 MongoDB의 `rate_limit_events`에 기록된다. 샘플은 `/api/**`에 적용되는 전역 규칙 두 개를 사용하며, 더 엄격한 규칙이 2회/60초를 제한한다. 클라이언트가 보낸 `X-Forwarded-For`, 사용자 ID, rule-set ID는 판정에 사용하지 않는다. 사용자별 할당량은 신뢰 가능한 프록시 체인과 인증된 ID 전파를 별도로 설계한 다음 추가해야 한다.

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
kubectl --context kind-fluxgate-eg -n fluxgate-pilot rollout status deployment/mongo --timeout=180s
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

다른 터미널에서 Redis의 새 버킷을 쓰도록 약 60초 기다린 뒤 테스트한다. 첫 두 요청은 `200`, 세 번째는 `429`와 `Retry-After`가 예상된다.

```bash
curl -i http://127.0.0.1:8888/api/items
curl -i http://127.0.0.1:8888/api/items
curl -i http://127.0.0.1:8888/api/items
```

규칙과 판정 이벤트는 다음처럼 직접 확인할 수 있다. 이 샘플의 MongoDB 데이터는 `emptyDir`에 있으므로 Pod 재생성 시 사라진다.

```bash
kubectl --context kind-fluxgate-eg -n fluxgate-pilot exec deployment/mongo -- mongosh fluxgate --quiet --eval 'printjson({rules:db.rate_limit_rules.countDocuments({}),events:db.rate_limit_events.countDocuments({})})'
kubectl --context kind-fluxgate-eg -n fluxgate-pilot exec deployment/redis -- redis-cli DBSIZE
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

## 별도 namespace의 발행 정책·mTLS 개발 환경

`enterprise.yaml`과 아래 스크립트는 `kind-fluxgate-eg`의 **`fluxgate-enterprise` namespace 및 `fluxgate-enterprise-gateway` Gateway만** 구성한다. 기존 `fluxgate-pilot` 환경은 유지한다. 이름의 enterprise는 통합 개발 환경을 구분하며 운영 준비 완료를 뜻하지 않는다. MongoDB와 Redis는 인증 없는 로컬 임시 저장소이며 데이터는 Pod 재생성 시 사라진다.

Java 판정 서비스 두 Pod가 발행된 동일 Mongo 정책을 읽고 Redis에서 두 전역 규칙을 집행한다. 부트스트랩은 `local-enterprise` 프로필에서만 실행하며 고정 operation ID로 최초 정책을 발행한다. 규칙의 capacity는 각각 3과 2이고 window는 3600초다. `/api` 경로의 rule-set과 permit 비용은 서버 설정으로 선택한다. 일반 요청이 보낸 사용자 ID·rule-set·permit 헤더는 신뢰하지 않는다. 이 로컬 환경에는 공개 시험 credential `fluxgate-local-test-key`의 SHA256 mapping을 넣었으며 검증 시 `user-id=local-user`, `api-key-id=local-key`, `tenant=local`로 연결한다. 이 fixture는 운영 환경에 사용하면 안 된다. API key별 identity가 필요하면 검증된 SHA256 mapping 또는 `EnvoyIdentityResolver`를 설정해야 한다.

Gateway → Java 구간은 HTTPS 8443과 client certificate 인증을 사용한다. `EnvoyProxy.backendTLS.clientCertificateRef`, `Gateway.infrastructure.parametersRef`, `BackendTLSPolicy`의 서버 CA·hostname 검증을 사용한다. 서버와 gateway client용 CA는 분리하고 Java는 client certificate를 필수로 요구한다. Gateway certificate subject는 `CN=fluxgate-envoy-gateway`다. EG 1.9.2의 HTTP extAuth에는 임의 `additionalHeaders` secret 주입 필드가 없으므로 이 환경은 mTLS로 내부 호출자를 인증한다.

```bash
# 현재 변경의 테스트와 package가 끝난 jar를 사용한다.
./mvnw -pl fluxgate-envoy-extauth -am package -DskipTests -Dspotless.skip=true

# schema 조회/검증만 수행하며 클러스터를 변경하지 않는다.
./deploy/envoy-gateway-local/setup-enterprise.sh --dry-run

# 별도 image tag와 namespace를 구성한다.
./deploy/envoy-gateway-local/setup-enterprise.sh

# setup이 출력한 /tmp 디렉터리를 사용한다. 디렉터리 안의 key는 git에 넣지 않는다.
FLUXGATE_ENTERPRISE_TLS_DIR=/tmp/fluxgate-enterprise-tls.XXXXXX \
  ./deploy/envoy-gateway-local/verify-enterprise.sh
```

CA와 key는 repository 외부의 권한 700 디렉터리·권한 600 파일에 생성하며 유효기간은 개발용 2일이다. `FLUXGATE_ENTERPRISE_TLS_DIR`를 지정하면 동일 개발 인증서를 재사용한다. 스크립트는 key나 password를 출력하지 않는다. 테스트는 인증서 없는 직접 TLS 호출의 거절, 인증된 TLS health 200, 발행 정책 readiness 200, plaintext 포트의 authz 404, 신규 버킷에 대한 Envoy 응답 `[200,200,429]`와 `Retry-After`를 확인한다. 반복 실행으로 quota가 이미 소비됐다면 최초 순서 검증은 실패하며 자동으로 버킷을 초기화하지 않는다.

포트포워드와 로그·상태·`smoke.json` 증거는 verify 스크립트가 임시 디렉터리에 남긴다. 수동 Gateway 접근은 다음과 같다.

```bash
ENTERPRISE_ENVOY_SERVICE=$(kubectl --context kind-fluxgate-eg -n envoy-gateway-system get service \
  -l gateway.envoyproxy.io/owning-gateway-namespace=fluxgate-enterprise,gateway.envoyproxy.io/owning-gateway-name=fluxgate-enterprise-gateway \
  -o jsonpath='{.items[0].metadata.name}')
kubectl --context kind-fluxgate-eg -n envoy-gateway-system port-forward "service/$ENTERPRISE_ENVOY_SERVICE" 8889:80
curl -i http://127.0.0.1:8889/api/items
```

Mongo URI는 연결·서버 선택·기존 socket 읽기에 각각 2초 제한(`connectTimeoutMS`, `serverSelectionTimeoutMS`, `socketTimeoutMS`)을 설정한다. Redis command timeout도 2초다. 이 설정은 연결된 저장소 프로세스가 정지한 경우에도 authz가 무기한 대기하지 않도록 한다.

별도 plaintext 8081 listener는 `/healthz`와 `/readyz`만 제공한다. readiness는 설정된 모든 발행 정책이 존재하는지 확인하며 quota를 소비하지 않는다. authz Service에는 8443만 노출한다. NetworkPolicy는 Gateway에서 authz/backend로, authz에서 Mongo/Redis/DNS로 연결을 제한하도록 구성했다. **현재 kind 기본 CNI에서는 NetworkPolicy 강제를 검증하지 않았으며 egress 제어가 작동한다고 주장하지 않는다.** 집행 가능한 CNI에서 허용·차단 테스트가 추가로 필요하다. Pod probe의 kubelet 접근은 Kubernetes의 node 예외 의미에 의존한다.

### NetworkPolicy 집행을 검증하는 독립 Calico cluster

기존 `kind-fluxgate-eg`에는 kindnet이 설치돼 있어 정책 manifest만으로 차단을 증명할 수 없다. `setup-enforced-cluster.sh`는 별도 `fluxgate-enterprise` kind cluster를 만들고 공식 Calico 설치 흐름에 따라 **Calico v3.33.0**, Kubernetes v1.35.0의 고정 node digest, Envoy Gateway v1.9.2와 controller를 지정하는 `GatewayClass eg`를 설치한다. Calico의 native API에 필요한 `MutatingAdmissionPolicy` beta feature gate·runtime API를 새 cluster에서만 활성화한다. kubeconfig는 `/tmp/fluxgate-enforced-cluster.*/kubeconfig`에 보관하므로 기존 current-context를 바꾸지 않는다. [Calico kind 설치](https://docs.tigera.io/calico/latest/getting-started/kubernetes/kind), [Kubernetes 1.35 feature gates](https://v1-35.docs.kubernetes.io/docs/reference/command-line-tools-reference/feature-gates/).

```bash
./deploy/envoy-gateway-local/setup-enforced-cluster.sh
# 위 명령이 출력한 실제 경로를 사용한다.
export KUBECONFIG=/tmp/fluxgate-enforced-cluster.XXXXXX/kubeconfig
export FLUXGATE_ENTERPRISE_CONTEXT=kind-fluxgate-enterprise
./deploy/envoy-gateway-local/setup-enterprise.sh
# setup이 출력한 인증서 디렉터리를 지정한다.
FLUXGATE_ENTERPRISE_TLS_DIR=/tmp/fluxgate-enterprise-tls.XXXXXX \
  ./deploy/envoy-gateway-local/verify-enterprise.sh
./deploy/envoy-gateway-local/verify-network-policy.sh
```

네트워크 검증은 별도 probe namespace의 미인가 Pod가 echo/authz/Mongo/Redis의 Service 주소 및 Pod IP로 직접 연결할 수 없는지 검사한다. 실제 Java authz Pod에서 Mongo·Redis 연결이 허용되고, 제한 없는 시험용 sink와 echo로의 연결은 차단되는지도 확인한다. 시험용 sink에 미인가 Pod의 연결이 성공해야만 차단 결과를 인정하므로 단순한 네트워크 장애를 정책 집행으로 오인하지 않는다. 차단 판정은 probe 안에서 TCP connect timeout으로 식별된 경우에만 인정하며 DNS 실패, connection refused, kubectl exec 실패는 검증 실패로 처리한다. 각 대상은 허용된 source에서 차단 검사 전후에 연결되고 미인가 probe의 생존도 확인해야 한다. Gateway namespace의 시험용 허용 probe는 관리자 권한으로 보호된 Gateway labels를 부여하며, 실제 Envoy/mTLS 경로 증거는 별도 smoke 결과로 확인한다. 이 probe Pod에는 Kubernetes API token을 자동 마운트하지 않으며 검사 후 해당 Pod·Service만 제거한다. 결과는 `/tmp/fluxgate-network-proof.*/network-policy.json`에 남긴다. 성공 결과가 수집되기 전에는 네트워크 집행이 검증됐다고 판단하지 않는다. 관리자 권한의 port-forward·host-network Pod와 label/RBAC 변경 권한은 이 Pod 네트워크 검증의 경계 밖이다.

2026-10-10 로컬 검증에서 Calico cluster의 실제 Envoy smoke는 `[200,200,429]`, 인증된 TLS health·발행 정책 readiness `200`, 인증서 없는 TLS certificate-required alert, plaintext authz `404`를 확인했다. `verify-network-policy.sh`는 Service/Pod IP 직접 접근과 실제 authz egress를 포함한 **31개 항목을 모두 통과**했다. 증거는 `/tmp/fluxgate-enterprise-proof.Qf8MIY/smoke.json`과 `/tmp/fluxgate-network-proof.S4IAVb/network-policy.json`이며 임시 로컬 파일이므로 재현 시 새 결과 디렉터리를 사용한다. 이 결과는 격리된 개발 cluster의 검증이며 앞서 설명한 관리자·host-network·RBAC 경계 밖 접근은 검증하지 않았다.

### 정책 변경과 장애 회귀

검증 순서는 최초 quota smoke → NetworkPolicy → lifecycle → 장애 → Studio 발행 API다. 최초 smoke를 다시 실행해 기존 버킷이 `[429,429,429]`를 반환하는 것은 새 정책 장애의 증거가 아니다. 스크립트는 운영 데이터나 기존 quota를 자동 초기화하지 않는다.

`EnvoyPolicyLifecycleLiveTest`는 JUnit 테스트 대신 실제 환경을 명시적으로 요구하는 실행 클래스다. `kind-fluxgate-enterprise`·`local-ephemeral` namespace·loopback 저장소·서로 다른 판정 Pod 두 개를 사용한다. 최초 전역 3/2 quota fixture를 확인한 뒤 **사유가 기록된 reset 두 번**을 실행한다. ACL 변경, 동일 epoch에서 capacity 감소/증가·rollback, CAS 충돌·operation 재전송, 손상된 snapshot의 503·정확한 복원, API-key 신원 검증을 검사한다. 공유 규칙의 quota를 충분히 남긴 상태에서 앞선 규칙의 사용량을 검사하므로 다른 규칙의 거부가 오류를 가리지 않는다. 종료 후에는 fixture API key가 필요한 사용자/API-key 정책이 활성화된다.

실제 Pod 이름을 `kubectl get pods -l app=fluxgate-authz`로 확인하고 각각 별도 터미널에서 포트포워드한다. Deployment 포트포워드 두 개가 동일 Pod를 선택한 결과를 복수 Pod 증거로 사용하면 안 된다.

```bash
# 각 명령은 별도 터미널에서 실행한다. Pod 이름과 kubeconfig는 실제 값을 사용한다.
kubectl --context kind-fluxgate-enterprise -n fluxgate-enterprise port-forward pod/<첫-판정-Pod> 18443:8443 18083:8081
kubectl --context kind-fluxgate-enterprise -n fluxgate-enterprise port-forward pod/<둘째-판정-Pod> 18444:8443 18084:8081
kubectl --context kind-fluxgate-enterprise -n fluxgate-enterprise port-forward service/mongo 27039:27017
# Envoy 8889 포트포워드도 유지한다.

export FLUXGATE_LIVE_MONGO_URI='mongodb://127.0.0.1:27039/fluxgate?connectTimeoutMS=2000&serverSelectionTimeoutMS=2000&socketTimeoutMS=2000'
export FLUXGATE_LIVE_ENVOY_URL=http://127.0.0.1:8889
export FLUXGATE_LIVE_AUTHZ_POD_URLS='https://fluxgate-authz.fluxgate-enterprise.svc.cluster.local:18443,https://fluxgate-authz.fluxgate-enterprise.svc.cluster.local:18444'
export FLUXGATE_LIVE_READY_URLS='http://127.0.0.1:18083/readyz,http://127.0.0.1:18084/readyz'
export FLUXGATE_LIVE_CLIENT_CERT="$FLUXGATE_ENTERPRISE_TLS_DIR/client.crt"
export FLUXGATE_LIVE_CLIENT_KEY="$FLUXGATE_ENTERPRISE_TLS_DIR/client.key"
export FLUXGATE_LIVE_CA_CERT="$FLUXGATE_ENTERPRISE_TLS_DIR/server-ca.crt"
RUNTIME_DIR=$(mktemp -d /tmp/fluxgate-live-runtime.XXXXXX)
unzip -q fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.3.7.jar 'BOOT-INF/lib/*' -d "$RUNTIME_DIR"
java -cp "fluxgate-envoy-extauth/target/test-classes:fluxgate-envoy-extauth/target/classes:$RUNTIME_DIR/BOOT-INF/lib/*" org.fluxgate.envoy.EnvoyPolicyLifecycleLiveTest

python3 deploy/envoy-gateway-local/verify-failure-modes.py
```

장애 스크립트는 exact kind node·namespace label·containerd Mongo task를 확인한 뒤 `PAUSED`를 검증한다. Redis는 10초 동안 command 처리를 일시 중지하고, Mongo는 task freezer를 사용해 기존 socket의 읽기 장애를 만든다. PID 1에 내부 `SIGSTOP`을 보냈다는 사실만으로 장애를 가정하지 않는다. 판정 서비스를 0→2 replica로 복원하며 Mongo/Redis Pod를 삭제하지 않는다. 실패한 검사에서도 Mongo task resume과 replica 복원을 시도한다. 정상 echo의 상태·본문을 검사 전후에 확인하고, GET/OPTIONS 모두 저장소 장애는 503, 판정 endpoint 부재는 EG 1.9.2에서 500 또는 503으로 차단되는지 확인한다. 모든 검사를 통과한 경우에만 `failure-modes.json`의 `result=pass`를 기록한다.

장애 검사 후 판정 Pod 이름이 바뀌므로 위 두 Pod 포트포워드를 새 이름으로 재생성해야 한다. Studio의 실제 JWT·ADMIN 발행·rollback 검증은 별도 Studio worktree의 `deploy/verify-published-policy.py`와 안내를 사용한다. 테스트 결과와 제한사항은 [최종 리뷰](../../docs/reviews/2026-10-10-enterprise-envoy-review.ko.md)에 기록한다.
