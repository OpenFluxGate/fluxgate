#!/usr/bin/env bash
set -euo pipefail
umask 077
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
CONTEXT=${FLUXGATE_ENTERPRISE_CONTEXT:-kind-fluxgate-eg}
case "$CONTEXT" in kind-fluxgate-eg|kind-fluxgate-enterprise) ;; *) echo "Only the isolated FluxGate kind contexts are supported" >&2; exit 1;; esac
NAMESPACE=fluxgate-enterprise
KUBE=(kubectl --context "$CONTEXT")
if [[ ${1:-} == --dry-run ]]; then
  "${KUBE[@]}" apply --dry-run=client --validate=strict -f "$SCRIPT_DIR/enterprise.yaml"
  exit 0
fi
for command in kubectl openssl docker kind; do command -v "$command" >/dev/null; done
"${KUBE[@]}" get namespace envoy-gateway-system >/dev/null
if [[ ! -f "$REPO_DIR/fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.4.0-SNAPSHOT.jar" ]]; then
  echo 'Build the current extAuth jar before running setup-enterprise.sh.' >&2
  exit 1
fi
TLS_DIR=${FLUXGATE_ENTERPRISE_TLS_DIR:-$(mktemp -d /tmp/fluxgate-enterprise-tls.XXXXXX)}
mkdir -p "$TLS_DIR"
chmod 700 "$TLS_DIR"
# Two dedicated development CAs prevent a server certificate being accepted as a gateway client.
if [[ ! -f "$TLS_DIR/client.crt" || ! -f "$TLS_DIR/server.crt" ]]; then
  for identity in server-ca client-ca; do
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -sha256 \
      -keyout "$TLS_DIR/$identity.key" -out "$TLS_DIR/$identity.crt" \
      -subj "/CN=fluxgate-enterprise-local-$identity" >/dev/null 2>&1
  done
  openssl req -new -newkey rsa:2048 -nodes -keyout "$TLS_DIR/server.key" \
    -out "$TLS_DIR/server.csr" -subj '/CN=fluxgate-authz.fluxgate-enterprise.svc.cluster.local' >/dev/null 2>&1
  cat > "$TLS_DIR/server.ext" <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:fluxgate-authz.fluxgate-enterprise.svc.cluster.local
EXT
  openssl x509 -req -in "$TLS_DIR/server.csr" -CA "$TLS_DIR/server-ca.crt" \
    -CAkey "$TLS_DIR/server-ca.key" -CAcreateserial -days 2 -sha256 \
    -extfile "$TLS_DIR/server.ext" -out "$TLS_DIR/server.crt" >/dev/null 2>&1
  openssl req -new -newkey rsa:2048 -nodes -keyout "$TLS_DIR/client.key" \
    -out "$TLS_DIR/client.csr" -subj '/CN=fluxgate-envoy-gateway' >/dev/null 2>&1
  cat > "$TLS_DIR/client.ext" <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth
EXT
  openssl x509 -req -in "$TLS_DIR/client.csr" -CA "$TLS_DIR/client-ca.crt" \
    -CAkey "$TLS_DIR/client-ca.key" -CAcreateserial -days 2 -sha256 \
    -extfile "$TLS_DIR/client.ext" -out "$TLS_DIR/client.crt" >/dev/null 2>&1
fi
chmod 600 "$TLS_DIR"/*
docker build -f "$REPO_DIR/fluxgate-envoy-extauth/Dockerfile" -t fluxgate-envoy-extauth:enterprise "$REPO_DIR"
kind load docker-image fluxgate-envoy-extauth:enterprise --name "${CONTEXT#kind-}"
"${KUBE[@]}" create namespace "$NAMESPACE" --dry-run=client -o yaml | "${KUBE[@]}" apply -f -
"${KUBE[@]}" -n "$NAMESPACE" create secret generic fluxgate-authz-server-tls \
  --from-file=tls.crt="$TLS_DIR/server.crt" --from-file=tls.key="$TLS_DIR/server.key" \
  --from-file=client-ca.crt="$TLS_DIR/client-ca.crt" --dry-run=client -o yaml | "${KUBE[@]}" apply -f -
"${KUBE[@]}" -n "$NAMESPACE" create secret tls fluxgate-envoy-client-tls \
  --cert="$TLS_DIR/client.crt" --key="$TLS_DIR/client.key" --dry-run=client -o yaml | "${KUBE[@]}" apply -f -
"${KUBE[@]}" -n "$NAMESPACE" create configmap fluxgate-authz-server-ca \
  --from-file=ca.crt="$TLS_DIR/server-ca.crt" --dry-run=client -o yaml | "${KUBE[@]}" apply -f -
"${KUBE[@]}" apply --dry-run=server --validate=strict -f "$SCRIPT_DIR/enterprise.yaml" >/dev/null
"${KUBE[@]}" apply -f "$SCRIPT_DIR/enterprise.yaml"
"${KUBE[@]}" -n "$NAMESPACE" rollout restart deployment/fluxgate-authz
for deployment in mongo redis fluxgate-authz echo; do
  "${KUBE[@]}" -n "$NAMESPACE" rollout status "deployment/$deployment" --timeout=300s
done
printf 'TLS artifacts (private, outside git): %s\n' "$TLS_DIR"
printf 'Run: FLUXGATE_ENTERPRISE_TLS_DIR=%q %q\n' "$TLS_DIR" "$SCRIPT_DIR/verify-enterprise.sh"
