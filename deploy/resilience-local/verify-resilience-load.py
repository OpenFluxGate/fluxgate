#!/usr/bin/env python3
"""Coordinate one isolated primary fault under timestamped open-loop traffic.

Run --self-check offline. Live --fixture --fault redis|mongo --proof DIR requires
an empty/new private proof directory and exclusive root scheduling. Raw load/HA
proofs and logs remain private; the compact summary contains their hashes and
counts. OBSERVATION_COMPLETE alone never establishes recovery or steady SLOs.
"""

import argparse
import copy
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import time


HERE = Path(__file__).resolve().parent


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def milestone(value):
    require(isinstance(value, dict) and "unix_ms" in value and "utc" in value,
            "missing actual UTC milestone")
    utc = datetime.fromisoformat(value["utc"].replace("Z", "+00:00"))
    require(utc.utcoffset() is not None, "milestone timezone absent")
    require(abs(utc.timestamp() * 1000 - value["unix_ms"]) <= 1, "inconsistent UTC milestone")
    return float(value["unix_ms"])


def assess(load, ha, fault, clock):
    """Pure evaluator. Reject evidence instead of filling absent milestones/requests."""
    rules = runpy.run_path(str(HERE / "verify-load.py"))
    require(load.get("phase") == "resilience" and load.get("result") == "OBSERVATION_COMPLETE",
            "load observation incomplete")
    require(ha.get("result") == "pass" and ha.get("phase") == fault
            and ha.get("phase_complete") is True and ha.get("complete") is False,
            "fault phase must be successful and explicitly partial")
    name = "redis-promotion" if fault == "redis" else "mongo-election-publication"
    checks = [c for c in ha.get("checks", []) if c.get("check") == name]
    require(len(checks) == 1, "missing or ambiguous primary fault evidence")
    check = checks[0]
    require(check.get("target_still_paused") is True, "recovery not verified while primary paused")
    stamps = check.get("timestamps", {})
    names = ["rto_budget_started", "fault_start", "paused_verified", "sustained_gateway_recovered"]
    names += ["quota_verified"] if fault == "redis" else ["publication_verified", "policy_and_traffic_verified"]
    times = {name: milestone(stamps.get(name)) for name in names}
    budget, start, paused, sustained = [times[name] for name in names[:4]]
    verified = times["quota_verified" if fault == "redis" else "policy_and_traffic_verified"]
    require(budget <= start <= paused <= sustained <= verified, "fault timeline out of order")
    if fault == "mongo":
        require(paused <= times["publication_verified"] <= sustained, "publication not verified before recovered traffic")
    rto = check.get("final_quota_seconds" if fault == "redis" else "recovery_seconds")
    require(isinstance(rto, (int, float)) and 0 <= rto <= 30, "monotonic RTO exceeds 30 seconds")
    require(0 <= verified - budget <= 30000, "verified UTC RTO exceeds 30 seconds")
    require(len(check.get("sustained_gateway", [])) == 3
            and all(s.get("status") == 200 for s in check["sustained_gateway"]),
            "missing sustained recovery responses")
    offset = clock["pod_minus_host_estimate_ms"]
    uncertainty = clock["measurement_bound_ms"]
    require(isinstance(offset, (int, float)) and isinstance(uncertainty, (int, float))
            and 0 <= uncertainty <= 2000, "clock measurement absent or too uncertain")
    observation = load["observation"]
    require(observation.get("target_requests") == 12000 and observation.get("measurement_seconds") == 120,
            "requires complete 100 RPS for 120 seconds")
    require(rules["generator_valid"](observation), "omitted, lost or failed generator work")
    samples = observation["samples"]
    require(len(samples) == 12000 and {s["sequence"] for s in samples} == set(range(12000)),
            "duplicate or missing arrival sequences")
    schedule_start = observation["schedule_start_unix_ms"] - offset
    schedule_end = observation["schedule_end_unix_ms"] - offset
    require(abs(schedule_end - schedule_start - 120000) <= 1, "wrong observation interval")
    require(schedule_start + uncertainty <= budget and verified + uncertainty < schedule_end - 60000,
            "fault not contained or verified recovery overlaps final minute")
    failures = []
    for sample in samples:
        scheduled = sample["scheduled_unix_ms"] - offset
        completed = sample["completed_unix_ms"] - offset
        require(abs(scheduled - (schedule_start + sample["sequence"] * 10)) <= 1,
                "arrival is not on the preset schedule")
        require(completed >= scheduled, "completion precedes arrival")
        require(abs(sample["latency_ms"] - (completed - scheduled)) <= 1,
                "latency does not include the scheduled arrival")
        require(sample.get("method") == "GET" and sample.get("expected") == 200,
                "unexpected resilience request class")
        require(sample.get("body_valid") is True, "unexpected response body")
        status = sample.get("status")
        error = sample.get("error")
        if status == 200 and error is None:
            require(sample.get("backend_body") is True, "200 did not reach expected backend")
            continue
        require((status == 503 and error is None)
                or (status == "ERROR" and error == "transport-error"), "unexpected fault status")
        require(status != 503 or sample.get("backend_body") is False, "denied response reached backend")
        require(scheduled <= verified and completed >= start, "failure outside actual fault window")
        failures.append(sample)
    # Recompute from raw samples: supplied counters/last-minute summaries cannot hide losses.
    actual_elapsed = max(120, (max(s["completed_unix_ms"] for s in samples)
                              - observation["schedule_start_unix_ms"]) / 1000)
    require(observation["elapsed_including_drain_seconds"] >= actual_elapsed - 0.001,
            "drain interval excluded late completions")
    recomputed = rules["aggregate"](samples, 12000, 120,
        observation["elapsed_including_drain_seconds"], len(observation.get("omitted_samples", [])), 0)
    for field in ("all_statuses", "completed_requests", "transport_errors", "unexpected_body_responses"):
        require(recomputed[field] == observation[field], "forged load aggregation")
    tail = rules["last_sixty_seconds"](observation)
    require(tail["target_requests"] == 6000 and rules["baseline_passes"](tail),
            "final minute fails original steady-state bounds")
    return {"passed": True, "complete": False, "fault": fault, "phase_complete": True,
            "fault_timestamps": stamps, "verified_rto_monotonic_seconds": rto,
            "verified_rto_utc_seconds": (verified - budget) / 1000,
            "clock": clock, "observation_requests": len(samples),
            "all_statuses": recomputed["all_statuses"], "fault_window_failures": len(failures),
            "last_minute": {k: tail[k] for k in ("target_requests", "completed_requests", "achieved_rps",
                "all_statuses", "latency_ms", "omitted_schedules", "unaccounted_schedules", "worker_failures")},
            "assessment": "Steady bounds verified only after actual primary recovery; no across-fault steady SLO claim"}


def kube(fixture, arguments):
    process = subprocess.run(["kubectl", "--kubeconfig", fixture["kubeconfig"], "--context",
        fixture["context"], "-n", fixture["namespace"], *arguments], capture_output=True, text=True, timeout=15)
    require(process.returncode == 0, "coordination kubectl failed; private diagnostics suppressed")
    return process.stdout


def clock_measurement(fixture, pod):
    before = time.time_ns() / 1000000
    pod_ms = int(kube(fixture, ["exec", pod, "--", "python3", "-c", "import time; print(time.time_ns())"])) / 1000000
    after = time.time_ns() / 1000000
    low, high = pod_ms - after, pod_ms - before
    return {"host_before_unix_ms": before, "host_after_unix_ms": after, "pod_unix_ms": pod_ms,
            "pod_minus_host_interval_ms": [low, high], "pod_minus_host_estimate_ms": (low + high) / 2,
            "measurement_bound_ms": (high - low) / 2, "within_100ms": max(abs(low), abs(high)) <= 100}


def digest(path):
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def coordinate(args):
    os.umask(0o077)
    proof = args.proof.resolve()
    require(not proof.exists() or (proof.is_dir() and not any(proof.iterdir())), "proof directory must be fresh/empty")
    proof.mkdir(mode=0o700, parents=True, exist_ok=True)
    proof.chmod(0o700)
    fixture_file = args.fixture / "fixture.json" if args.fixture.is_dir() else args.fixture
    fixture = json.loads(fixture_file.read_text())
    require(fixture["context"] == "kind-fluxgate-resilience" and fixture["namespace"] == "fluxgate-resilience",
            "requires isolated resilience fixture")
    processes = []
    handles = []
    summary = {"passed": False, "complete": False, "fault": args.fault}
    try:
        active = json.loads(kube(fixture, ["get", "pods", "-l", "fluxgate.io/load-phase=resilience", "-o", "json"]))
        require(not active["items"], "another resilience generator is active")
        if args.fault == "redis":
            with (proof / "prepare.log").open("w") as log:
                prepared = subprocess.run([sys.executable, str(HERE / "prepare-ha-identity.py"),
                    "--fixture", str(fixture_file), "--output", str(proof / "identity.json")],
                    stdout=log, stderr=log, timeout=300)
            require(prepared.returncode == 0, "fresh HA identity preparation failed")
            identity = json.loads((proof / "identity.json").read_text())
            require(identity.get("passed") is True and identity.get("policy_pointer_unchanged") is True
                    and identity.get("existing_counters_deleted_or_reset") is False, "identity preparation changed policy/counters")
        log = (proof / "load.log").open("w"); handles.append(log)
        load = subprocess.Popen([sys.executable, str(HERE / "verify-load.py"), "--fixture", str(fixture_file),
            "--phase", "resilience", "--output", str(proof / "raw-load.json")], stdout=log, stderr=log)
        processes.append(("load", load))
        deadline = time.monotonic() + 60
        marker = None
        while time.monotonic() < deadline:
            require(load.poll() is None, "load exited before readiness marker")
            pods = json.loads(kube(fixture, ["get", "pods", "-l", "fluxgate.io/load-phase=resilience", "-o", "json"]))
            require(len(pods["items"]) <= 1, "ambiguous resilience generator")
            if pods["items"]:
                pod = pods["items"][0]
                name = pod["metadata"]["name"]
                require(pod["metadata"]["labels"].get("fluxgate.io/load-run") == name, "generator ownership mismatch")
                # cat returns a sentinel until the atomic marker exists; no credentials in arguments.
                if pod["status"].get("phase") == "Running":
                    text = kube(fixture, ["exec", name, "--", "python3", "-c",
                        "from pathlib import Path; p=Path('/tmp/fluxgate-load-ready.json'); print(p.read_text() if p.exists() else 'null')"])
                    marker = json.loads(text)
                    if marker is not None:
                        require(marker.get("load_pod_name") == name and marker.get("run_label") == name
                                and marker.get("phase") == "resilience" and marker.get("planned_requests") == 12000,
                                "wrong readiness marker")
                        require(marker.get("target_rps") == 100 and marker.get("duration_seconds") == 120,
                                "wrong readiness schedule")
                        break
            time.sleep(0.25)
        require(marker is not None, "load readiness marker timed out")
        clock = clock_measurement(fixture, name)
        (proof / "readiness.json").write_text(json.dumps({"marker": marker, "clock": clock}, indent=2) + "\n")
        log = (proof / "ha.log").open("w"); handles.append(log)
        ha = subprocess.Popen([sys.executable, str(HERE / "verify-ha.py"), "--fixture", str(fixture_file),
            "--phase", args.fault, "--proof", str(proof / "ha")], stdout=log, stderr=log)
        processes.append(("ha", ha))
        ha_code = ha.wait(timeout=600)
        load_code = load.wait(timeout=400)
        require(ha_code == 0 and load_code == 0, "load or fault subprocess failed")
        raw_load, raw_ha = proof / "raw-load.json", proof / "ha" / "ha.json"
        load_data = json.loads(raw_load.read_text())
        require(load_data["target"]["generator_pod"] == name
                and load_data["observation"]["schedule_start_unix_ms"] == marker["schedule_start_unix_ms"],
                "raw load does not match readiness invocation")
        summary = assess(load_data, json.loads(raw_ha.read_text()), args.fault, clock)
        summary["raw_proofs"] = {"load": digest(raw_load), "ha": digest(raw_ha), "readiness": digest(proof / "readiness.json")}
    except Exception as error:
        summary["failure"] = str(error) if isinstance(error, ValueError) else type(error).__name__
    finally:
        # Do not kill a fault subprocess: its finally block must restore the real primary.
        for _, process in processes:
            if process.poll() is None:
                try:
                    process.wait(timeout=600)
                except subprocess.TimeoutExpired:
                    summary["passed"] = False
                    summary["incomplete_subprocess_retained"] = True
        summary["processes"] = [{"name": name, "pid": p.pid, "exit_code": p.poll()} for name, p in processes]
        for handle in handles:
            handle.close()
        # verify-load intentionally does not publish successful output on failure. Preserve
        # its complete JSON stdout as raw failed evidence when available, never mark it passed.
        raw_load = proof / "raw-load.json"
        if not raw_load.exists() and (proof / "load.log").exists():
            try:
                failed_load = json.loads((proof / "load.log").read_text())
                raw_load.write_text(json.dumps(failed_load, indent=2) + "\n")
            except (ValueError, OSError):
                pass
        if "raw_proofs" not in summary:
            retained = {"load": raw_load, "ha": proof / "ha" / "ha.json",
                        "ha_failure": proof / "ha" / "failure.json", "readiness": proof / "readiness.json"}
            summary["raw_proofs"] = {name: digest(path) for name, path in retained.items() if path.exists()}
        (proof / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0 if summary["passed"] else 1


def self_check():
    rules = runpy.run_path(str(HERE / "verify-load.py"))
    start = 1700000000000
    def stamp(ms):
        return {"unix_ms": ms, "utc": datetime.fromtimestamp(ms / 1000).astimezone().isoformat()}
    samples = [{"sequence": n, "status": 200, "expected": 200, "method": "GET", "error": None,
                "body_valid": True, "backend_body": True, "latency_ms": 10, "dispatch_lag_ms": 0,
                "scheduled_unix_ms": start + n * 10, "completed_unix_ms": start + n * 10 + 10}
               for n in range(12000)]
    observation = rules["aggregate"](samples, 12000, 120, 120, 0, 0)
    observation.update(schedule_start_unix_ms=start, schedule_end_unix_ms=start + 120000, omitted_samples=[])
    load = {"phase": "resilience", "result": "OBSERVATION_COMPLETE", "observation": observation}
    timestamps = {name: stamp(start + ms) for name, ms in {
        "rto_budget_started": 10000, "fault_start": 10010, "paused_verified": 11000,
        "sustained_gateway_recovered": 14000, "quota_verified": 15000}.items()}
    ha = {"result": "pass", "phase": "redis", "phase_complete": True, "complete": False,
          "checks": [{"check": "redis-promotion", "target_still_paused": True, "timestamps": timestamps,
                      "final_quota_seconds": 5, "sustained_gateway": [{"status": 200}] * 3}]}
    clock = {"pod_minus_host_estimate_ms": 0, "measurement_bound_ms": 1}
    assert assess(load, ha, "redis", clock)["passed"]
    during = copy.deepcopy(load)
    during["observation"]["samples"][1200].update(status=503, backend_body=False)
    during["observation"]["all_statuses"] = {"200": 11999, "503": 1}
    assert assess(during, ha, "redis", clock)["fault_window_failures"] == 1
    mongo = copy.deepcopy(ha); mongo["phase"] = "mongo"
    check = mongo["checks"][0]; check["check"] = "mongo-election-publication"
    check["recovery_seconds"] = 4
    del check["final_quota_seconds"]
    check["timestamps"]["publication_verified"] = stamp(start + 13000)
    check["timestamps"]["policy_and_traffic_verified"] = stamp(start + 15000)
    del check["timestamps"]["quota_verified"]
    assert assess(load, mongo, "mongo", clock)["passed"]
    invalid = []
    missing = copy.deepcopy(ha); del missing["checks"][0]["timestamps"]["fault_start"]
    invalid.append((load, missing))
    late = copy.deepcopy(ha); late["checks"][0]["timestamps"]["quota_verified"] = stamp(start + 61000)
    late["checks"][0]["final_quota_seconds"] = 51
    invalid.append((load, late))
    overlap = copy.deepcopy(ha)
    overlap["checks"][0]["timestamps"] = {name: stamp(start + ms) for name, ms in {
        "rto_budget_started": 50000, "fault_start": 50010, "paused_verified": 51000,
        "sustained_gateway_recovered": 64000, "quota_verified": 65000}.items()}
    overlap["checks"][0]["final_quota_seconds"] = 15
    invalid.append((load, overlap))
    for index, changes in [(0, {"body_valid": False}), (0, {"backend_body": False}), (9000, {"status": 503}),
                           (9000, {"status": 403}), (9000, {"status": 429}),
                           (11999, {"latency_ms": 10010, "completed_unix_ms": start + 130000})]:
        changed = copy.deepcopy(load); changed["observation"]["samples"][index].update(changes)
        if index == 11999:
            changed["observation"]["elapsed_including_drain_seconds"] = 130
        invalid.append((changed, ha))
    outside_transport = copy.deepcopy(load)
    outside_transport["observation"]["samples"][9000].update(status="ERROR", error="transport-error")
    invalid.append((outside_transport, ha))
    p95 = copy.deepcopy(load)
    for sample in p95["observation"]["samples"][6000:6600]:
        sample.update(latency_ms=200, completed_unix_ms=sample["scheduled_unix_ms"] + 200)
    invalid.append((p95, ha))
    lost = copy.deepcopy(load); lost["observation"]["samples"].pop()
    invalid.append((lost, ha))
    for bad_load, bad_ha in invalid:
        try:
            assess(bad_load, bad_ha, "redis", clock)
        except (ValueError, KeyError):
            continue
        raise AssertionError("invalid fault/load proof accepted")
    print(json.dumps({"result": "PASS", "self_checks": len(invalid) + 3}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--fault", choices=("redis", "mongo"))
    parser.add_argument("--proof", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not (args.fixture and args.fault and args.proof):
        parser.error("--fixture, --fault and fresh --proof are required")
    return coordinate(args)


if __name__ == "__main__":
    sys.exit(main())
