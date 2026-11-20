#!/usr/bin/env bash
set -euo pipefail
umask 077
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CONTEXT=${FLUXGATE_ENTERPRISE_CONTEXT:-kind-fluxgate-eg}
case "$CONTEXT" in kind-fluxgate-eg|kind-fluxgate-enterprise) ;; *) echo "Only the isolated FluxGate kind contexts are supported" >&2; exit 1;; esac
NAMESPACE=fluxgate-enterprise
KUBE=(kubectl --context "$CONTEXT")
TLS_DIR=${FLUXGATE_ENTERPRISE_TLS_DIR:?Set the TLS artifact directory printed by setup-enterprise.sh}
EVIDENCE_DIR=${FLUXGATE_ENTERPRISE_EVIDENCE_DIR:-$(mktemp -d /tmp/fluxgate-enterprise-proof.XXXXXX)}
mkdir -p "$EVIDENCE_DIR"
PORT=${FLUXGATE_ENTERPRISE_PORT:-8889}
TLS_PORT=${FLUXGATE_ENTERPRISE_TLS_PORT:-18443}
HEALTH_PORT=${FLUXGATE_ENTERPRISE_HEALTH_PORT:-18081}
PIDS=()
cleanup() { for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
"${KUBE[@]}" -n "$NAMESPACE" rollout status deployment/fluxgate-authz --timeout=180s
python3 - "$EVIDENCE_DIR" "$CONTEXT" <<'PY_STATUS'
import json, pathlib, subprocess, sys, time
path=pathlib.Path(sys.argv[1])/'policy-status.json'
for attempt in range(60):
    result=subprocess.run(['kubectl','--context',sys.argv[2],'-n','fluxgate-enterprise','get','gateway,httproute,securitypolicy,backendtlspolicy','-o','json'],capture_output=True,text=True,check=True)
    data=json.loads(result.stdout)
    path.write_text(json.dumps(data,indent=2)+'\n')
    valid=True
    for item in data['items']:
        status=item.get('status',{})
        if item['kind']=='Gateway':
            conditions=[c for listener in status.get('listeners',[]) for c in listener.get('conditions',[])]
            needed={'Accepted','Programmed','ResolvedRefs'}
        elif item['kind']=='HTTPRoute':
            conditions=[c for parent in status.get('parents',[]) for c in parent.get('conditions',[])]
            needed={'Accepted','ResolvedRefs'}
        else:
            conditions=[c for ancestor in status.get('ancestors',[]) for c in ancestor.get('conditions',[])]
            needed={'Accepted'}
        good={c['type'] for c in conditions if c['status']=='True' and c.get('observedGeneration')==item['metadata']['generation']}
        valid=valid and needed.issubset(good)
    if valid and len(data['items'])==4:
        break
    time.sleep(1)
else:
    raise SystemExit('Gateway listener or policy status did not become current and accepted; see policy-status.json')
PY_STATUS
SERVICE=$("${KUBE[@]}" -n envoy-gateway-system get service \
  -l gateway.envoyproxy.io/owning-gateway-namespace="$NAMESPACE",gateway.envoyproxy.io/owning-gateway-name=fluxgate-enterprise-gateway \
  -o jsonpath='{.items[0].metadata.name}')
[[ -n "$SERVICE" ]]
"${KUBE[@]}" -n envoy-gateway-system port-forward "service/$SERVICE" "$PORT:80" > "$EVIDENCE_DIR/envoy-forward.log" 2>&1 &
PIDS+=("$!")
"${KUBE[@]}" -n "$NAMESPACE" port-forward deployment/fluxgate-authz "$TLS_PORT:8443" "$HEALTH_PORT:8081" > "$EVIDENCE_DIR/authz-forward.log" 2>&1 &
PIDS+=("$!")
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$HEALTH_PORT/readyz" >/dev/null 2>&1; then break; fi
  sleep 1
done
READY=$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$HEALTH_PORT/readyz")
[[ "$READY" == 200 ]]
PLAIN_AUTHZ=$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$HEALTH_PORT/authz/api/items")
[[ "$PLAIN_AUTHZ" == 404 ]]
TLS_HOST=fluxgate-authz.fluxgate-enterprise.svc.cluster.local
TLS_ARGS=(--noproxy '*' --max-time 5 --cacert "$TLS_DIR/server-ca.crt" --resolve "$TLS_HOST:$TLS_PORT:127.0.0.1")
MTLS=$(curl -sS "${TLS_ARGS[@]}" --cert "$TLS_DIR/client.crt" --key "$TLS_DIR/client.key" -o /dev/null -w '%{http_code}' "https://$TLS_HOST:$TLS_PORT/healthz")
[[ "$MTLS" == 200 ]]
set +e
NO_CERT=$(curl -sS "${TLS_ARGS[@]}" -o /dev/null -w '%{http_code}' "https://$TLS_HOST:$TLS_PORT/healthz" 2> "$EVIDENCE_DIR/no-client-cert.log")
NO_CERT_EXIT=$?
set -e
# A network/DNS error is not evidence that client certificates are enforced.
[[ "$NO_CERT_EXIT" == 35 || "$NO_CERT_EXIT" == 56 ]]
[[ "$NO_CERT" == 000 ]]
python3 - "$EVIDENCE_DIR/no-client-cert.log" <<'PY_TLS'
import pathlib, sys
error=pathlib.Path(sys.argv[1]).read_text().lower()
markers=('alert certificate required','peer did not return a certificate','certificate required')
# LibreSSL 3.3.6 renders certificate_required (alert 116 + reason offset 1000) numerically.
# https://github.com/libressl/openbsd/blob/master/src/lib/libssl/ssl.h
libressl_certificate_required='libressl' in error and 'reason(1116)' in error
if not (any(marker in error for marker in markers) or libressl_certificate_required):
    raise SystemExit('Missing-client-cert request did not produce an explicit TLS certificate rejection')
PY_TLS
MTLS_AFTER=$(curl -sS "${TLS_ARGS[@]}" --cert "$TLS_DIR/client.crt" --key "$TLS_DIR/client.key" -o /dev/null -w '%{http_code}' "https://$TLS_HOST:$TLS_PORT/healthz")
[[ "$MTLS_AFTER" == 200 ]]
for index in 1 2 3; do
  CODE=$(curl -sS --max-time 7 -D "$EVIDENCE_DIR/request-$index.headers" -o "$EVIDENCE_DIR/request-$index.body" \
    -w '%{http_code}' "http://127.0.0.1:$PORT/api/items")
  printf '%s\n' "$CODE" > "$EVIDENCE_DIR/request-$index.status"
done
"${KUBE[@]}" -n "$NAMESPACE" get gateway,httproute,securitypolicy,backendtlspolicy -o json > "$EVIDENCE_DIR/policy-status.json"
"${KUBE[@]}" -n "$NAMESPACE" get pods -o json > "$EVIDENCE_DIR/pods.json"
"${KUBE[@]}" -n "$NAMESPACE" logs deployment/fluxgate-authz --all-pods=true --tail=100 > "$EVIDENCE_DIR/authz.log"
python3 - "$EVIDENCE_DIR" "$MTLS" "$NO_CERT" "$NO_CERT_EXIT" "$READY" "$PLAIN_AUTHZ" "$MTLS_AFTER" <<'PY'
import json, pathlib, sys
proof=pathlib.Path(sys.argv[1])
codes=[int((proof/f'request-{i}.status').read_text()) for i in (1,2,3)]
result={'gateway_statuses':codes,'mtls_health':int(sys.argv[2]),'missing_client_certificate':{'http':sys.argv[3],'curl_exit':int(sys.argv[4])},'published_policy_readiness':int(sys.argv[5]),'plaintext_authz':int(sys.argv[6]),'mtls_health_after_negative':int(sys.argv[7]),'network_policy_enforcement':'requires separate verify-network-policy.sh evidence'}
(proof/'smoke.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
if codes != [200,200,429]:
    raise SystemExit('Fresh bootstrap quotas expected [200, 200, 429]; existing buckets may already be consumed. Do not reset production data.')
assert 'retry-after:' in (proof/'request-3.headers').read_text().lower()
PY
printf 'Evidence: %s\n' "$EVIDENCE_DIR"
