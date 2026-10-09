#!/usr/bin/env python3
"""Opt-in real Gateway load proof; all dependencies are Python standard library.

Run --self-check without a cluster. Live --fixture accepts the private fixture.json or
its directory. --phase load (default) never consumes the dedicated quota; root must
serialize --phase quota against the other proofs. The in-cluster Python generator
uses HTTP/1.1 keepalive, bypasses kubectl/port-forward on the measured request path,
and receives credential contents through stdin only. No notifier or storage write
is performed. Acceptance bounds are fixed below, before any measurement.
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
import threading
import time
import uuid


BASELINE_RPS = 100
BASELINE_SECONDS = 60
P95_MS = 100
P99_MS = 250
EXPECTED_FRACTION = 0.999
QUOTA_BURST = 100
QUOTA_ALLOWED = 5


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
        status = str(sample["status"])
        statuses[status] = statuses.get(status, 0) + 1
        kind = "invalid-key" if sample["expected"] == 403 else sample["method"]
        counts = distributions.setdefault(kind, {})
        counts[status] = counts.get(status, 0) + 1
        expected += sample["status"] == sample["expected"]
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
            and report["expected_status_fraction"] >= EXPECTED_FRACTION
            and report["latency_ms"]["p95"] is not None
            and report["latency_ms"]["p95"] <= P95_MS
            and report["latency_ms"]["p99"] <= P99_MS)


class GatewayClient:
    def __init__(self, config):
        self.config = config
        self.local = threading.local()

    def request(self, sequence, scheduled, method, path, credential, expected):
        started = time.monotonic()
        status = "ERROR"
        error = None
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
            # Fully drain the body before reusing the connection, including denied responses.
            while response.read(65536):
                pass
            if response.will_close:
                connection.close()
                self.local.connection = None
        except (OSError, http.client.HTTPException):
            # No automatic retry: retries would hide real errors and alter quota consumption.
            error = "transport-error"
            if connection is not None:
                connection.close()
            self.local.connection = None
        finished = time.monotonic()
        return {"sequence": sequence, "method": method, "expected": expected,
                "status": status, "error": error,
                "latency_ms": (finished - scheduled) * 1000,
                "service_time_ms": (finished - started) * 1000,
                "dispatch_lag_ms": max(0, (started - scheduled) * 1000)}


def fixed_arrivals(client, pool, rate, seconds, mixed=False):
    target = rate * seconds
    start = time.monotonic() + 0.25
    # Bound in-flight + queued work. Saturation cannot turn into an unbounded hidden queue.
    slots = threading.BoundedSemaphore(512)
    results = queue.Queue()
    omitted = 0
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
    return aggregate(samples, target, seconds, max(seconds, time.monotonic() - start),
                     omitted, worker_failures)


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
                        and generator_valid(report))
    return report


def worker(config):
    client = GatewayClient(config)
    report = {"phase": config["phase"], "thresholds": {
        "baseline_rps": BASELINE_RPS, "baseline_seconds": BASELINE_SECONDS,
        "p95_ms": P95_MS, "p99_ms": P99_MS, "expected_200_fraction": EXPECTED_FRACTION,
        "omitted_schedules": 0, "quota_burst": QUOTA_BURST, "quota_allowed": QUOTA_ALLOWED},
        "latency_origin": "scheduled arrival, including dispatch/queue delay"}
    passed = True
    if config["phase"] in ("load", "all"):
        with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
            warmup = fixed_arrivals(client, pool, 100, 5)
            report["warmup"] = warmup
            baseline = fixed_arrivals(client, pool, BASELINE_RPS, BASELINE_SECONDS)
            baseline["passed"] = baseline_passes(baseline)
            report["baseline"] = baseline
            passed = (generator_valid(warmup) and warmup["expected_status_fraction"] == 1
                      and baseline["passed"])
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
              + ".svc.cluster.local", "port": int(fixture.get("gateway_port", 80)),
              "host": fixture["gateway_host"], "load_path": fixture["load_path"],
              "quota_path": fixture["quota_path"], "api_key": api_key,
              "quota_api_key": quota_key, "phase": args.phase}
    pod_name = "fluxgate-load-" + uuid.uuid4().hex[:12]
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {
        "name": pod_name, "namespace": fixture["namespace"],
        "labels": {"app": "fluxgate-load-generator", "fluxgate.io/environment": "local-ephemeral"}},
        "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                 "containers": [{"name": "generator", "image": fixture.get("generator_image", "python:3.12-alpine"),
                                 "command": ["python3", "-c", "import time; time.sleep(7200)"],
                                 "resources": {"requests": {"cpu": "250m", "memory": "64Mi"},
                                               "limits": {"cpu": "2", "memory": "256Mi"}}}]}}
    try:
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
        cleanup = kubectl(fixture, ["delete", "pod", pod_name, "-n", fixture["namespace"],
                                    "--ignore-not-found=true", "--wait=true", "--timeout=60s"])
        require_success(cleanup, "generator cleanup")


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
    assert percentile([1, 2, 3, 4, 5], 0.95) == 5
    print(json.dumps({"result": "PASS", "self_checks": 7}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture")
    parser.add_argument("--phase", choices=("load", "quota", "all"), default="load")
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
    if report["result"] == "PASS" and args.output:
        Path(args.output).write_text(encoded + "\n")
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as failure:
        # Exception text can contain headers/addresses supplied by third-party responses.
        print(json.dumps({"result": "FAIL", "error_type": type(failure).__name__,
                          "message": "proof aborted; sensitive diagnostics suppressed"}))
        sys.exit(1)
