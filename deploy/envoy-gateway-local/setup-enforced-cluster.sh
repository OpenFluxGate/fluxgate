#!/usr/bin/env bash
set -euo pipefail
umask 077
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CLUSTER=fluxgate-enterprise
CONTEXT=kind-fluxgate-enterprise
ARTIFACT_DIR=${FLUXGATE_ENFORCED_ARTIFACT_DIR:-$(mktemp -d /tmp/fluxgate-enforced-cluster.XXXXXX)}
mkdir -p "$ARTIFACT_DIR"
chmod 700 "$ARTIFACT_DIR"
export KUBECONFIG="$ARTIFACT_DIR/kubeconfig"
for command in kind kubectl helm curl; do command -v "$command" >/dev/null; done
if kind get clusters | rg -qx "$CLUSTER"; then
  kind export kubeconfig --name "$CLUSTER" --kubeconfig "$KUBECONFIG"
else
  kind create cluster --name "$CLUSTER" --kubeconfig "$KUBECONFIG" \
    --config "$SCRIPT_DIR/kind-enforced.yaml" \
    --image kindest/node:v1.35.0@sha256:452d707d4862f52530247495d180205e029056831160e22870e37e3f6c1ac31f
fi
KUBE=(kubectl --context "$CONTEXT")
# Pinned primary manifests; Kubernetes 1.35 uses the v1beta1 admission API variant.
for manifest in v3_projectcalico_org-v1beta1.yaml tigera-operator.yaml; do
  curl -fsSL "https://raw.githubusercontent.com/projectcalico/calico/v3.33.0/manifests/$manifest" -o "$ARTIFACT_DIR/$manifest"
  "${KUBE[@]}" apply --server-side --field-manager=fluxgate-enforced-lab -f "$ARTIFACT_DIR/$manifest"
done
"${KUBE[@]}" wait --for=condition=Established crd/installations.operator.tigera.io --timeout=120s
"${KUBE[@]}" apply -f "$SCRIPT_DIR/calico-installation.yaml"
"${KUBE[@]}" wait --for=condition=Ready node --all --timeout=300s
"${KUBE[@]}" -n calico-system rollout status daemonset/calico-node --timeout=300s
"${KUBE[@]}" -n kube-system rollout status deployment/coredns --timeout=180s
helm --kube-context "$CONTEXT" upgrade --install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.9.2 -n envoy-gateway-system --create-namespace --wait --timeout 300s
"${KUBE[@]}" apply -f - <<'GATEWAY_CLASS'
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: eg
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
GATEWAY_CLASS
"${KUBE[@]}" get nodes -o json > "$ARTIFACT_DIR/nodes.json"
"${KUBE[@]}" -n calico-system get pods -o json > "$ARTIFACT_DIR/calico-pods.json"
"${KUBE[@]}" get installation default -o yaml > "$ARTIFACT_DIR/calico-installation.yaml"
shasum -a 256 "$ARTIFACT_DIR"/*.yaml > "$ARTIFACT_DIR/manifests.sha256"
printf 'Isolated cluster ready. Use:\nexport KUBECONFIG=%q\nexport FLUXGATE_ENTERPRISE_CONTEXT=%q\n' "$KUBECONFIG" "$CONTEXT"
printf 'Cluster evidence: %s\n' "$ARTIFACT_DIR"
