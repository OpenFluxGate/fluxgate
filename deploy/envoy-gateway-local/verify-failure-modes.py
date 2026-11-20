#!/usr/bin/env python3
"""Exercise reversible failures only in the isolated local enterprise kind fixture.

Preserves Mongo/Redis Pods and their ephemeral data. The exact Mongo container is
paused through kind's container runtime, verified PAUSED, and resumed in cleanup.
The authz replica count is restored. Requires Docker and a live Envoy port forward.
"""
import json
import os
import pathlib
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main():
    context = os.environ.get("FLUXGATE_ENTERPRISE_CONTEXT", "kind-fluxgate-enterprise")
    if context != "kind-fluxgate-enterprise":
        raise RuntimeError("Failure injection requires the isolated enterprise cluster")
    base = ["kubectl", "--context", context, "-n", "fluxgate-enterprise"]
    url = os.environ.get("FLUXGATE_LIVE_ENVOY_URL", "http://127.0.0.1:8889")
    if not url.startswith("http://127.0.0.1:"):
        raise RuntimeError("Only a local Envoy forward is supported")
    proof = pathlib.Path(os.environ.get("FLUXGATE_FAILURE_EVIDENCE_DIR", tempfile.mkdtemp(prefix="fluxgate-failures-")))
    proof.mkdir(parents=True, exist_ok=True)
    observations = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def kube(args):
        return subprocess.run(base + args, check=True, capture_output=True, text=True, timeout=60).stdout

    def request(label, method, allowed):
        started = time.monotonic()
        req = urllib.request.Request(url + "/api/failure-proof", method=method,
                                     headers={"X-API-Key": "fluxgate-local-test-key"})
        try:
            response = opener.open(req, timeout=9)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read().decode("utf-8", errors="replace")
            status = response.status
        if status not in allowed or (status >= 500 and "fluxgate-enterprise-ok" in body):
            raise AssertionError(f"{label}/{method}: unexpected HTTP {status}")
        observations.append({"check": label, "method": method, "status": status,
                             "elapsed_ms": round((time.monotonic() - started) * 1000)})

    def recover(label):
        deadline = time.monotonic() + 45
        while True:
            try:
                request(label, "GET", {200, 429})
                return
            except (AssertionError, TimeoutError, urllib.error.URLError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)

    def backend_control(label):
        # Administrative API port-forward is a positive control, not a permitted workload path.
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        forward = subprocess.Popen(base + ["port-forward", "deployment/echo", str(port) + ":5678"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 8
            while True:
                try:
                    if forward.poll() is not None:
                        raise RuntimeError("Backend control port-forward exited")
                    with opener.open(f"http://127.0.0.1:{port}/", timeout=2) as response:
                        if response.status != 200 or response.read().strip() != b"fluxgate-enterprise-ok":
                            raise AssertionError("Backend positive control failed")
                    observations.append({"check": label, "status": 200})
                    return
                except urllib.error.URLError:
                    if forward.poll() is not None or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.2)
        finally:
            forward.terminate()
            forward.wait(timeout=5)

    mongo_paused = False
    authz_scaled = False
    replicas = json.loads(kube(["get", "deployment", "fluxgate-authz", "-o", "json"]))["spec"]["replicas"]
    if replicas != 2:
        raise RuntimeError("Expected the two-Pod enterprise fixture")
    namespace = json.loads(kube(["get", "namespace", "fluxgate-enterprise", "-o", "json"]))
    if namespace["metadata"].get("labels", {}).get("fluxgate.io/environment") != "local-ephemeral":
        raise RuntimeError("Expected the ephemeral enterprise fixture")
    mongo_pods = json.loads(kube(["get", "pod", "-l", "app=mongo", "-o", "json"]))["items"]
    if len(mongo_pods) != 1:
        raise RuntimeError("Expected exactly one local Mongo Pod")
    mongo = mongo_pods[0]
    node = mongo["spec"]["nodeName"]
    if node != "fluxgate-enterprise-control-plane":
        raise RuntimeError("Mongo pause requires the isolated kind node")
    cluster = subprocess.check_output(["docker", "inspect", node, "--format",
                                      '{{index .Config.Labels "io.x-k8s.kind.cluster"}}'],
                                     text=True, timeout=10).strip()
    if cluster != "fluxgate-enterprise":
        raise RuntimeError("Docker node is not the isolated kind cluster")
    container = next(c["containerID"] for c in mongo["status"]["containerStatuses"]
                     if c["name"] == "mongo")
    if not re.fullmatch(r"containerd://[0-9a-f]{64}", container):
        raise RuntimeError("Expected an exact containerd Mongo task")
    container = container.removeprefix("containerd://")
    ctr = ["docker", "exec", node, "ctr", "-n", "k8s.io", "tasks"]

    def task_state():
        tasks = subprocess.check_output(ctr + ["ls"], text=True, timeout=10)
        return next(line.split()[-1] for line in tasks.splitlines()
                    if line.split() and line.split()[0] == container)

    def resume_mongo():
        if task_state() == "PAUSED":
            subprocess.run(ctr + ["resume", container], check=True, timeout=10)
        if task_state() != "RUNNING":
            raise RuntimeError("Mongo task did not resume")

    if task_state() != "RUNNING":
        raise RuntimeError("Mongo task must start RUNNING")
    try:
        backend_control("echo-healthy-before-failures")
        request("healthy-baseline", "GET", {200, 429})
        # Redis processes all commands only after this bounded pause. No key is deleted.
        started = time.monotonic()
        kube(["exec", "deployment/redis", "--", "redis-cli", "CLIENT", "PAUSE", "10000", "ALL"])
        request("redis-paused-fail-closed", "GET", {503})
        request("redis-paused-fail-closed", "OPTIONS", {503})
        if time.monotonic() - started >= 10:
            raise AssertionError("Redis outage requests exceeded the pause window")
        time.sleep(10)
        recover("redis-recovered")
        # PID 1 signals inside its own namespace may not stop the process. Use the
        # actual task freezer and verify state before testing established sockets.
        mongo_paused = True
        subprocess.run(ctr + ["pause", container], check=True, timeout=10)
        if task_state() != "PAUSED":
            raise RuntimeError("Mongo task was not actually paused")
        observations.append({"check": "mongo-task-paused", "state": "PAUSED"})
        request("mongo-paused-fail-closed", "GET", {503})
        request("mongo-paused-fail-closed", "OPTIONS", {503})
        resume_mongo()
        mongo_paused = False
        recover("mongo-recovered")
        authz_scaled = True
        kube(["scale", "deployment/fluxgate-authz", "--replicas=0"])
        kube(["wait", "--for=delete", "pod", "-l", "app=fluxgate-authz", "--timeout=45s"])
        # EG 1.9.2 returns 500 when the authz cluster has no endpoints even with
        # statusOnError=503. Both are closed failure responses, as documented in
        # this fixture's README; preserve the exact status in the evidence.
        request("authz-unavailable-fail-closed", "GET", {500, 503})
        request("authz-unavailable-fail-closed", "OPTIONS", {500, 503})
        kube(["scale", "deployment/fluxgate-authz", "--replicas=2"])
        kube(["rollout", "status", "deployment/fluxgate-authz", "--timeout=55s"])
        authz_scaled = False
        recover("authz-recovered-with-existing-policy")
        backend_control("echo-healthy-after-failures")
        result = {"result": "pass", "context": context, "checks": observations}
        (proof / "failure-modes.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
    finally:
        try:
            if mongo_paused:
                resume_mongo()
        finally:
            if authz_scaled:
                kube(["scale", "deployment/fluxgate-authz", "--replicas=" + str(replicas)])


if __name__ == "__main__":
    main()
