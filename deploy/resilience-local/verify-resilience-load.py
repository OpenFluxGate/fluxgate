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
import signal
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


class ReadinessFailure(ValueError):
    def __init__(self, reason, stage="load_readiness"):
        super().__init__(reason)
        self.stage = stage


def wait_readiness(fixture, load, query=kube, now=time.monotonic, sleep=time.sleep):
    """A missing atomic marker is pending; API/exec failures are genuine failures."""
    deadline = now() + 60
    identity = None
    while now() < deadline:
        if load.poll() is not None:
            raise ReadinessFailure("load exited before readiness marker", "load_warmup")
        pods = json.loads(query(fixture, ["get", "pods", "-l", "fluxgate.io/load-phase=resilience", "-o", "json"]))
        require(len(pods["items"]) <= 1, "ambiguous resilience generator")
        if not pods["items"]:
            require(identity is None, "owned generator disappeared before readiness")
        else:
            pod = pods["items"][0]
            metadata = pod["metadata"]
            name = metadata["name"]
            labels = metadata.get("labels", {})
            require(labels.get("fluxgate.io/load-run") == name
                    and labels.get("fluxgate.io/load-phase") == "resilience"
                    and labels.get("app") == "fluxgate-load-generator"
                    and labels.get("fluxgate.io/environment") == "local-ephemeral",
                    "generator ownership mismatch")
            current = (name, metadata.get("uid"), metadata.get("creationTimestamp"))
            require(all(current), "generator identity absent")
            require(identity is None or current == identity, "owned generator identity changed")
            identity = current
            require(not metadata.get("deletionTimestamp"), "owned generator is being deleted")
            phase = pod.get("status", {}).get("phase")
            require(phase not in ("Failed", "Succeeded"), "generator terminated before readiness")
            if phase == "Running":
                value = query(fixture, ["exec", name, "--", "python3", "-c",
                    "from pathlib import Path; p=Path('/tmp/fluxgate-load-ready.json'); print(p.read_text() if p.exists() else 'null')"])
                marker = json.loads(value)
                if marker is not None:
                    require(marker.get("load_pod_name") == name and marker.get("run_label") == name
                            and marker.get("phase") == "resilience" and marker.get("planned_requests") == 12000,
                            "wrong readiness marker")
                    require(marker.get("target_rps") == 100 and marker.get("duration_seconds") == 120,
                            "wrong readiness schedule")
                    if load.poll() is not None:
                        raise ReadinessFailure("load exited while reading readiness marker", "load_warmup")
                    # Re-read identity before authorizing a fault against this observation.
                    confirmed = json.loads(query(fixture, ["get", "pod", name, "-o", "json"]))
                    final = confirmed.get("metadata", {})
                    require((final.get("name"), final.get("uid"), final.get("creationTimestamp")) == identity
                            and final.get("labels") == labels and not final.get("deletionTimestamp"),
                            "owned generator identity or ownership changed during readiness")
                    return name, marker, {"name": name, "uid": identity[1], "creationTimestamp": identity[2]}
        sleep(0.25)
    raise ReadinessFailure("load readiness marker timed out")


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



def stop_preparation_group(process):
    """Only preparation children have their own session; never stop HA cleanup."""
    for signum in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, signum)
        except ProcessLookupError:
            pass
        if signum == signal.SIGTERM:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                continue
            # The session leader may exit before a hung kubectl descendant.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            return
        process.wait(timeout=5)


def prepare_owned(arguments, log, processes, name, evidence, timeout=300):
    evidence["started_unix_ms"] = time.time_ns() // 1000000
    process = subprocess.Popen(arguments, stdout=log, stderr=log, start_new_session=True)
    processes.append((name, process))
    evidence["pid"] = process.pid
    try:
        code = process.wait(timeout=timeout)
        require(code == 0, name + " failed")
    except BaseException:
        stop_preparation_group(process)
        raise
    finally:
        evidence["completed_unix_ms"] = time.time_ns() // 1000000
        evidence["exit_code"] = process.poll()


def fault_arguments(fixture_file, fault, proof):
    arguments = [sys.executable, str(HERE / "verify-ha.py"), "--fixture", str(fixture_file),
                 "--phase", fault, "--proof", str(proof / "ha")]
    if fault == "mongo":
        arguments.append("--prepared-publisher")
    return arguments

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
    preparations = {}
    summary = {"passed": False, "complete": False, "fault": args.fault}
    stage = "fixture_preflight"
    try:
        active = json.loads(kube(fixture, ["get", "pods", "-l", "fluxgate.io/load-phase=resilience", "-o", "json"]))
        require(not active["items"], "another resilience generator is active")
        stage = "identity_preparation" if args.fault == "redis" else "publisher_preparation"
        helper = HERE / ("prepare-ha-identity.py" if args.fault == "redis" else "publish-hook.py")
        evidence = {"source": digest(helper)}
        preparations[stage] = evidence
        arguments = [sys.executable, str(helper), "--fixture", str(fixture_file)]
        arguments += ["--output", str(proof / "identity.json")] if args.fault == "redis" else ["--prepare"]
        with (proof / "prepare.log").open("w") as log:
            prepare_owned(arguments, log, processes, stage, evidence)
        if args.fault == "redis":
            identity = json.loads((proof / "identity.json").read_text())
            require(identity.get("passed") is True and identity.get("policy_pointer_unchanged") is True
                    and identity.get("existing_counters_deleted_or_reset") is False, "identity preparation changed policy/counters")
        else:
            state_file = fixture_file.parent / "publication-hook-state.json"
            require(state_file.stat().st_mode & 0o077 == 0, "publisher state must be private")
            state = json.loads(state_file.read_text())
            require(state.get("probe_owned") is True and state.get("pod_uid") and state.get("source_authz_pod") and state.get("source_authz_pod_uid")
                    and state.get("ruleSetId") == fixture.get("rule_set_id", "resilience-limits")
                    and isinstance(state.get("jar_sha256"), str) and len(state["jar_sha256"]) == 64
                    and all(c in "0123456789abcdef" for c in state["jar_sha256"]), "publisher provenance absent")
            baseline = state.get("baseline", {})
            require(all(key in baseline for key in ("revision", "counterEpoch", "snapshotId", "checksum")),
                    "prepared publisher baseline pointer/epoch absent")
            retained_state = proof / "publication-helper-state.json"
            retained_state.write_text(json.dumps(state, indent=2) + "\n")
            evidence["state"] = digest(retained_state)
        # All compilation/upload and preparation finish while healthy, before warmup.
        stage = "load_start"
        log = (proof / "load.log").open("w"); handles.append(log)
        load = subprocess.Popen([sys.executable, str(HERE / "verify-load.py"), "--fixture", str(fixture_file),
            "--phase", "resilience", "--output", str(proof / "raw-load.json")], stdout=log, stderr=log)
        processes.append(("load", load))
        stage = "load_readiness"
        name, marker, pod_identity = wait_readiness(fixture, load)
        stage = "clock_alignment"
        clock = clock_measurement(fixture, name)
        (proof / "readiness.json").write_text(json.dumps({"marker": marker, "clock": clock, "pod_identity": pod_identity}, indent=2) + "\n")
        if load.poll() is not None:
            raise ReadinessFailure("load exited before fault launch", "load_warmup")
        stage = "fault_execution"
        log = (proof / "ha.log").open("w"); handles.append(log)
        ha = subprocess.Popen(fault_arguments(fixture_file, args.fault, proof), stdout=log, stderr=log)
        processes.append(("ha", ha))
        ha_code = ha.wait(timeout=600)
        load_code = load.wait(timeout=400)
        require(ha_code == 0 and load_code == 0, "load or fault subprocess failed")
        raw_load, raw_ha = proof / "raw-load.json", proof / "ha" / "ha.json"
        load_data = json.loads(raw_load.read_text())
        require(load_data["target"]["generator_pod"] == name
                and load_data["observation"]["schedule_start_unix_ms"] == marker["schedule_start_unix_ms"],
                "raw load does not match readiness invocation")
        stage = "evidence_assessment"
        summary = assess(load_data, json.loads(raw_ha.read_text()), args.fault, clock)
        summary["raw_proofs"] = {"load": digest(raw_load), "ha": digest(raw_ha), "readiness": digest(proof / "readiness.json")}
    except Exception as error:
        summary["failure_stage"] = getattr(error, "stage", stage)
        summary["fault_process_started"] = any(name == "ha" for name, _ in processes)
        summary["failure"] = str(error) if isinstance(error, ValueError) else type(error).__name__
    finally:
        # Do not kill a fault subprocess: its finally block must restore the real primary.
        for process_name, process in processes:
            if process.poll() is None:
                if process_name in ("identity_preparation", "publisher_preparation"):
                    stop_preparation_group(process)
                    continue
                try:
                    process.wait(timeout=600)
                except subprocess.TimeoutExpired:
                    summary["passed"] = False
                    summary["incomplete_subprocess_retained"] = True
        # HA owns cleanup after real fault restoration. This fallback is idempotent
        # and also handles a partially-created publisher when preparation fails.
        if args.fault == "mongo" and any(name == "publisher_preparation" for name, _ in processes):
            cleanup = {}
            summary["publisher_cleanup"] = cleanup
            if any(name == "ha" and process.poll() is None for name, process in processes):
                summary["passed"] = False
                cleanup["failure"] = "deferred while HA fault restoration is active"
            else:
                try:
                    cleanup["source"] = digest(HERE / "publish-hook.py")
                    with (proof / "publisher-cleanup.log").open("w") as log:
                        prepare_owned([sys.executable, str(HERE / "publish-hook.py"),
                            "--fixture", str(fixture_file), "--cleanup"], log, processes,
                            "publisher_cleanup", cleanup, timeout=90)
                    report = json.loads((proof / "publisher-cleanup.log").read_text())
                    cleanup_proof = proof / "publisher-cleanup.json"
                    cleanup_proof.write_text(json.dumps(report, indent=2) + "\n")
                    cleanup["proof"] = digest(cleanup_proof)
                    require(report.get("passed") is True and report.get("pod_absent") is True
                            and report.get("network_policy_absent") is True,
                            "publisher cleanup did not verify absence of owned resources")
                    cleanup["owned_resources_absent_verified_by_helper"] = True
                    state_file = fixture_file.parent / "publication-hook-state.json"
                    if state_file.exists():
                        state = json.loads(state_file.read_text())
                        if state.get("probe_owned") is True:
                            require(state.get("cleanup_complete") is True
                                    and isinstance(state.get("cleanup_resources"), list)
                                    and all(resource.get("absent") is True for resource in state["cleanup_resources"]),
                                    "publisher cleanup tombstone incomplete")
                        tombstone = proof / "publication-helper-cleanup-state.json"
                        tombstone.write_bytes(state_file.read_bytes())
                        cleanup["state"] = digest(tombstone)
                except Exception as error:
                    summary["passed"] = False
                    cleanup["failure"] = str(error) if isinstance(error, ValueError) else type(error).__name__
                    # Retain the original preparation/measurement failure verbatim.
                    if "failure" not in summary:
                        summary["failure_stage"] = "publisher_cleanup"
                        summary["failure"] = cleanup["failure"]
        summary["preparations"] = preparations
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
    # Exercise the orchestration boundary without kubectl, subprocesses or faults.
    pod = {"metadata": {"name": "owned", "uid": "original-uid", "creationTimestamp": "2026-10-10T00:00:00Z",
                           "labels": {"fluxgate.io/load-run": "owned", "fluxgate.io/load-phase": "resilience",
                                      "app": "fluxgate-load-generator", "fluxgate.io/environment": "local-ephemeral"}},
           "status": {"phase": "Running"}}
    marker = {"load_pod_name": "owned", "run_label": "owned", "phase": "resilience",
              "planned_requests": 12000, "target_rps": 100, "duration_seconds": 120}
    class FakeLoad:
        def __init__(self, exits=None):
            self.exits = iter(exits or [])
        def poll(self):
            return next(self.exits, None)
    def polling(responses, process=None):
        remaining = iter(responses)
        queries = []
        ticks = [0]
        def query(_, arguments):
            queries.append(arguments)
            response = next(remaining)
            if isinstance(response, Exception):
                raise response
            return json.dumps(response)
        def sleep(seconds):
            ticks[0] += seconds
        result = wait_readiness({}, process or FakeLoad(), query, lambda: ticks[0], sleep)
        return result, queries
    result, queries = polling([{"items": [pod]}, None, {"items": [pod]}, marker, pod])
    assert result[0] == "owned" and result[2]["uid"] == "original-uid" and len(queries) == 5
    # Run the actual exec payload against a missing local path: absent is JSON null,
    # not a nonzero cat exit. This uses no Kubernetes or live fixture.
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        expression = queries[1][-1].replace("/tmp/fluxgate-load-ready.json", str(Path(directory) / "ready.json"))
        absent = subprocess.run([sys.executable, "-c", expression], capture_output=True, text=True)
        assert absent.returncode == 0 and json.loads(absent.stdout) is None
    rejected_polls = 0
    replacement = copy.deepcopy(pod); replacement["metadata"]["uid"] = "replacement"
    wrong_owner = copy.deepcopy(pod); wrong_owner["metadata"]["labels"]["fluxgate.io/load-run"] = "other"
    for responses, process, reason in [
        ([], FakeLoad([1]), "load exited"),
        ([{"items": [pod]}, None], FakeLoad([None, 1]), "load exited"),
        ([{"items": [pod]}, marker], FakeLoad([None, 1]), "load exited"),
        ([{"items": [pod]}, None, {"items": [replacement]}], None, "identity changed"),
        ([{"items": [pod]}, None, {"items": []}], None, "disappeared"),
        ([{"items": [wrong_owner]}], None, "ownership mismatch"),
        ([{"items": [pod]}, ValueError("coordination kubectl failed")], None, "kubectl failed"),
        ([{"items": [pod]}, marker, replacement], None, "identity or ownership changed")]:
        try:
            polling(responses, process)
        except ValueError as error:
            assert reason in str(error)
            if "load exited" in reason:
                assert error.stage == "load_warmup"
            rejected_polls += 1
            continue
        raise AssertionError("failed/replaced generator authorized a fault")
    # Mock only process/API boundaries: exercise actual coordinator ordering,
    # source/state evidence, flags, stage attribution and failed JSON retention.
    import contextlib
    import io
    from types import SimpleNamespace
    saved = {key: globals()[key] for key in ("HERE", "kube", "wait_readiness", "clock_measurement", "assess")}
    original_popen = subprocess.Popen
    original_killpg = os.killpg
    failed_evidence = {"phase": "resilience", "result": "FAIL", "warmup": {"all_statuses": {"503": 325}}}
    ordering_checks = 0
    try:
        os.killpg = lambda *arguments: None
        for fault, failure in [("mongo", None), ("redis", None), ("mongo", "warmup"), ("mongo", "prepare"),
                               ("mongo", "provenance"), ("mongo", "cleanup"), ("mongo", "prepare_and_cleanup"), ("mongo", "residual")]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                globals()["HERE"] = root
                for script in ("publish-hook.py", "prepare-ha-identity.py"):
                    (root / script).write_text("# offline helper source\n")
                fixture = root / "fixture.json"
                fixture.write_text(json.dumps({"context": "kind-fluxgate-resilience", "namespace": "fluxgate-resilience"}))
                proof = root / "proof"
                spawned = []
                events = []
                class FakeProcess:
                    pid = 123
                    def __init__(self, name, code=0):
                        self.name, self.code, self.finished = name, code, False
                    def poll(self):
                        return self.code if self.finished else None
                    def wait(self, timeout):
                        events.append("completed:" + self.name)
                        self.finished = True
                        return self.code
                def fake_popen(arguments, stdout, stderr, start_new_session=False):
                    name = Path(arguments[1]).name
                    spawned.append((name, arguments, start_new_session))
                    events.append("started:" + name)
                    if name in ("publish-hook.py", "prepare-ha-identity.py"):
                        assert start_new_session
                        if "--cleanup" in arguments:
                            assert name == "publish-hook.py" and len(spawned) > 1
                            assert "started:verify-ha.py" not in events or "completed:verify-ha.py" in events
                            stdout.write(json.dumps({"passed": True, "pod_absent": True,
                                "network_policy_absent": failure != "residual"})); stdout.flush()
                            state_file = root / "publication-hook-state.json"
                            state = json.loads(state_file.read_text())
                            state.update(cleanup_complete=True, cleanup_resources=[{"kind": "Pod", "name": "probe", "uid": "probe-uid", "absent": True}])
                            state_file.write_text(json.dumps(state))
                            return FakeProcess("cleanup", 1 if failure in ("cleanup", "prepare_and_cleanup") else 0)
                        assert len(spawned) == 1
                        if name == "publish-hook.py":
                            assert "--prepare" in arguments
                            state = {"probe_owned": True, "pod_uid": "probe-uid", "source_authz_pod": "authz-source", "source_authz_pod_uid": "authz-uid",
                                     "jar_sha256": "a" * 64,
                                     "ruleSetId": "resilience-limits", "baseline": {
                                         "revision": 3, "counterEpoch": "epoch", "snapshotId": "snap", "checksum": "sum"}}
                            if failure == "provenance":
                                del state["source_authz_pod_uid"]
                            state_file = root / "publication-hook-state.json"
                            state_file.write_text(json.dumps(state)); state_file.chmod(0o600)
                        else:
                            Path(arguments[arguments.index("--output") + 1]).write_text(json.dumps({
                                "passed": True, "policy_pointer_unchanged": True, "existing_counters_deleted_or_reset": False}))
                        return FakeProcess(name, 1 if failure in ("prepare", "prepare_and_cleanup") else 0)
                    if name == "verify-load.py":
                        assert "completed:" + spawned[0][0] in events
                        stdout.write(json.dumps(failed_evidence)); stdout.flush()
                        if failure != "warmup":
                            Path(arguments[arguments.index("--output") + 1]).write_text(json.dumps({
                                "target": {"generator_pod": "owned"}, "observation": {"schedule_start_unix_ms": 123}}))
                        process = FakeProcess(name, 1 if failure == "warmup" else 0)
                        process.finished = failure == "warmup"
                        return process
                    assert name == "verify-ha.py" and not start_new_session
                    assert ("--prepared-publisher" in arguments) == (fault == "mongo")
                    assert events.index("marker-ready") < events.index("started:verify-ha.py")
                    ha_dir = proof / "ha"; ha_dir.mkdir()
                    (ha_dir / "ha.json").write_text('{}')
                    return FakeProcess(name)
                def fake_ready(fixture_data, load_process):
                    if load_process.poll() is not None:
                        raise ReadinessFailure("load exited before readiness marker", "load_warmup")
                    events.append("marker-ready")
                    return "owned", {"schedule_start_unix_ms": 123}, {"uid": "load-uid"}
                globals()["kube"] = lambda *arguments: '{"items": []}'
                globals()["wait_readiness"] = fake_ready
                globals()["clock_measurement"] = lambda *arguments: {}
                globals()["assess"] = lambda *arguments: {"passed": True, "complete": False}
                subprocess.Popen = fake_popen
                with contextlib.redirect_stdout(io.StringIO()):
                    code = coordinate(SimpleNamespace(fixture=fixture, proof=proof, fault=fault))
                summary = json.loads((proof / "summary.json").read_text())
                prep_name = "publisher_preparation" if fault == "mongo" else "identity_preparation"
                assert summary["preparations"][prep_name]["source"]["sha256"] == digest(root / spawned[0][0])["sha256"]
                if failure in ("cleanup", "residual"):
                    assert code == 1 and not summary["passed"] and summary["failure_stage"] == "publisher_cleanup"
                    assert summary["publisher_cleanup"]["exit_code"] == (1 if failure == "cleanup" else 0) and len(spawned) == 4
                elif failure:
                    assert code == 1 and not summary["passed"] and not summary["fault_process_started"]
                    assert not (proof / "ha.log").exists()
                    assert summary["failure_stage"] == ("load_warmup" if failure == "warmup" else "publisher_preparation")
                    if failure == "warmup":
                        assert json.loads((proof / "raw-load.json").read_text()) == failed_evidence
                        assert summary["raw_proofs"]["load"]["sha256"] == digest(proof / "raw-load.json")["sha256"]
                    else:
                        assert len(spawned) == 2 and not (proof / "load.log").exists()
                        if failure == "prepare_and_cleanup":
                            assert summary["failure"] == "publisher_preparation failed"
                            assert summary["publisher_cleanup"]["failure"] == "publisher_cleanup failed"
                else:
                    assert code == 0 and len(spawned) == (4 if fault == "mongo" else 3)
                    if fault == "mongo":
                        assert summary["preparations"][prep_name]["state"]["sha256"] == digest(proof / "publication-helper-state.json")["sha256"]
                if fault == "mongo" and failure not in ("cleanup", "prepare_and_cleanup", "residual"):
                    assert summary["publisher_cleanup"]["owned_resources_absent_verified_by_helper"] is True
                    assert "state" in summary["publisher_cleanup"]
                if fault == "redis":
                    assert "publisher_cleanup" not in summary
                ordering_checks += 1
    finally:
        globals().update(saved)
        subprocess.Popen, os.killpg = original_popen, original_killpg
    # Timeout kills only the helper's dedicated session, including descendants.
    original_killpg = os.killpg
    signals = []
    class TimedOutPreparation:
        pid = 4242
        def wait(self, timeout):
            if timeout == 300:
                raise subprocess.TimeoutExpired("offline-helper", timeout)
            return -15
        def poll(self):
            return -15
    try:
        os.killpg = lambda pid, signum: signals.append((pid, signum))
        subprocess.Popen = lambda *args, **kwargs: TimedOutPreparation()
        evidence, processes = {}, []
        try:
            prepare_owned(["offline"], None, processes, "publisher_preparation", evidence)
        except subprocess.TimeoutExpired:
            pass
        else:
            raise AssertionError("preparation timeout ignored")
        assert signals == [(4242, signal.SIGTERM), (4242, signal.SIGKILL)]
        assert evidence["exit_code"] == -15 and processes[0][0] == "publisher_preparation"
    finally:
        os.killpg, subprocess.Popen = original_killpg, original_popen
    print(json.dumps({"result": "PASS", "self_checks": len(invalid) + 5 + rejected_polls + ordering_checks + 1}))


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
