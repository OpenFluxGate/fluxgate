#!/usr/bin/env python3
"""Opt-in real Gateway load proof; all dependencies are Python standard library.

Run --self-check without a cluster. Live --fixture accepts the private fixture.json or
its directory. --phase load (default) never consumes the dedicated quota; root must
serialize --phase quota against the other proofs. The in-cluster Python generator
uses HTTP/1.1 keepalive, bypasses kubectl/port-forward on the measured request path,
and receives credential contents through stdin only. No notifier or storage write
is performed. Acceptance bounds are fixed below, before any measurement.

--phase resilience warms up then observes 100 RPS for 120 seconds. Its result
OBSERVATION_COMPLETE proves complete accounting/body integrity, not a fault SLO.
Find its owned Pod via label fluxgate.io/load-phase=resilience and read
/tmp/fluxgate-load-ready.json via kubectl exec; the atomic marker gives the
scheduled start Unix time. Root alone injects faults and assesses fault windows.
"""

import argparse
import concurrent.futures
import http.client
import json
import math
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time
import uuid


BASELINE_RPS = 100
BASELINE_SECONDS = 60
MIN_ACHIEVED_RPS = 99
P95_MS = 100
P99_MS = 250
EXPECTED_FRACTION = 0.999
QUOTA_BURST = 100
QUOTA_ALLOWED = 5
BACKEND_BODY = b"fluxgate-resilience-ok"  # Exact public echo text in stack.yaml.
RESILIENCE_SECONDS = 120
READY_MARKER = "/tmp/fluxgate-load-ready.json"


def percentile(values, quantile):
    """Nearest-rank percentiles, including errors and scheduled-arrival queue time."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def aggregate(samples, target, duration, elapsed, omitted, worker_failures):
    statuses = {}
    distributions = {}
    expected = 0
    for sample in samples:
        status = "ERROR" if sample.get("error") is not None else str(sample["status"])
        statuses[status] = statuses.get(status, 0) + 1
        kind = "invalid-key" if sample["expected"] == 403 else sample["method"]
        counts = distributions.setdefault(kind, {})
        counts[status] = counts.get(status, 0) + 1
        expected += (sample.get("error") is None and status == str(sample["expected"])
                     and sample.get("body_valid", True))
    return {
        "target_requests": target,
        "planned_requests": target,
        "submitted_requests": target - omitted,
        "completed_requests": len(samples),
        "target_rps": target / duration,
        "achieved_rps": len(samples) / elapsed,
        "measurement_seconds": duration,
        "elapsed_including_drain_seconds": elapsed,
        "omitted_schedules": omitted,
        "unaccounted_schedules": target - len(samples) - omitted,
        "worker_failures": worker_failures,
        "transport_errors": sum(sample.get("error") is not None for sample in samples),
        "unexpected_body_responses": sum(not sample.get("body_valid", True) for sample in samples),
        "all_statuses": statuses,
        "request_distributions": distributions,
        "expected_status_fraction": expected / target if target else 0,
        "latency_ms": {
            "p50": percentile([s["latency_ms"] for s in samples], 0.50),
            "p95": percentile([s["latency_ms"] for s in samples], 0.95),
            "p99": percentile([s["latency_ms"] for s in samples], 0.99),
            "max": max((s["latency_ms"] for s in samples), default=None),
        },
        "dispatch_lag_ms": {
            "p95": percentile([s["dispatch_lag_ms"] for s in samples], 0.95),
            "max": max((s["dispatch_lag_ms"] for s in samples), default=None),
        },
        "samples": samples,
    }


def generator_valid(report):
    return (report["omitted_schedules"] == 0 and report["unaccounted_schedules"] == 0
            and report["worker_failures"] == 0
            and report["completed_requests"] == report["target_requests"])


def baseline_passes(report):
    return (generator_valid(report)
            and report["target_requests"] == BASELINE_RPS * BASELINE_SECONDS
            and report["measurement_seconds"] == BASELINE_SECONDS
            and report["achieved_rps"] >= MIN_ACHIEVED_RPS
            and report["unexpected_body_responses"] == 0
            and report["expected_status_fraction"] >= EXPECTED_FRACTION
            and report["latency_ms"]["p95"] is not None
            and report["latency_ms"]["p95"] <= P95_MS
            and report["latency_ms"]["p99"] <= P99_MS)


class GatewayClient:
    def __init__(self, config):
        self.config = config
        self.local = threading.local()
        self.clock_offset = time.time() - time.monotonic()

    def request(self, sequence, scheduled, method, path, credential, expected):
        started = time.monotonic()
        status = "ERROR"
        received_status = None
        error = None
        backend_body = False
        body_valid = True
        connection = getattr(self.local, "connection", None)
        try:
            if connection is None:
                connection = http.client.HTTPConnection(
                    self.config["service"], self.config["port"], timeout=10)
                self.local.connection = connection
            headers = {"Host": self.config["host"], "Connection": "keep-alive"}
            if credential is not None:
                headers["X-API-Key"] = credential
            connection.request(method, path, headers=headers)
            response = connection.getresponse()
            status = response.status
            received_status = status
            # Fully drain the body before reusing the connection, including denied responses.
            prefix = bytearray()
            total_bytes = 0
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                total_bytes += len(chunk)
                if len(prefix) < 1024:
                    prefix.extend(chunk[:1024 - len(prefix)])
            # read(n) can return EOF without raising when Content-Length was not fulfilled.
            # Even an apparently correct echo prefix is incomplete transport in that case.
            remaining = getattr(response, "length", None)
            if remaining is not None and remaining != 0:
                raise http.client.IncompleteRead(bytes(prefix), remaining)
            backend_body = total_bytes <= 1024 and bytes(prefix) in (BACKEND_BODY, BACKEND_BODY + b"\n")
            body_valid = backend_body if status == 200 else not backend_body
            if response.will_close:
                connection.close()
                self.local.connection = None
        except (OSError, http.client.HTTPException):
            # No automatic retry: retries would hide real errors and alter quota consumption.
            error = "transport-error"
            status = "ERROR"
            if connection is not None:
                connection.close()
            self.local.connection = None
        finished = time.monotonic()
        return {"sequence": sequence, "method": method, "expected": expected,
                "status": status, "received_status": received_status, "error": error,
                "backend_body": backend_body, "body_valid": body_valid,
                "latency_ms": (finished - scheduled) * 1000,
                "scheduled_unix_ms": (scheduled + self.clock_offset) * 1000,
                "completed_unix_ms": (finished + self.clock_offset) * 1000,
                "service_time_ms": (finished - started) * 1000,
                "dispatch_lag_ms": max(0, (started - scheduled) * 1000)}


def fixed_arrivals(client, pool, rate, seconds, mixed=False, readiness=False):
    target = rate * seconds
    start = time.monotonic() + 0.25
    offset = getattr(client, "clock_offset", time.time() - time.monotonic())
    schedule_start_unix_ms = (start + offset) * 1000
    if readiness:
        marker = {"phase": "resilience", "load_pod_name": client.config["load_pod_name"],
                  "run_label": client.config["load_pod_name"],
                  "schedule_start_unix_ms": schedule_start_unix_ms,
                  "target_rps": rate, "duration_seconds": seconds, "planned_requests": target}
        temporary = Path(READY_MARKER + ".tmp")
        temporary.write_text(json.dumps(marker))
        temporary.replace(READY_MARKER)
    # Bound in-flight + queued work. Saturation cannot turn into an unbounded hidden queue.
    slots = threading.BoundedSemaphore(512)
    results = queue.Queue()
    omitted = 0
    omitted_samples = []
    worker_failures = 0
    futures = []

    def perform(sequence, due, method, credential, expected):
        try:
            results.put(client.request(sequence, due, method,
                                       client.config["load_path"], credential, expected))
        finally:
            slots.release()

    for sequence in range(target):
        due = start + sequence / rate
        delay = due - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        if not slots.acquire(blocking=False):
            omitted += 1
            omitted_samples.append({"sequence": sequence, "scheduled_unix_ms": (due + offset) * 1000,
                                    "reason": "pending-limit"})
            continue
        method, credential, expected = "GET", client.config["api_key"], 200
        if mixed and sequence % 5 == 0:
            credential, expected = "invalid-local-load-credential", 403
        elif mixed and sequence % 5 == 1:
            method = "OPTIONS"
        try:
            futures.append(pool.submit(perform, sequence, due, method, credential, expected))
        except RuntimeError:
            slots.release()
            worker_failures += 1
            omitted += 1
            omitted_samples.append({"sequence": sequence, "scheduled_unix_ms": (due + offset) * 1000,
                                    "reason": "submission-error"})
    # Keep the denominator equal to the planned interval, even if its last request finishes early.
    delay = start + seconds - time.monotonic()
    if delay > 0:
        time.sleep(delay)
    for future in futures:
        try:
            future.result()
        except Exception:
            worker_failures += 1
    samples = []
    while not results.empty():
        samples.append(results.get_nowait())
    samples.sort(key=lambda s: s["sequence"])
    report = aggregate(samples, target, seconds, max(seconds, time.monotonic() - start),
                       omitted, worker_failures)
    report["schedule_start_unix_ms"] = schedule_start_unix_ms
    report["schedule_end_unix_ms"] = schedule_start_unix_ms + seconds * 1000
    report["clock_unix_offset_seconds"] = offset
    report["omitted_samples"] = omitted_samples
    return report


def last_sixty_seconds(observation):
    """Assess arrivals in the final minute, retaining lateness beyond the planned finish."""
    first_sequence = BASELINE_RPS * (RESILIENCE_SECONDS - BASELINE_SECONDS)
    samples = [s for s in observation["samples"] if s["sequence"] >= first_sequence]
    omitted = [s for s in observation["omitted_samples"] if s["sequence"] >= first_sequence]
    start = observation["schedule_end_unix_ms"] - BASELINE_SECONDS * 1000
    completed = max((s["completed_unix_ms"] for s in samples), default=start)
    report = aggregate(samples, BASELINE_RPS * BASELINE_SECONDS, BASELINE_SECONDS,
                       max(BASELINE_SECONDS, (completed - start) / 1000), len(omitted),
                       observation["worker_failures"])
    report["schedule_start_unix_ms"] = start
    report["schedule_end_unix_ms"] = observation["schedule_end_unix_ms"]
    report["omitted_samples"] = omitted
    report["steady_bounds_met_without_fault_alignment"] = baseline_passes(report)
    report["requires_external_fault_window_alignment"] = True
    return report


def quota_burst(client):
    barrier = threading.Barrier(QUOTA_BURST + 1, timeout=20)
    start = None

    def attempt(sequence):
        barrier.wait()
        return client.request(sequence, start, "GET", client.config["quota_path"],
                              client.config["quota_api_key"], 200)

    with concurrent.futures.ThreadPoolExecutor(max_workers=QUOTA_BURST) as pool:
        futures = [pool.submit(attempt, n) for n in range(QUOTA_BURST)]
        start = time.monotonic()
        barrier.wait()
        samples = [future.result() for future in futures]
    report = aggregate(samples, QUOTA_BURST, 1, time.monotonic() - start, 0, 0)
    # Which concurrent request obtains a permit is intentionally nondeterministic.
    report.pop("expected_status_fraction")
    report["expected_status_distribution"] = {"200": QUOTA_ALLOWED,
                                               "429": QUOTA_BURST - QUOTA_ALLOWED}
    for sample in report["samples"]:
        sample["expected"] = "quota-distribution"
    report["passed"] = (report["all_statuses"] == {"200": QUOTA_ALLOWED,
                                                   "429": QUOTA_BURST - QUOTA_ALLOWED}
                        and report["unexpected_body_responses"] == 0
                        and report["transport_errors"] == 0
                        and generator_valid(report))
    return report



def warmup_preconditions(client, pool, report):
    for name, rate in (("priming", 10), ("warmup", BASELINE_RPS)):
        measured = fixed_arrivals(client, pool, rate, 5)
        report[name] = measured
        if (not generator_valid(measured) or measured["expected_status_fraction"] != 1
                or measured["unexpected_body_responses"] != 0):
            report["result"] = "FAIL"
            report["failure_stage"] = report["phase"] + "-" + name
            return False
    return True

def worker(config):
    client = GatewayClient(config)
    report = {"phase": config["phase"], "thresholds": {
        "baseline_rps": BASELINE_RPS, "baseline_seconds": BASELINE_SECONDS,
        "min_achieved_rps": MIN_ACHIEVED_RPS,
        "p95_ms": P95_MS, "p99_ms": P99_MS, "expected_200_fraction": EXPECTED_FRACTION,
        "omitted_schedules": 0, "quota_burst": QUOTA_BURST, "quota_allowed": QUOTA_ALLOWED},
        "latency_origin": "scheduled arrival, including dispatch/queue delay"}
    control = client.request(-1, time.monotonic(), "GET", config["load_path"], config["api_key"], 200)
    report["backend_positive_control"] = control
    passed = control["status"] == 200 and control["body_valid"] and control["error"] is None
    if not passed:
        report["result"] = "FAIL"
        report["failure_stage"] = "initial-backend-positive-control"
        return report
    if config["phase"] == "control":
        report["result"] = "PASS"
        return report
    if config["phase"] == "resilience":
        with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
            if not warmup_preconditions(client, pool, report):
                return report
            observation = fixed_arrivals(client, pool, BASELINE_RPS, RESILIENCE_SECONDS,
                                         readiness=True)
        report["observation"] = observation
        report["last_60_seconds"] = last_sixty_seconds(observation)
        report["assessment"] = "observation completeness and body integrity only; assess fault windows externally"
        report["steady_slo_pass"] = None
        report["result"] = ("OBSERVATION_COMPLETE" if generator_valid(observation)
                            and observation["unexpected_body_responses"] == 0 else "FAIL")
        return report
    if config["phase"] in ("load", "all"):
        with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
            if not warmup_preconditions(client, pool, report):
                return report
            baseline = fixed_arrivals(client, pool, BASELINE_RPS, BASELINE_SECONDS)
            baseline["passed"] = baseline_passes(baseline)
            report["baseline"] = baseline
            passed = passed and baseline["passed"]
            report["characterization"] = []
            for rate in (300, 600):
                measured = fixed_arrivals(client, pool, rate, 30)
                measured["classification"] = "characterization-only-no-performance-pass-claim"
                report["characterization"].append(measured)
                # Saturation/misses are reported; internal lost accounting invalidates evidence.
                passed = passed and measured["unaccounted_schedules"] == 0 and measured["worker_failures"] == 0
            mixed = fixed_arrivals(client, pool, 100, 10, mixed=True)
            mixed["passed"] = (generator_valid(mixed)
                                and mixed["expected_status_fraction"] == 1
                                and mixed["all_statuses"] == {"200": 800, "403": 200})
            report["mixed"] = mixed
            passed = passed and mixed["passed"]
    if config["phase"] in ("quota", "all"):
        report["quota"] = quota_burst(client)
        passed = passed and report["quota"]["passed"]
    report["result"] = "PASS" if passed else "FAIL"
    return report


def kubectl(fixture, arguments, input_text=None):
    return subprocess.run(["kubectl", "--kubeconfig", fixture["kubeconfig"],
                           "--context", fixture["context"], *arguments],
                          input=input_text, text=True, capture_output=True, timeout=400)


def require_success(process, label):
    # Do not propagate kubectl diagnostics: a server may echo request input containing credentials.
    if process.returncode != 0:
        raise RuntimeError(label + " failed (diagnostics suppressed)")
    return process.stdout


def generator_policy(namespace, run_label, gateway_namespace, selector, target_port):
    """Only this invocation's Pod may reach CoreDNS and the exact Gateway workload."""
    return {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
            "metadata": {"name": run_label, "namespace": namespace,
                         "labels": {"fluxgate.io/load-run": run_label}},
            "spec": {"podSelector": {"matchLabels": {"fluxgate.io/load-run": run_label}},
                     "policyTypes": ["Egress"], "egress": [
                         {"to": [{"namespaceSelector": {"matchLabels": {
                             "kubernetes.io/metadata.name": "kube-system"}},
                                  "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
                          "ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]},
                         {"to": [{"namespaceSelector": {"matchLabels": {
                             "kubernetes.io/metadata.name": gateway_namespace}},
                                  "podSelector": {"matchLabels": selector}}],
                          "ports": [{"protocol": "TCP", "port": target_port}]}]}}


def live(args):
    fixture_path = Path(args.fixture).resolve()
    if fixture_path.is_dir():
        fixture_path /= "fixture.json"
    fixture = json.loads(fixture_path.read_text())
    if fixture["context"] != "kind-fluxgate-resilience" or fixture["namespace"] != "fluxgate-resilience":
        raise ValueError("requires isolated kind-fluxgate-resilience fixture")
    namespace = json.loads(require_success(kubectl(fixture, ["get", "namespace", fixture["namespace"],
                                                             "-o", "json"]), "namespace guard"))
    if namespace["metadata"].get("labels", {}).get("fluxgate.io/environment") != "local-ephemeral":
        raise ValueError("requires local-ephemeral namespace label")
    if fixture["gateway_namespace"] != "envoy-gateway-system":
        raise ValueError("requires isolated fixture Gateway namespace")
    service = json.loads(require_success(kubectl(fixture, ["get", "service", fixture["gateway_service"],
                                                          "-n", fixture["gateway_namespace"], "-o", "json"]),
                                        "Gateway identity guard"))
    selector = service["spec"].get("selector", {})
    if (selector.get("gateway.envoyproxy.io/owning-gateway-name") != "fluxgate-resilience-gateway"
            or selector.get("gateway.envoyproxy.io/owning-gateway-namespace") != fixture["namespace"]):
        raise ValueError("Gateway service must select exact isolated fixture workload")
    port = int(fixture.get("gateway_port", 80))
    matching_ports = [p for p in service["spec"]["ports"]
                      if p["port"] == port and p.get("protocol", "TCP") == "TCP"]
    if len(matching_ports) != 1 or not isinstance(matching_ports[0].get("targetPort"), int):
        raise ValueError("requires exact numeric Gateway target port")
    target_port = matching_ports[0]["targetPort"]
    def private_key(field):
        key_path = Path(fixture[field]).resolve()
        if key_path.stat().st_mode & 0o077:
            raise ValueError("API key file must be private (mode 0600 or stricter)")
        value = key_path.read_text().strip()
        if not value or "\n" in value or "\r" in value:
            raise ValueError("invalid private API key file")
        return value

    api_key = private_key("api_key_file")
    quota_key = private_key("quota_api_key_file") if args.phase in ("quota", "all") else None
    config = {"service": fixture["gateway_service"] + "." + fixture["gateway_namespace"]
              + ".svc.cluster.local", "port": port,
              "host": fixture["gateway_host"], "load_path": fixture["load_path"],
              "quota_path": fixture["quota_path"], "api_key": api_key,
              "quota_api_key": quota_key, "phase": args.phase}
    pod_name = "fluxgate-load-" + uuid.uuid4().hex[:12]
    config["load_pod_name"] = pod_name
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {
        "name": pod_name, "namespace": fixture["namespace"],
        "labels": {"app": "fluxgate-load-generator", "fluxgate.io/environment": "local-ephemeral",
                   "fluxgate.io/load-run": pod_name, "fluxgate.io/load-phase": args.phase}},
        "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                 "terminationGracePeriodSeconds": 1,
                 "containers": [{"name": "generator", "image": fixture.get("generator_image", "python:3.12-alpine"),
                                 "command": ["python3", "-c", "import time; time.sleep(7200)"],
                                 "resources": {"requests": {"cpu": "250m", "memory": "64Mi"},
                                               "limits": {"cpu": "2", "memory": "256Mi"}}}]}}
    try:
        policy = generator_policy(fixture["namespace"], pod_name, fixture["gateway_namespace"], selector, target_port)
        require_success(kubectl(fixture, ["create", "-f", "-"], json.dumps(policy)), "generator egress policy")
        require_success(kubectl(fixture, ["create", "-f", "-"], json.dumps(pod)), "generator creation")
        require_success(kubectl(fixture, ["wait", "--for=condition=Ready", "pod/" + pod_name,
                                         "-n", fixture["namespace"], "--timeout=180s"]), "generator readiness")
        process = kubectl(fixture, ["exec", "-i", "-n", fixture["namespace"], pod_name, "--",
                                    "python3", "-c", Path(__file__).read_text(), "--worker"],
                          json.dumps(config))
        output = require_success(process, "in-cluster generator")
        report = json.loads(output)
        # Credentials may never appear in evidence, even if future generator code accidentally logs.
        if api_key in output or (quota_key is not None and quota_key in output):
            raise RuntimeError("credential leaked into generator output; evidence suppressed")
        report["target"] = {"context": fixture["context"], "namespace": fixture["namespace"],
                            "gateway_service": fixture["gateway_service"],
                            "generator_pod": pod_name, "transport": "in-cluster HTTP/1.1 keepalive"}
        return report
    finally:
        failures = []
        for kind in ("pod", "networkpolicy"):
            try:
                found = require_success(kubectl(fixture, ["get", kind, pod_name,
                    "-n", fixture["namespace"], "--ignore-not-found=true", "-o", "json"]),
                    "generator cleanup ownership check")
                if not found.strip():
                    continue
                if json.loads(found)["metadata"].get("labels", {}).get("fluxgate.io/load-run") != pod_name:
                    raise RuntimeError("refusing cleanup of a resource owned by another invocation")
                cleanup = kubectl(fixture, ["delete", kind, pod_name, "-n", fixture["namespace"],
                                            "--ignore-not-found=true", "--wait=true", "--timeout=60s"])
                require_success(cleanup, "generator " + kind + " cleanup")
            except Exception:
                failures.append(kind)
        if failures:
            raise RuntimeError("generator-owned resource cleanup failed")


def self_check():
    sample = {"sequence": 0, "status": 200, "expected": 200, "latency_ms": 10,
              "dispatch_lag_ms": 0, "method": "GET"}
    report = aggregate([dict(sample, sequence=n) for n in range(6000)], 6000, 60, 60, 0, 0)
    assert baseline_passes(report)
    omitted = aggregate(report["samples"][:-1], 6000, 60, 60, 1, 0)
    assert not baseline_passes(omitted)
    lost = aggregate(report["samples"][:-1], 6000, 60, 60, 0, 0)
    assert not baseline_passes(lost)
    failed = dict(report, worker_failures=1)
    assert not baseline_passes(failed)
    slow = aggregate([dict(sample, sequence=n, latency_ms=500) for n in range(6000)],
                     6000, 60, 60, 0, 0)
    assert not baseline_passes(slow)
    errors = aggregate([dict(sample, sequence=n, status="ERROR" if n < 7 else 200)
                        for n in range(6000)], 6000, 60, 60, 0, 0)
    assert not baseline_passes(errors)
    slow_drain = aggregate(
        [dict(s, status="ERROR", error="transport-error", latency_ms=10000)
         if n == 5999 else s for n, s in enumerate(report["samples"])],
        6000, 60, 70, 0, 0)
    assert generator_valid(slow_drain)
    assert slow_drain["expected_status_fraction"] >= EXPECTED_FRACTION
    assert slow_drain["latency_ms"]["p99"] == 10
    assert not baseline_passes(slow_drain)
    wrong_body = aggregate([dict(sample, sequence=n, body_valid=n != 0) for n in range(6000)],
                           6000, 60, 60, 0, 0)
    assert not baseline_passes(wrong_body)

    class TruncatedResponse:
        will_close = False

        def __init__(self, status):
            self.status = status

        def read(self, amount):
            raise http.client.IncompleteRead(b"partial", 128)

    class HeaderOnlyConnection:
        def __init__(self, status):
            self.status = status
            self.closed = False

        def request(self, *args, **kwargs):
            pass

        def getresponse(self):
            return TruncatedResponse(self.status)

        def close(self):
            self.closed = True

    for received in (200, 403, 429):
        client = GatewayClient({"host": "fixture.invalid"})
        connection = HeaderOnlyConnection(received)
        client.local.connection = connection
        truncated = client.request(0, time.monotonic(), "GET", "/fixture", None, received)
        assert truncated["status"] == "ERROR" and truncated["received_status"] == received
        assert truncated["error"] == "transport-error" and connection.closed
        assert client.local.connection is None
        broken = aggregate([truncated], 1, 1, 1, 0, 0)
        assert broken["all_statuses"] == {"ERROR": 1} and broken["expected_status_fraction"] == 0
    mislabeled = aggregate([dict(sample, error="transport-error")], 1, 1, 1, 0, 0)
    assert mislabeled["all_statuses"] == {"ERROR": 1} and mislabeled["expected_status_fraction"] == 0

    class ShortBody(TruncatedResponse):
        length = 128
        read_once = False

        def read(self, amount):
            if self.read_once:
                return b""
            self.read_once = True
            self.length -= len(BACKEND_BODY)
            return BACKEND_BODY

    connection = HeaderOnlyConnection(200)
    connection.getresponse = lambda: ShortBody(200)
    client.local.connection = connection
    truncated = client.request(0, time.monotonic(), "GET", "/fixture", None, 200)
    assert truncated["status"] == "ERROR" and truncated["error"] == "transport-error"
    # Exercise actual HTTP parsing and GatewayClient.request against isolated
    # loopback fixtures; no live Gateway credentials or policy state are used.
    from http.server import BaseHTTPRequestHandler, HTTPServer
    bodies = {"/exact": BACKEND_BODY, "/lf": BACKEND_BODY + b"\n",
              "/space-prefix": b" " + BACKEND_BODY, "/space-suffix": BACKEND_BODY + b" ",
              "/tab-prefix": b"\t" + BACKEND_BODY, "/crlf": BACKEND_BODY + b"\r\n",
              "/double-lf": BACKEND_BODY + b"\n\n", "/prefix": b"prefix" + BACKEND_BODY,
              "/suffix": BACKEND_BODY + b"suffix", "/oversized": BACKEND_BODY + b"x" * 1024,
              "/truncated": BACKEND_BODY}
    class BodyFixture(BaseHTTPRequestHandler):
        def do_GET(self):
            body = bodies[self.path]
            self.send_response(200)
            self.send_header("Content-Length", str(len(body) + (7 if self.path == "/truncated" else 0)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *arguments):
            pass
    server = HTTPServer(("127.0.0.1", 0), BodyFixture)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    body_checks = 0
    client = GatewayClient({"service": "127.0.0.1", "port": server.server_port, "host": "offline.invalid"})
    try:
        for path in bodies:
            response = client.request(0, time.monotonic(), "GET", path, None, 200)
            if path in ("/exact", "/lf"):
                assert response["status"] == 200 and response["backend_body"] and response["body_valid"]
            elif path == "/truncated":
                assert response["status"] == "ERROR" and response["error"] == "transport-error"
                assert not response["backend_body"] and client.local.connection is None
            else:
                assert response["status"] == 200 and response["error"] is None
                assert response["backend_body"] is False and response["body_valid"] is False, path
                assert aggregate([response], 1, 1, 1, 0, 0)["unexpected_body_responses"] == 1
            body_checks += 1
    finally:
        connection = getattr(client.local, "connection", None)
        if connection is not None:
            connection.close()
        server.shutdown()
        server.server_close()
        server_thread.join(timeout=2)
        assert not server_thread.is_alive()
    original_client = GatewayClient

    class FailedControlClient:
        def __init__(self, config):
            pass

        def request(self, *args):
            return {"status": "ERROR", "body_valid": True, "error": "transport-error"}

    try:
        globals()["GatewayClient"] = FailedControlClient
        stopped = worker({"phase": "all", "load_path": "/load", "api_key": "offline-placeholder"})
        assert stopped["result"] == "FAIL" and stopped["failure_stage"] == "initial-backend-positive-control"
        assert not any(key in stopped for key in ("warmup", "baseline", "characterization", "mixed", "quota"))
    finally:
        globals()["GatewayClient"] = original_client
    policy = generator_policy("fluxgate-resilience", "unique-run", "envoy-gateway-system",
                              {"owning-gateway": "isolated-gateway"}, 10080)
    assert policy["spec"]["podSelector"] == {"matchLabels": {"fluxgate.io/load-run": "unique-run"}}
    assert policy["spec"]["policyTypes"] == ["Egress"]
    assert {p["port"] for rule in policy["spec"]["egress"] for p in rule["ports"]} == {53, 10080}
    assert all("namespaceSelector" in target and "podSelector" in target
               for rule in policy["spec"]["egress"] for target in rule["to"])
    wall_start = 1700000000000
    observation = aggregate([
        dict(sample, sequence=n, status=503 if n < 6000 else 200,
             scheduled_unix_ms=wall_start + n * 10,
             completed_unix_ms=wall_start + n * 10 + 10)
        for n in range(12000)], 12000, 120, 120, 0, 0)
    observation.update(schedule_start_unix_ms=wall_start,
                       schedule_end_unix_ms=wall_start + 120000, omitted_samples=[])
    tail = last_sixty_seconds(observation)
    assert tail["target_requests"] == 6000 and tail["all_statuses"] == {"200": 6000}
    assert tail["steady_bounds_met_without_fault_alignment"]
    assert tail["requires_external_fault_window_alignment"]
    omitted_tail = dict(observation, samples=observation["samples"][:-1],
                        omitted_samples=[{"sequence": 11999, "scheduled_unix_ms": wall_start + 119990}])
    assert not last_sixty_seconds(omitted_tail)["steady_bounds_met_without_fault_alignment"]
    slow_tail_samples = list(observation["samples"])
    slow_tail_samples[-1] = dict(slow_tail_samples[-1], completed_unix_ms=wall_start + 130000,
                                 status="ERROR", error="transport-error", latency_ms=10010)
    slow_tail = last_sixty_seconds(dict(observation, samples=slow_tail_samples))
    assert slow_tail["expected_status_fraction"] >= EXPECTED_FRACTION
    assert slow_tail["latency_ms"]["p99"] == 10
    assert slow_tail["achieved_rps"] < MIN_ACHIEVED_RPS
    assert not slow_tail["steady_bounds_met_without_fault_alignment"]
    assert truncated["completed_unix_ms"] >= truncated["scheduled_unix_ms"]
    original_marker = READY_MARKER

    class OfflineTimedClient:
        config = {"load_path": "/offline", "api_key": "offline-placeholder", "load_pod_name": "offline-pod"}
        clock_offset = time.time() - time.monotonic()

        def request(self, sequence, due, method, path, credential, expected):
            return dict(sample, sequence=sequence, method=method, expected=expected,
                        scheduled_unix_ms=(due + self.clock_offset) * 1000,
                        completed_unix_ms=(time.monotonic() + self.clock_offset) * 1000)

    try:
        with tempfile.TemporaryDirectory(prefix="fluxgate-load-offline-") as directory:
            globals()["READY_MARKER"] = str(Path(directory) / "ready.json")
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                timed = fixed_arrivals(OfflineTimedClient(), pool, 10, 1, readiness=True)
            marker = json.loads(Path(READY_MARKER).read_text())
            assert marker["load_pod_name"] == "offline-pod" and marker["planned_requests"] == 10
            assert marker["schedule_start_unix_ms"] == timed["schedule_start_unix_ms"]
            assert marker["schedule_start_unix_ms"] == timed["samples"][0]["scheduled_unix_ms"]
            assert generator_valid(timed) and len(timed["samples"]) == 10
            assert not Path(READY_MARKER + ".tmp").exists()
    finally:
        globals()["READY_MARKER"] = original_marker
    original_client, original_arrivals = GatewayClient, fixed_arrivals
    pacing_checks = 0
    try:
        class SuccessfulControlClient:
            def __init__(self, config):
                self.config = config
            def request(self, *arguments):
                return dict(sample, status=200, error=None, body_valid=True, backend_body=True)
        globals()["GatewayClient"] = SuccessfulControlClient
        for phase, failure in (("resilience", None), ("resilience", "priming-status"),
                               ("resilience", "warmup-body"), ("load", "priming-omission"),
                               ("all", "warmup-status")):
            calls = []
            def fake_arrivals(client, pool, rate, seconds, mixed=False, readiness=False):
                calls.append((rate, seconds, readiness))
                assert pool._max_workers == 128
                target = rate * seconds
                samples = [dict(sample, sequence=n, error=None, backend_body=True, body_valid=True,
                                scheduled_unix_ms=wall_start + n * 1000 / rate,
                                completed_unix_ms=wall_start + n * 1000 / rate + 10)
                           for n in range(target)]
                name = "priming" if len(calls) == 1 else "warmup"
                omitted_count = 0
                if failure == name + "-status":
                    samples[0].update(status=503, backend_body=False)
                elif failure == name + "-body":
                    samples[0].update(body_valid=False, backend_body=False)
                elif failure == name + "-omission":
                    samples.pop(); omitted_count = 1
                measured = aggregate(samples, target, seconds, seconds, omitted_count, 0)
                measured.update(schedule_start_unix_ms=wall_start,
                                schedule_end_unix_ms=wall_start + seconds * 1000, omitted_samples=[])
                return measured
            globals()["fixed_arrivals"] = fake_arrivals
            result = worker({"phase": phase, "load_path": "/offline", "api_key": "offline"})
            assert calls[0] == (10, 5, False)
            assert result["priming"]["target_requests"] == 50
            if failure:
                name = failure.split('-')[0]
                assert result["result"] == "FAIL" and result["failure_stage"] == phase + "-" + name
                assert len(calls) == (1 if name == "priming" else 2)
                assert not any(key in result for key in ("observation", "baseline", "mixed", "quota", "characterization"))
                assert not any(call[2] for call in calls)
            else:
                assert result["result"] == "OBSERVATION_COMPLETE"
                assert calls == [(10, 5, False), (100, 5, False), (100, 120, True)]
                assert result["warmup"]["target_requests"] == 500 and result["observation"]["target_requests"] == 12000
            assert result["thresholds"] == {"baseline_rps": 100, "baseline_seconds": 60,
                "min_achieved_rps": 99, "p95_ms": 100, "p99_ms": 250, "expected_200_fraction": .999,
                "omitted_schedules": 0, "quota_burst": 100, "quota_allowed": 5}
            pacing_checks += 1
    finally:
        globals()["GatewayClient"], globals()["fixed_arrivals"] = original_client, original_arrivals
    assert percentile([1, 2, 3, 4, 5], 0.95) == 5
    print(json.dumps({"result": "PASS", "self_checks": 21 + body_checks + pacing_checks}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture")
    parser.add_argument("--phase", choices=("control", "load", "quota", "all", "resilience"), default="load")
    parser.add_argument("--output", help="sanitized JSON output, written only after complete PASS")
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        print(json.dumps(worker(json.load(sys.stdin))))
        return 0
    if args.self_check:
        self_check()
        return 0
    if not args.fixture:
        parser.error("--fixture is required for live proof")
    report = live(args)
    encoded = json.dumps(report, indent=2)
    print(encoded)
    successful = report["result"] in ("PASS", "OBSERVATION_COMPLETE")
    if successful and args.output:
        Path(args.output).write_text(encoded + "\n")
    return 0 if successful else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as failure:
        # Exception text can contain headers/addresses supplied by third-party responses.
        print(json.dumps({"result": "FAIL", "error_type": type(failure).__name__,
                          "message": "proof aborted; sensitive diagnostics suppressed"}))
        sys.exit(1)
