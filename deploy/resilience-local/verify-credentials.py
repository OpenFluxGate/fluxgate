#!/usr/bin/env python3
"""Real credential controls/rotations for the isolated resilience fixture.

Secrets travel through private files or subprocess stdin, never command arguments or
proof output. Every HTTP/TLS/store probe creates a new connection. Run serially after
HA/load proofs; successful rotations intentionally update the private fixture files.
"""
import argparse
import asyncio
import base64
import contextlib
import hashlib
import http.client
import http.server
import inspect
import json
import os
import queue
import re
from pathlib import Path
import secrets
import socket
import ssl
import sys
import subprocess
import tempfile
import threading
import time
from urllib.parse import parse_qsl, quote, unquote, urlencode, urlsplit, urlunsplit


class ProofError(RuntimeError):
    pass


def require(condition, label):
    if not condition:
        raise ProofError(label)


def credential_runtime():
    require(ssl.HAS_TLSv1_3,
            "live credential proof requires a TLS 1.3 capable Python/OpenSSL runtime; macOS LibreSSL is unsupported")
    return {"python": sys.version.split()[0], "openssl": ssl.OPENSSL_VERSION,
            "tls13_available": ssl.HAS_TLSv1_3}


def validate_certificate_rejection(reason):
    # Generic alerts, EOF, refused connections and timeouts never establish certificate rejection.
    require(reason in {"CERTIFICATE_VERIFY_FAILED", "TLSV13_ALERT_CERTIFICATE_REQUIRED",
                       "TLSV1_ALERT_UNKNOWN_CA", "SSLV3_ALERT_BAD_CERTIFICATE",
                       "SSLV3_ALERT_CERTIFICATE_EXPIRED", "TLSV1_ALERT_BAD_CERTIFICATE",
                       "SSLV3_ALERT_CERTIFICATE_UNKNOWN"},
            "TLS negative lacked explicit certificate rejection")


def run(args, data=None, check=True, timeout=120, env=None):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout, env=env)
    if check and result.returncode:
        # stderr may contain a URI, password or JWT. Deliberately never include it.
        raise ProofError("command failed: " + Path(args[0]).name)
    return result


def private_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(content.encode() if isinstance(content, str) else content)
    os.chmod(path, 0o600)


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mongo_marker(output, marker):
    """Accept exactly one known marker, with mongosh's optional exact prompt prefix."""
    values = []
    for line in output.decode().splitlines():
        if line.startswith("> "):
            line = line[2:]
        if line.startswith(marker):
            values.append(line[len(marker):].strip())
    require(len(values) == 1 and bool(values[0]), "Mongo marker missing or ambiguous: " + marker)
    return values[0]


def signed_jwt(openssl, private_key, kid, issuer, audience, expiry, algorithm="RS256"):
    """Sign explicit claims; negative fixtures change one trust input at a time."""
    now = int(time.time())
    header = b64url(json.dumps({"alg": algorithm, "kid": kid, "typ": "JWT"},
                              separators=(",", ":")).encode())
    claims = b64url(json.dumps({"iss": issuer, "aud": audience, "sub": "credential-proof-admin",
                               "iat": min(now, expiry - 60), "exp": expiry,
                               "realm_access": {"roles": ["admin"]}}, separators=(",", ":")).encode())
    message = (header + "." + claims).encode()
    digest = {"RS256": "-sha256", "RS512": "-sha512"}[algorithm]
    signature = run([openssl, "dgst", digest, "-sign", str(private_key)], data=message).stdout
    return message.decode() + "." + b64url(signature)


def certificate_fingerprints(pem):
    certificates = re.findall(rb"-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----", pem)
    require(bool(certificates), "TLS barrier certificate bundle is empty")
    require(not re.sub(rb"-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----", b"", pem).strip(),
            "TLS barrier certificate bundle contains unexpected material")
    try:
        return sorted({hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.decode())).hexdigest() for cert in certificates})
    except Exception:
        raise ProofError("TLS barrier certificate encoding invalid") from None


def loaded_envoy_tls(dump, cluster_name, server_name, client_secret, ca_secret, ca_pem, client_pem):
    configs = dump.get("configs", [])
    clusters = [entry["cluster"] for config in configs for entry in config.get("dynamic_active_clusters", [])
                if entry.get("cluster", {}).get("name") == cluster_name]
    require(len(clusters) == 1, "TLS barrier application cluster missing or ambiguous")
    require(not any(entry.get("cluster", {}).get("name") == cluster_name
                    for config in configs for entry in config.get("dynamic_warming_clusters", [])),
            "TLS barrier application cluster is warming")
    sockets = [item.get("transport_socket", {}).get("typed_config", {})
               for item in clusters[0].get("transport_socket_matches", [])]
    direct = clusters[0].get("transport_socket", {}).get("typed_config")
    if sockets and direct is not None:
        # EG emits an empty default TLS context beside its bound TLS socket match.
        require(direct == {"@type": "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
                           "common_tls_context": {}}, "TLS barrier alternative application transport is ambiguous")
    elif direct is not None:
        sockets.append(direct)
    require(len(sockets) == 1, "TLS barrier application transport missing or ambiguous")
    transport = sockets[0]
    require(transport.get("@type") == "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
            "TLS barrier application transport is not upstream TLS")
    common = transport.get("common_tls_context", {})
    require(transport.get("sni") == server_name, "TLS barrier application SNI mismatch")
    require([item.get("name") for item in common.get("tls_certificate_sds_secret_configs", [])] == [client_secret]
            and common.get("combined_validation_context", {}).get("validation_context_sds_secret_config", {}).get("name") == ca_secret,
            "TLS barrier application SDS binding mismatch")
    active = [entry for config in configs for entry in config.get("dynamic_active_secrets", [])]
    warming = [entry for config in configs for entry in config.get("dynamic_warming_secrets", [])]
    actual = {}
    for name, kind, field in ((client_secret, "tls_certificate", "certificate_chain"),
                              (ca_secret, "validation_context", "trusted_ca")):
        require(not any(entry.get("name") == name or entry.get("secret", {}).get("name") == name for entry in warming),
                "TLS barrier bound secret is warming")
        entries = [entry for entry in active if entry.get("name") == name or entry.get("secret", {}).get("name") == name]
        require(len(entries) == 1 and entries[0].get("name") == name and entries[0].get("secret", {}).get("name") == name,
                "TLS barrier bound active secret missing or ambiguous")
        source = entries[0]["secret"].get(kind, {}).get(field, {})
        require(set(source) == {"inline_bytes"}, "TLS barrier bound certificate is not inline bytes")
        try:
            pem = base64.b64decode(source["inline_bytes"], validate=True)
        except Exception:
            raise ProofError("TLS barrier inline certificate encoding invalid") from None
        actual[name] = certificate_fingerprints(pem)
    require(actual[ca_secret] == certificate_fingerprints(ca_pem), "TLS barrier trust fingerprint set mismatch")
    require(actual[client_secret] == certificate_fingerprints(client_pem), "TLS barrier client fingerprint set mismatch")
    return {"ca_fingerprints": actual[ca_secret], "client_fingerprints": actual[client_secret]}


def redis_hash_contents(value):
    fields = value.split(b"\n")
    require(bool(value) and len(fields) % 2 == 0, "Redis metadata is not a complete hash")
    pairs = dict(zip(fields[::2], fields[1::2]))
    require(len(pairs) == len(fields) // 2, "Redis metadata has ambiguous duplicate fields")
    return pairs


def validate_restart_identity(before, after, originals, current):
    require(before["metadata"]["uid"] != after["metadata"]["uid"], "Redis member was not cold replaced")
    def claims(pod):
        return {v["persistentVolumeClaim"]["claimName"] for v in pod["spec"]["volumes"] if "persistentVolumeClaim" in v}
    require(bool(claims(before)) and claims(before) == claims(after), "replacement lost data PVC mount")
    for claim, original in originals.items():
        pvc = current[claim]
        require(pvc["metadata"]["uid"] == original["metadata"]["uid"] and
                pvc["spec"]["volumeName"] == original["spec"]["volumeName"], "cold restart replaced PVC/PV identity")


def sampler_worker(config, stop_path):
    """Fixed 100ms arrivals, bounded concurrent fresh sockets, and explicit omissions."""
    start = time.monotonic()
    anchor = {"started_utc_ns": time.time_ns(), "started_monotonic_ns": time.monotonic_ns()}
    sequence, omitted, peak, worker_failures = 0, 0, 0, 0
    samples, omissions, pending = [], [], set()
    condition = threading.Condition()
    slots = threading.BoundedSemaphore(24)
    jobs = queue.Queue(maxsize=24)
    shutdown = threading.Event()
    positive_claimed = False
    status_counts = {}
    progress_failures = 0
    progress_mailbox = queue.Queue(maxsize=1)
    progress_stop, progress_cancel = threading.Event(), threading.Event()
    publication_lock = threading.Lock()
    progress_path = Path(config.get("progress_path", "/tmp/sampler-progress.json"))

    def snapshot():  # Caller holds condition: pending and completed cannot disappear between reads.
        return {**anchor, "interval_ms": 100, "scheduled": sequence,
            "omitted_schedules": omitted, "omissions": list(omissions),
            "samples": sorted(samples, key=lambda sample: sample["sequence"]),
            "pending": len(pending), "pending_sequences": sorted(pending),
            "peak_inflight": peak, "max_inflight": 24, "worker_failures": worker_failures,
            "duration_seconds": time.monotonic() - start,
            "latency_basis": "scheduled arrival including worker dispatch; service latency separate",
            "transport": "in-cluster Gateway Service; fresh connection per sample"}

    def publish(contents, final=False):
        # Write outside both scheduler and publication locks; only rename is serialized.
        temporary = progress_path.with_name(progress_path.name + (".final" if final else ".partial"))
        try:
            private_write(temporary, contents)
            with publication_lock:
                if final or not progress_cancel.is_set():
                    os.replace(temporary, progress_path)
        finally:
            temporary.unlink(missing_ok=True)

    def progress():  # Caller holds condition; fixed-size state, never full sample history.
        if progress_stop.is_set():
            return
        partial = {"progress_schema": 1, "partial": True, "complete": False,
            "interval_ms": 100, "scheduled": sequence, "completed": len(samples),
            "pending": len(pending), "pending_sequences": sorted(pending),
            "omitted_schedules": omitted, "status_counts": dict(status_counts),
            "worker_failures": worker_failures, "progress_writer_failures": progress_failures,
            "captured_utc_ns": time.time_ns(), "captured_monotonic_ns": time.monotonic_ns()}
        try:
            progress_mailbox.put_nowait(partial)
        except queue.Full:
            try:
                progress_mailbox.get_nowait()
            except queue.Empty:
                pass
            progress_mailbox.put_nowait(partial)

    def report_progress():
        nonlocal progress_failures
        while not progress_stop.is_set() or not progress_mailbox.empty():
            try:
                partial = progress_mailbox.get(timeout=.1)
            except queue.Empty:
                continue
            try:
                publish(json.dumps(partial))
            except Exception:
                with condition:
                    progress_failures += 1

    reporter = threading.Thread(target=report_progress, daemon=True)
    reporter.start()

    def worker():
        nonlocal worker_failures, positive_claimed
        while not shutdown.is_set():
            index = jobs.get()
            if index is None or shutdown.is_set():
                jobs.task_done()
                return  # An unexecuted numeric arrival remains explicit pending evidence.
            origin = start + index * .1
            began = time.monotonic()
            sample = {"sequence": index, "scheduled_elapsed_seconds": index * .1,
                "elapsed_seconds": began - start, "dispatch_lag_ms": max(0, began - origin) * 1000,
                "status": None, "body_valid": False}
            connection = None
            try:
                connection = http.client.HTTPConnection(config["service"], config["port"], timeout=2)
                connection.request("GET", config["path"], headers={"Host": config["host"],
                    "x-api-key": config["api_key"], "Connection": "close"})
                response = connection.getresponse()
                body = response.read()
                expected = config["body"].encode()
                sample.update(status=response.status, body_valid=body in (expected, expected + b"\n"))
            except BaseException as error:
                sample.update(status=None, body_valid=False, error=type(error).__name__)
            finally:
                if connection is not None:
                    try:
                        connection.close()
                    except BaseException as error:
                        sample.update(status=None, body_valid=False, error=type(error).__name__)
                completed = time.monotonic()
                sample.update(latency_ms=max(0, completed - origin) * 1000,
                    service_latency_ms=(completed - began) * 1000,
                    completed_elapsed_seconds=completed - start)
                with condition:
                    publish_positive = (sample["status"] == 200 and sample["body_valid"]
                                        and not shutdown.is_set() and not positive_claimed)
                    if publish_positive:
                        positive_claimed = True
                try:
                    # One claimed marker write must not block the scheduler's condition.
                    if publish_positive:
                        private_write(config.get("positive_path", "/tmp/sampler-positive"), "yes")
                except Exception:
                    with condition:
                        worker_failures += 1
                finally:
                    with condition:
                        # Publish completion only after marker I/O; snapshots stay disjoint.
                        samples.append(sample)
                        pending.remove(index)
                        status = sample["status"]
                        label = ("ERROR" if status is None else str(status) if status in (200, 403, 429)
                                 else "5xx" if 500 <= status <= 599 else "OTHER")
                        status_counts[label] = status_counts.get(label, 0) + 1
                        try:
                            if len(samples) == 1 and not shutdown.is_set():
                                progress()
                        except Exception:
                            worker_failures += 1  # No exception text or credential data in evidence.
                        finally:
                            slots.release()
                            jobs.task_done()
                            condition.notify_all()

    workers = [threading.Thread(target=worker, daemon=True) for _ in range(24)]
    for thread in workers:
        thread.start()
    try:
        while not Path(stop_path).exists():
            deadline = start + sequence * .1
            time.sleep(max(0, deadline - time.monotonic()))
            if Path(stop_path).exists():
                break
            with condition:
                if Path(stop_path).exists():
                    break
                lag = time.monotonic() - deadline
                if lag >= .1:
                    missed = int(lag / .1)
                    omissions.append({"first_sequence": sequence, "count": missed, "reason": "lateness"})
                    omitted += missed
                    sequence += missed
                index = sequence
                sequence += 1
                if not slots.acquire(blocking=False):
                    omitted += 1
                    omissions.append({"first_sequence": index, "count": 1, "reason": "capacity"})
                else:
                    pending.add(index)
                    peak = max(peak, len(pending))
                    jobs.put_nowait(index)
                if sequence == 1 or sequence % 10 == 1:
                    progress()
    finally:
        # Stop halts arrivals; drain all submitted work. A stuck worker remains explicit pending,
        # and the parent rejects the incomplete report rather than losing accepted schedules.
        drain_deadline = time.monotonic() + 5
        with condition:
            while pending and time.monotonic() < drain_deadline:
                condition.wait(timeout=max(0, drain_deadline - time.monotonic()))
        shutdown.set()
        # Wake every idle worker without blocking on a saturated queue. Queued arrivals
        # already wake workers; they remain pending if the bounded drain expired.
        for _ in workers:
            try:
                jobs.put_nowait(None)
            except queue.Full:
                break
        join_deadline = time.monotonic() + 1
        for thread in workers:
            thread.join(timeout=max(0, join_deadline - time.monotonic()))
    progress_stop.set()
    reporter.join(timeout=1)
    # Cancellation + the rename lock fence even a reporter stalled in file I/O.
    progress_cancel.set()
    with publication_lock:
        reporter_drained = not reporter.is_alive()
    with condition:
        report = snapshot()
        report.update(partial=False, complete=True, stopped_utc_ns=time.time_ns(),
            stopped_monotonic_ns=time.monotonic_ns(), progress_writer_failures=progress_failures,
            progress_writer_drained=reporter_drained,
            drain_complete=not pending and not any(thread.is_alive() for thread in workers))
    try:
        publish(json.dumps(report), final=True)
    except Exception:
        report["progress_writer_failures"] += 1
    return report


def sampler_program(stop_path="/tmp/sampler-stop", started_path="/tmp/sampler-started"):
    """Only the stdlib worker and private writer belong in the nonsecret exec argument."""
    return ("import http.client,json,os,queue,sys,threading,time\nfrom pathlib import Path\n\n"
            + inspect.getsource(private_write) + "\n" + inspect.getsource(sampler_worker)
            + "\nconfig = json.loads(sys.stdin.readline())\n"
            + "private_write(" + repr(str(started_path)) + ", 'yes')\n"
            + "print(json.dumps(sampler_worker(config, " + repr(str(stop_path)) + ")), flush=True)\n")


async def relay_connection(reader, writer, config, state):
    async def close(stream):
        stream.close()
        try:
            await asyncio.wait_for(stream.wait_closed(), timeout=2)
        except (ConnectionError, OSError, asyncio.TimeoutError):
            transport = getattr(stream, "transport", None)
            if transport is not None:
                transport.abort()
    """Opaque fresh TCP streams only; bounded active sessions and backpressure."""
    if state["active"] >= 24:
        await close(writer)
        return
    state["active"] += 1
    upstream = None
    tasks = []
    try:
        first = await asyncio.wait_for(reader.read(65536), timeout=2)
        if not first:
            return  # TCP readiness connections must never reach Gateway HTTP/quota.
        remote, upstream = await asyncio.wait_for(
            asyncio.open_connection(config["host"], config["port"], limit=65536), timeout=2)
        upstream.write(first)
        await upstream.drain()
        async def pump(source, destination):
            while True:
                data = await source.read(65536)
                if not data:
                    if destination.can_write_eof():
                        try:
                            destination.write_eof()
                            await destination.drain()
                        except (ConnectionError, OSError):
                            pass
                    return
                destination.write(data)
                await destination.drain()
        tasks = [asyncio.create_task(pump(reader, upstream)), asyncio.create_task(pump(remote, writer))]
        await asyncio.gather(*tasks)
    except (ConnectionError, OSError, asyncio.TimeoutError):
        pass  # No traffic or error text is logged; host HTTP owns response validation/timeouts.
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for stream in (upstream, writer):
            if stream is not None:
                await close(stream)
        state["active"] -= 1


def relay_program(host, port=80, listen=18080):
    require(isinstance(host, str) and bool(re.fullmatch(r"[A-Za-z0-9.-]+", host))
            and port == 80 and listen == 18080, "invalid owned relay endpoint")
    return ("import asyncio\n" + inspect.getsource(relay_connection)
            + "\nconfig = " + repr({"host": host, "port": port}) + "\n"
            + "async def serve():\n"
            + "    state={'active':0}\n"
            + "    server=await asyncio.start_server(lambda r,w:relay_connection(r,w,config,state),"
              "'0.0.0.0',18080,limit=65536)\n"
            + "    async with server:await server.serve_forever()\n"
            + "asyncio.run(serve())\n")


def validate_sampler_partial(report):
    """Validate bounded diagnostics; partial progress is never acceptance evidence."""
    require(isinstance(report, dict) and report.get("progress_schema") == 1
            and report.get("partial") is True and report.get("complete") is False
            and report.get("interval_ms") == 100, "invalid sampler partial schema")
    fields = ("scheduled", "completed", "pending", "omitted_schedules", "worker_failures",
              "progress_writer_failures", "captured_utc_ns", "captured_monotonic_ns")
    require(all(type(report.get(field)) is int and report[field] >= 0 for field in fields),
            "invalid sampler partial counters")
    pending, counts = report.get("pending_sequences"), report.get("status_counts")
    require(report["pending"] <= 24 and isinstance(pending, list)
            and len(pending) == report["pending"]
            and all(type(index) is int and 0 <= index < report["scheduled"] for index in pending)
            and len(set(pending)) == len(pending),
            "invalid sampler partial pending")
    require(isinstance(counts, dict) and set(counts) <= {"200", "403", "429", "5xx", "OTHER", "ERROR"}
            and all(type(value) is int and value >= 0 for value in counts.values())
            and sum(counts.values()) == report["completed"], "invalid sampler partial statuses")
    require(report["scheduled"] == report["completed"] + report["pending"] + report["omitted_schedules"]
            and "samples" not in report and "omissions" not in report,
            "invalid sampler partial accounting")
    return report


def sampler_summary(report):
    require(not report.get("partial", False) and report.get("complete", True)
            and report.get("progress_writer_failures", 0) == 0
            and report.get("progress_writer_drained", True), "sampler progress reporter did not drain cleanly")
    samples = report["samples"]
    require(report.get("pending", 0) == 0 and report.get("drain_complete", True) and
            report.get("worker_failures", 0) == 0, "credential traffic sampler did not drain cleanly")
    require(bool(samples), "credential traffic sampler produced no observations")
    require(report["scheduled"] == len(samples) + report["omitted_schedules"],
            "credential traffic sampler lost schedules")
    statuses = {}
    for sample in samples:
        label = str(sample["status"]) if sample["status"] is not None else "ERROR"
        statuses[label] = statuses.get(label, 0) + 1
    valid = sum(x["status"] == 200 and x["body_valid"] for x in samples)
    report.update(statuses=statuses, backend_successes=valid,
                  unexpected_body_responses=sum(x["status"] == 200 and not x["body_valid"] for x in samples),
                  observed_availability=valid / len(samples),
                  uninterrupted_observed=valid == len(samples) and report["omitted_schedules"] == 0,
                  limit="Sampling bounds observed gaps; it does not prove zero downtime between samples.")
    return report


def credential_acceptance(results, phases):
    """Score completed protocol phases without initiating rollback on availability failure."""
    planned = {phase for phase in phases if phase in ("mtls", "api-key", "stores")}
    reports = results.get("rotation_traffic", {})
    availability = bool(planned)
    for phase in planned:
        report = reports.get(phase, {})
        samples = report.get("samples", [])
        cleanup = report.get("cleanup", {})
        valid = (report.get("interval_ms") == 100 and bool(samples)
                 and report.get("pending", 0) == 0 and report.get("drain_complete", True) is True
                 and report.get("worker_failures", 0) == 0
                 and not report.get("partial", False) and report.get("complete", True)
                 and report.get("progress_writer_failures", 0) == 0
                 and report.get("progress_writer_drained", True)
                 and report.get("scheduled") == len(samples) + report.get("omitted_schedules", -1)
                 and report.get("omitted_schedules") == 0
                 and all(sample.get("sequence") == index and sample.get("status") == 200
                         and sample.get("body_valid") is True and sample.get("error") is None
                         and sample.get("transport_error") is None for index, sample in enumerate(samples))
                 and all(cleanup.get(field) is True for field in
                         ("pod_absent", "networkpolicy_absent", "ownership_checked")))
        availability = availability and valid
    complete = set(phases) == {"mtls", "api-key", "stores", "jwt"}
    return {"protocol_passed": True, "rotation_availability_passed": availability,
            "passed": availability, "complete": complete,
            "final_acceptance_passed": complete and availability}


class Proof:
    def __init__(self, fixture):
        self.fixture_path = Path(fixture).resolve()
        if self.fixture_path.is_dir():
            self.fixture_path /= "fixture.json"
        self.f = json.loads(self.fixture_path.read_text())
        require(self.f["context"] == "kind-fluxgate-resilience", "isolated context required")
        require(self.f["namespace"] == "fluxgate-resilience", "isolated namespace required")
        require(self.fixture_path.stat().st_mode & 0o077 == 0, "fixture permissions must be private")
        self.work = Path(tempfile.mkdtemp(prefix="credential-proof-", dir=self.fixture_path.parent))
        os.chmod(self.work, 0o700)
        self.openssl = self.f.get("openssl", "openssl")
        self.results = {}
        self.backups = {}
        self.store_rollback = None
        self.api_rollback = None
        self.tls_rollback = None
        self.cold_sentinels = []
        self.ns = self.f["namespace"]
        self.api_key = self.read("api_key_file")

    @contextlib.contextmanager
    def operation(self, label):
        allowed = {
            'api.old-mapping',
            'api.barrier-old-only',
            'api.barrier-overlap',
            'api.barrier-retirement',
            'api.overlap-mapping',
            'api.retire-mapping',
            'mtls.barrier-overlap-old-client',
            'mtls.barrier-overlap-new-client',
            'mtls.barrier-new-only',
            'mtls.ca-overlap',
            'mtls.client-envoy-roll',
            'mtls.client-leaf-switch',
            'mtls.client-trust-overlap',
            'mtls.overlap-authz-roll',
            'mtls.retire-client-ca',
            'mtls.retire-server-ca',
            'mtls.retirement-authz-roll',
            'mtls.retirement-envoy-roll',
            'mtls.server-authz-roll',
            'mtls.server-leaf-switch',
            'phase.api-key',
            'phase.jwt',
            'phase.mtls',
            'phase.stores',
            'stores.app-roll',
            'stores.app-switch',
            'stores.cold-restart',
            'stores.mongo-overlap',
            'stores.redis-overlap',
            'stores.retire-mongo',
            'stores.retire-redis',
        }
        require(label in allowed, "unknown rotation timeline label")
        events = self.results.setdefault("rotation_timeline", [])
        def mark(state):
            events.append({"action": label, "state": state, "utc_ns": time.time_ns(),
                           "monotonic_ns": time.monotonic_ns()})
            private_write(self.work / "rotation-timeline.json", json.dumps(events, indent=2) + "\n")
        mark("start")
        try:
            yield
        except BaseException:
            mark("failure")
            raise
        else:
            mark("end")

    def perform(self, label, function, *args, **kwargs):
        with self.operation(label):
            return function(*args, **kwargs)

    def read(self, key):
        p = Path(self.f[key])
        require(p.stat().st_mode & 0o077 == 0, key + " must be private")
        return p.read_text().strip()

    def kube(self, *args, data=None, check=True, namespace=None, timeout=360):
        return run(["kubectl", "--kubeconfig", self.f["kubeconfig"], "--context", self.f["context"],
                    "-n", namespace or self.ns, *args], data=data, check=check, timeout=timeout)

    def get(self, resource, namespace=None, timeout=360):
        return json.loads(self.kube("get", resource, "-o", "json", namespace=namespace, timeout=timeout).stdout)

    def validate(self):
        ns = self.get("namespace/" + self.ns)
        require(ns["metadata"].get("labels", {}).get("fluxgate.io/environment") == "local-ephemeral",
                "namespace missing local-ephemeral guard")
        require(self.f["gateway_host"] not in ("", "localhost"), "explicit Gateway host required")
        for field in ("api_key_file", "mongo_uri_file", "mongo_admin_uri_file", "redis_password_file"):
            self.read(field)

    @contextlib.contextmanager
    def forward(self, resource, remote_port, namespace=None, deadline=None):
        port = unused_port()
        log = open(self.work / ("forward-" + str(port) + ".log"), "wb")
        proc = subprocess.Popen(["kubectl", "--kubeconfig", self.f["kubeconfig"], "--context",
                                 self.f["context"], "-n", namespace or self.ns, "port-forward",
                                 resource, "--address", "127.0.0.1", str(port) + ":" + str(remote_port)], stdout=log, stderr=log)
        try:
            for _ in range(100):
                require(proc.poll() is None, "port-forward exited")
                remaining = deadline - time.monotonic() if deadline is not None else .2
                require(remaining > 0, "port-forward exceeded operation deadline")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=min(.2, remaining)):
                        break
                except OSError:
                    time.sleep(.1)
            else:
                raise ProofError("port-forward not ready")
            yield port
        finally:
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
            finally:
                log.close()

    def pods(self, deployment, namespace=None):
        dep = self.get("deployment/" + deployment, namespace)
        selector = ",".join(k + "=" + v for k, v in dep["spec"]["selector"]["matchLabels"].items())
        return json.loads(self.kube("get", "pods", "-l", selector, "-o", "json",
                                   namespace=namespace).stdout)["items"]

    def rollout(self, deployment=None, namespace=None):
        deployment = deployment or self.f.get("authz_deployment", "fluxgate-authz")
        old = {pod["metadata"]["uid"] for pod in self.pods(deployment, namespace)}
        self.kube("rollout", "restart", "deployment/" + deployment, namespace=namespace)
        self.kube("rollout", "status", "deployment/" + deployment, "--timeout=300s", namespace=namespace)
        for _ in range(100):
            now = self.pods(deployment, namespace)
            if not old.intersection(pod["metadata"]["uid"] for pod in now):
                require(all(any(c["type"] == "Ready" and c["status"] == "True"
                                for c in p.get("status", {}).get("conditions", [])) for p in now),
                        "replacement Pods not Ready")
                return
            time.sleep(.3)
        raise ProofError("old Pods still present after rollout")

    def envoy_rollout(self):
        namespace = self.f["gateway_namespace"]
        service = self.get("service/" + self.f["gateway_service"], namespace)
        selector = service["spec"]["selector"]
        deps = json.loads(self.kube("get", "deployments", "-o", "json", namespace=namespace).stdout)
        matches = [d for d in deps["items"] if all(
            d["spec"]["template"]["metadata"].get("labels", {}).get(k) == v for k, v in selector.items())]
        require(len(matches) == 1, "cannot uniquely locate Gateway Envoy deployment")
        self.rollout(matches[0]["metadata"]["name"], namespace)

    def patch_data(self, kind, name, values, namespace=None):
        ident = (namespace or self.ns, kind, name)
        if ident not in self.backups:
            original = self.get(kind + "/" + name, namespace)
            # Capture data only; restoring resourceVersion/status from an old resource is unsafe.
            self.backups[ident] = original.get("data", {})
        encoded = {k: base64.b64encode(v.encode() if isinstance(v, str) else v).decode()
                   for k, v in values.items()} if kind == "secret" else values
        self.kube("patch", kind, name, "--type=merge", "--patch-file=/dev/stdin",
                  data=json.dumps({"data": encoded}).encode(), namespace=namespace)

    def gateway(self, path=None, key=None):
        with self.forward("service/" + self.f["gateway_service"], 80, self.f["gateway_namespace"]) as port:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            try:
                headers = {"Host": self.f["gateway_host"], "Connection": "close"}
                if key is not None:
                    headers["x-api-key"] = key
                conn.request("GET", path or self.f["load_path"], headers=headers)
                response = conn.getresponse()
                body = response.read()
                if response.status == 200:
                    expected = self.f.get("backend_body", "fluxgate-resilience-ok").encode()
                    require(body in (expected, expected + b"\n"),
                            "200 response was not the real backend")
                return response.status
            finally:
                conn.close()

    def available(self):
        for _ in range(60):
            status = self.gateway(key=self.api_key)
            if status == 200:
                return
            time.sleep(1)
        raise ProofError("Gateway not recovered with expected backend body")

    def tls_probe(self, ca, cert=None, key=None, path="/healthz", expected=200):
        pods = self.pods(self.f.get("authz_deployment", "fluxgate-authz"))
        require(len(pods) >= 2, "mTLS proof requires at least two authz replicas")
        return [dict(self.tls_probe_pod(pod["metadata"]["name"], ca, cert, key, path, expected),
                     pod_uid=pod["metadata"]["uid"]) for pod in pods]

    def tls_probe_pod(self, pod, ca, cert=None, key=None, path="/healthz", expected=200):
        ctx = ssl.create_default_context(cafile=str(ca))
        if cert:
            ctx.load_cert_chain(str(cert), str(key))
        with self.forward("pod/" + pod, 8443) as port:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=8) as raw:
                    with ctx.wrap_socket(raw, server_hostname=self.f["server_name"]) as conn:
                        if expected == "tls-reject":
                            # TLS 1.3 may deliver its certificate alert after wrap_socket returns.
                            conn.recv(1)
                            raise ProofError("TLS negative lacked explicit certificate rejection")
                        request = ("GET " + path + " HTTP/1.1\r\nHost: " + self.f["server_name"] +
                                   "\r\nx-api-key: " + self.api_key + "\r\nConnection: close\r\n\r\n")
                        conn.sendall(request.encode())
                        response = http.client.HTTPResponse(conn)
                        response.begin()
                        response.read()
                        require(response.status == expected, "unexpected TLS HTTP status")
                        return {"http": response.status, "protocol": conn.version(), "new_connection": True}
            except ssl.SSLError as error:
                require(expected == "tls-reject", "positive TLS probe rejected")
                # DNS/refused/reset/timeout or generic handshake errors are not certificate controls.
                validate_certificate_rejection(error.reason)
                return {"tls_rejected": True, "reason": error.reason, "new_connection": True}
        raise ProofError("negative TLS probe unexpectedly completed")

    def ca(self, stem):
        p = self.work / stem
        config = Path(str(p) + ".cnf")
        private_write(config, "[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n"
                      "[dn]\nCN=local-ca\n[ca]\nbasicConstraints=critical,CA:TRUE\n"
                      "keyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n"
                      "authorityKeyIdentifier=keyid:always\n")
        run([self.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-sha256", "-keyout", str(p) + ".key", "-out", str(p) + ".crt", "-subj", "/CN=" + stem,
             "-config", str(config)])
        return Path(str(p) + ".crt"), Path(str(p) + ".key")

    def certificate(self, stem, ca, ca_key, subject, server=False, expired=False):
        p = self.work / stem
        run([self.openssl, "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(p) + ".key",
             "-out", str(p) + ".csr", "-subj", "/CN=" + subject])
        ext = ("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
               "subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n")
        ext += "extendedKeyUsage=" + ("serverAuth" if server else "clientAuth") + "\n"
        if server:
            ext += "subjectAltName=DNS:" + subject + "\n"
        private_write(str(p) + ".ext", ext)
        if expired:
            private_write(str(p) + ".index", "")
            private_write(str(p) + ".serial", "1000\n")
            config = ("[ca]\ndefault_ca=issuer\n[issuer]\ndatabase=" + str(p) + ".index\nserial=" +
                      str(p) + ".serial\nnew_certs_dir=" + str(self.work) + "\ncertificate=" + str(ca) +
                      "\nprivate_key=" + str(ca_key) + "\ndefault_md=sha256\npolicy=policy\n"
                      "[policy]\ncommonName=supplied\n[leaf]\n" + ext)
            private_write(str(p) + ".cnf", config)
            run([self.openssl, "ca", "-batch", "-config", str(p) + ".cnf", "-extensions", "leaf",
                 "-startdate", "20000101000000Z", "-enddate", "20000102000000Z",
                 "-in", str(p) + ".csr", "-out", str(p) + ".crt"])
        else:
            run([self.openssl, "x509", "-req", "-in", str(p) + ".csr", "-CA", str(ca), "-CAkey",
                 str(ca_key), "-CAcreateserial", "-days", "2", "-sha256", "-extfile", str(p) + ".ext",
                 "-out", str(p) + ".crt"])
        return Path(str(p) + ".crt"), Path(str(p) + ".key")

    def envoy_tls_barrier(self, ca_pem, client_pem):
        deadline = time.monotonic() + 90
        def remaining():
            budget = deadline - time.monotonic()
            require(budget > 0, "TLS barrier exceeded 90-second deadline")
            return budget
        namespace = self.f["gateway_namespace"]
        service = self.get("service/" + self.f["gateway_service"], namespace, timeout=remaining())
        selector = ",".join(key + "=" + value for key, value in sorted(service["spec"]["selector"].items()))
        require(bool(selector), "TLS barrier Gateway selector empty")
        ports = [port for port in service["spec"]["ports"] if port["port"] == 80]
        require(len(ports) == 1 and isinstance(ports[0]["targetPort"], int), "TLS barrier Gateway target port invalid")
        def current():
            require(time.monotonic() < deadline, "TLS barrier exceeded 90-second deadline")
            pods = json.loads(self.kube("get", "pods", "-l", selector, "-o", "json",
                                       "--request-timeout=5s", namespace=namespace, timeout=min(5, remaining())).stdout)["items"]
            require(time.monotonic() < deadline, "TLS barrier exceeded 90-second deadline")
            require(len(pods) == 2 and all(not pod["metadata"].get("deletionTimestamp") and
                    any(condition["type"] == "Ready" and condition["status"] == "True"
                        for condition in pod.get("status", {}).get("conditions", [])) for pod in pods),
                    "TLS barrier requires two Ready nonterminating Envoy Pods")
            return {pod["metadata"]["name"]: pod["metadata"]["uid"] for pod in pods}
        pinned = current()
        client_secret = self.ns + "/" + self.f["envoy_client_tls_secret"]
        # Envoy Gateway's BackendTLSPolicy SDS name is policy-name/namespace-ca.
        ca_secret = self.f.get("backend_tls_policy_name", "authz-backend-tls") + "/" + self.ns + "-ca"
        cluster_name = "securitypolicy/" + self.ns + "/" + self.f.get("security_policy_name", "resilience-ext-auth") + "/extauth/0"
        loaded = {}
        while time.monotonic() < deadline:
            require(current() == pinned, "TLS barrier Envoy Pod identity changed")
            loaded = {}
            for name, uid in pinned.items():
                try:
                    with self.forward("pod/" + name, 19000, namespace, deadline=deadline) as port:
                        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=min(5, remaining()))
                        try:
                            connection.request("GET", "/config_dump", headers={"Connection": "close"})
                            response = connection.getresponse()
                            require(response.status == 200, "TLS barrier admin response invalid")
                            raw = response.read(8 * 1024 * 1024 + 1)
                            require(len(raw) <= 8 * 1024 * 1024, "TLS barrier admin response too large")
                            dump = json.loads(raw)
                        finally:
                            connection.close()
                    evidence = loaded_envoy_tls(dump, cluster_name, self.f["server_name"], client_secret,
                                                ca_secret, ca_pem, client_pem)
                    loaded[name] = dict(evidence, pod_uid=uid)
                except (ProofError, OSError, ValueError, http.client.HTTPException):
                    break
            if len(loaded) == 2:
                break
            time.sleep(min(.5, remaining()))
        else:
            raise ProofError("TLS barrier bound active configuration did not converge within 90 seconds")
        require(current() == pinned, "TLS barrier Envoy Pod identity changed")
        for name in pinned:
            with self.forward("pod/" + name, ports[0]["targetPort"], namespace, deadline=deadline) as port:
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=min(5, remaining()))
                try:
                    connection.request("GET", self.f["load_path"], headers={"Host": self.f["gateway_host"],
                                       "x-api-key": self.api_key, "Connection": "close"})
                    response = connection.getresponse()
                    body = response.read()
                    expected = self.f.get("backend_body", "fluxgate-resilience-ok").encode()
                    require(response.status == 200 and body in (expected, expected + b"\n"),
                            "TLS barrier per-Pod authenticated backend control failed")
                finally:
                    connection.close()
            require(current() == pinned, "TLS barrier Envoy Pod identity changed")
            loaded[name]["fresh_authenticated_backend_200"] = True
        return {"pods": list(loaded.values()), "limit": "Bound active TLS configuration and fresh per-Pod Gateway controls; not a wire ACK or independent backend handshake."}

    def tls(self):
        tls = Path(self.f["tls_dir"])
        self.tls_rollback = {tls / name: (tls / name).read_bytes() for name in (
            "server-ca.crt", "server-ca.key", "client-ca.crt", "client-ca.key",
            "server.crt", "server.key", "client.crt", "client.key")}
        old_server_ca, old_client_ca = tls / "server-ca.crt", tls / "client-ca.crt"
        old_client, old_client_key = tls / "client.crt", tls / "client.key"
        subject = self.f.get("client_subject", "fluxgate-resilience-gateway").removeprefix("CN=")
        untrusted_ca, untrusted_key = self.ca("untrusted-client-ca")
        untrusted = self.certificate("untrusted-client", untrusted_ca, untrusted_key, subject)
        wrong = self.certificate("wrong-subject", old_client_ca, tls / "client-ca.key", "wrong-gateway")
        expired = self.certificate("expired-client", old_client_ca, tls / "client-ca.key", subject, expired=True)
        controls = {"valid": self.tls_probe(old_server_ca, old_client, old_client_key),
                    "missing": self.tls_probe(old_server_ca, expected="tls-reject"),
                    "untrusted": self.tls_probe(old_server_ca, *untrusted, expected="tls-reject"),
                    "expired": self.tls_probe(old_server_ca, *expired, expected="tls-reject"),
                    "wrong_subject": self.tls_probe(old_server_ca, *wrong,
                                                     path="/authz" + self.f["load_path"], expected=403)}
        controls["valid_after_negative_controls"] = self.tls_probe(old_server_ca, old_client, old_client_key)
        self.available()
        new_sca, new_skey = self.ca("new-server-ca")
        new_cca, new_ckey = self.ca("new-client-ca")
        new_server = self.certificate("new-server", new_sca, new_skey, self.f["server_name"], server=True)
        new_client = self.certificate("new-client", new_cca, new_ckey, subject)
        server_secret = self.f.get("authz_tls_secret", "fluxgate-authz-server-tls")
        client_secret = self.f.get("envoy_client_tls_secret", "fluxgate-envoy-client-tls")
        server_cm = self.f.get("server_ca_configmap", "fluxgate-authz-server-ca")
        overlap_server = old_server_ca.read_bytes() + new_sca.read_bytes()
        overlap_client = old_client_ca.read_bytes() + new_cca.read_bytes()
        self.perform("mtls.ca-overlap", self.patch_data, "configmap", server_cm, {"ca.crt": overlap_server.decode()})
        self.perform("mtls.client-trust-overlap", self.patch_data, "secret", server_secret, {"client-ca.crt": overlap_client})
        self.perform("mtls.overlap-authz-roll", self.rollout)
        controls["overlap_old_client"] = self.tls_probe(old_server_ca, old_client, old_client_key)
        controls["overlap_new_client"] = self.tls_probe(old_server_ca, *new_client)
        self.available()
        controls["loaded_overlap_old_client"] = self.perform("mtls.barrier-overlap-old-client",
            self.envoy_tls_barrier, overlap_server, old_client.read_bytes())
        self.perform("mtls.server-leaf-switch", self.patch_data, "secret", server_secret,
                        {"tls.crt": new_server[0].read_bytes(), "tls.key": new_server[1].read_bytes()})
        self.perform("mtls.server-authz-roll", self.rollout)
        self.perform("mtls.client-leaf-switch", self.patch_data, "secret", client_secret,
                        {"tls.crt": new_client[0].read_bytes(), "tls.key": new_client[1].read_bytes()})
        self.perform("mtls.client-envoy-roll", self.envoy_rollout)
        self.available()
        controls["loaded_overlap_new_client"] = self.perform("mtls.barrier-overlap-new-client",
            self.envoy_tls_barrier, overlap_server, new_client[0].read_bytes())
        controls["new_pair_overlap"] = self.tls_probe(new_sca, *new_client)
        self.perform("mtls.retire-client-ca", self.patch_data, "secret", server_secret, {"client-ca.crt": new_cca.read_bytes()})
        self.perform("mtls.retirement-authz-roll", self.rollout)
        self.perform("mtls.retire-server-ca", self.patch_data, "configmap", server_cm, {"ca.crt": new_sca.read_text()})
        self.perform("mtls.retirement-envoy-roll", self.envoy_rollout)
        self.available()
        controls["loaded_new_only"] = self.perform("mtls.barrier-new-only",
            self.envoy_tls_barrier, new_sca.read_bytes(), new_client[0].read_bytes())
        controls["retired_client"] = self.tls_probe(new_sca, old_client, old_client_key, expected="tls-reject")
        controls["retired_server_ca"] = self.tls_probe(old_server_ca, *new_client, expected="tls-reject")
        controls["new_pair_final"] = self.tls_probe(new_sca, *new_client)
        for name, source in {"server-ca.crt": new_sca, "server-ca.key": new_skey,
                             "client-ca.crt": new_cca, "client-ca.key": new_ckey,
                             "server.crt": new_server[0], "server.key": new_server[1],
                             "client.crt": new_client[0], "client.key": new_client[1]}.items():
            private_write(tls / name, source.read_bytes())
        self.results["mtls"] = controls
        self.backups.clear()
        self.tls_rollback = None

    def api_key_pod_probe(self, pod, ca, cert, key, path, credential, expected, deadline):
        def remaining():
            budget = deadline - time.monotonic()
            require(budget > 0, "API replica barrier exceeded 90-second deadline")
            return budget
        try:
            ctx = ssl.create_default_context(cafile=str(ca))
            ctx.load_cert_chain(str(cert), str(key))
            with self.forward("pod/" + pod, 8443, deadline=deadline) as port:
                with socket.create_connection(("127.0.0.1", port), timeout=min(8, remaining())) as raw:
                    with ctx.wrap_socket(raw, server_hostname=self.f["server_name"],
                                         do_handshake_on_connect=False) as conn:
                        # Also bound slow-drip TLS/HTTP reads, which restart per-read timeouts.
                        def expire():
                            try:
                                conn.shutdown(socket.SHUT_RDWR)
                            except OSError:
                                pass
                        timer = threading.Timer(remaining(), expire)
                        timer.daemon = True
                        timer.start()
                        try:
                            conn.settimeout(min(8, remaining()))
                            conn.do_handshake()
                            request = "GET " + path + " HTTP/1.1\r\nHost: " + self.f["gateway_host"] + "\r\n"
                            if credential is not None:
                                request += "x-api-key: " + credential + "\r\n"
                            conn.sendall((request + "Connection: close\r\n\r\n").encode())
                            response = http.client.HTTPResponse(conn)
                            response.begin()
                            response.read()
                            remaining()
                            require(response.status == expected, "API replica control unexpected HTTP status")
                            return {"http": response.status, "protocol": conn.version(), "new_connection": True}
                        finally:
                            timer.cancel()
            remaining()
        except Exception:
            # Transport/header parsing errors must not expose credential bytes or request data.
            raise ProofError("API replica control failed") from None

    def api_key_pod_barrier(self, stage, old, new):
        expected = {"old-only": (200, 403), "overlap": (200, 200), "retirement": (403, 200)}
        require(stage in expected, "unknown API replica barrier stage")
        deadline = time.monotonic() + 90
        def remaining():
            budget = deadline - time.monotonic()
            require(budget > 0, "API replica barrier exceeded 90-second deadline")
            return budget
        deployment = self.get("deployment/" + self.f.get("authz_deployment", "fluxgate-authz"),
                              timeout=min(5, remaining()))
        selector = ",".join(k + "=" + v for k, v in sorted(deployment["spec"]["selector"]["matchLabels"].items()))
        require(bool(selector), "API replica selector empty")
        def current():
            pods = json.loads(self.kube("get", "pods", "-l", selector, "-o", "json",
                "--request-timeout=5s", timeout=min(5, remaining())).stdout)["items"]
            remaining()
            require(len(pods) == 2 and all(not p["metadata"].get("deletionTimestamp") and
                any(c.get("type") == "Ready" and c.get("status") == "True"
                    for c in p.get("status", {}).get("conditions", [])) for p in pods),
                "API replica barrier requires exactly two Ready nonterminating Pods")
            pinned = {p["metadata"]["name"]: p["metadata"]["uid"] for p in pods}
            require(len(pinned) == 2 and len(set(pinned.values())) == 2, "API replica identities ambiguous")
            return pinned
        pinned = current()
        tls = Path(self.f["tls_dir"])
        wrong = secrets.token_urlsafe(40)
        controls = (("old", old, expected[stage][0]), ("new", new, expected[stage][1]),
                    ("missing", None, 403), ("wrong", wrong, 403))
        records = []
        for name, uid in sorted(pinned.items()):
            statuses, protocols = {}, {}
            for label, credential, status in controls:
                result = self.api_key_pod_probe(name, tls / "server-ca.crt", tls / "client.crt",
                    tls / "client.key", "/authz" + self.f["load_path"], credential, status, deadline)
                statuses[label], protocols[label] = result["http"], result["protocol"]
            records.append({"pod_uid": uid, "statuses": statuses, "protocols": protocols,
                            "new_connections": True})
        require(current() == pinned, "API replica identities changed during controls")
        result = {"stage": stage, "pods": records, "pinned_uids_unchanged": True}
        self.results.setdefault("api_key_replica_controls", []).append(result)
        return result

    def api_mapping(self, mappings):
        content = json.dumps({"fluxgate": {"envoy": {"api-keys": mappings}}})
        self.patch_data("secret", self.f.get("api_keys_secret", "fluxgate-api-key-config"),
                        {"application-credentials.yml": content})
        self.rollout()
        self.available()
        return content

    def policy_stamp(self):
        output = self.mongo(self.read("mongo_uri_file"),
                            "const p=c.getDB('fluxgate').getCollection('rate_limit_rules_policies').findOne({_id:" +
                            json.dumps(self.f.get("rule_set_id", "resilience-limits")) +
                            "}); if(!p) throw Error('missing pointer'); print('POLICY:'+JSON.stringify(p))")
        stamp = mongo_marker(output, "POLICY:")
        return json.loads(stamp)

    def api_keys(self):
        policy_before = self.policy_stamp()
        mappings_path = Path(self.f["api_key_mapping_file"])
        document = json.loads(mappings_path.read_text())
        original = document["fluxgate"]["envoy"]["api-keys"]
        retained = self.work / "retained-api-key"
        self.api_rollback = {path: (path.read_bytes(), path.stat().st_mode & 0o777) if path.exists() else None
                             for path in (mappings_path, retained)}
        old, new = secrets.token_urlsafe(40), secrets.token_urlsafe(40)
        identity = "rotation-" + secrets.token_hex(8)
        template = {"user-id": identity, "api-key-id": identity, "attributes": {"tenant": "resilience"}}
        old_map = dict(template, sha256=hashlib.sha256(old.encode()).hexdigest())
        new_map = dict(template, sha256=hashlib.sha256(new.encode()).hexdigest())
        self.perform("api.old-mapping", self.api_mapping, original + [old_map])
        self.perform("api.barrier-old-only", self.api_key_pod_barrier, "old-only", old, new)
        require(self.gateway(self.f["quota_path"], old) == 200, "old API key initial quota")
        before = self.counter_snapshot(identity)
        require(before, "quota Redis counter absent; API-key identity not proven")
        self.perform("api.overlap-mapping", self.api_mapping, original + [old_map, new_map])
        self.perform("api.barrier-overlap", self.api_key_pod_barrier, "overlap", old, new)
        require(self.gateway(self.f["quota_path"], new) == 200, "new API key overlap quota")
        require(self.gateway(self.f["quota_path"], old) == 200, "old API key overlap quota")
        overlap = self.counter_snapshot(identity)
        require(set(before) == set(overlap), "rotation changed bucket key/epoch")
        require(before != overlap, "quota counter did not change across rotation")
        content = self.perform("api.retire-mapping", self.api_mapping, original + [new_map])
        self.perform("api.barrier-retirement", self.api_key_pod_barrier, "retirement", old, new)
        require(self.gateway(self.f["quota_path"], old) == 403, "retired API key accepted")
        for _ in range(2):
            require(self.gateway(self.f["quota_path"], new) == 200, "new key lost remaining quota")
        require(self.gateway(self.f["quota_path"], new) == 429, "rotation reset or bypassed quota")
        require(set(self.counter_snapshot(identity)) == set(before), "retirement changed quota epoch")
        require(self.gateway(self.f["load_path"], None) == 403, "missing API key accepted")
        require(self.gateway(self.f["load_path"], secrets.token_urlsafe(40)) == 403, "wrong API key accepted")
        require(policy_before == self.policy_stamp(), "API key rollout changed published revision/epoch")
        private_write(mappings_path, content)
        private_write(self.work / "retained-api-key", new)
        self.results["api_key"] = {"capacity": 5, "accepted": 5, "next": 429,
                                   "old_retired": 403, "missing": 403, "wrong": 403,
                                   "stable_logical_identity": True, "stable_bucket_epoch": True,
                                   "new_connections_and_replaced_pods": True}
        self.api_rollback = None
        self.backups.clear()

    def redis(self, pod, command, password=None, readonly=False):
        def response(stream):
            prefix = stream.read(1)
            line = stream.readline().rstrip(b"\r\n")
            if prefix == b"$":
                count = int(line)
                if count < 0:
                    return b""
                value = stream.read(count)
                stream.read(2)
                return value
            if prefix == b"*":
                return b"\n".join(response(stream) for _ in range(max(0, int(line))))
            require(prefix in (b"+", b"-", b":"), "invalid Redis protocol response")
            return (b"ERROR " if prefix == b"-" else b"") + line

        def send(stream, command):
            wire = ("*" + str(len(command)) + "\r\n").encode() + b"".join(
                ("$" + str(len(str(v).encode())) + "\r\n").encode() + str(v).encode() + b"\r\n"
                for v in command)
            stream.write(wire)
            stream.flush()
            return response(stream)

        with self.forward("pod/" + pod, 6379) as port:
            with socket.create_connection(("127.0.0.1", port), timeout=8) as connection:
                with connection.makefile("rwb") as stream:
                    if password is not None:
                        auth = send(stream, ["AUTH", password])
                        if auth.startswith(b"ERROR"):
                            return auth
                    if readonly:
                        require(send(stream, ["READONLY"]) == b"OK", "Redis replica READONLY failed")
                    return send(stream, command)

    def redis_check(self, pod, password, expected):
        output = self.redis(pod, ["PING"], password)
        if expected:
            require(b"PONG" in output and b"WRONGPASS" not in output, "valid Redis credential rejected")
        else:
            require(b"PONG" not in output and (b"WRONGPASS" in output or b"NOAUTH" in output),
                    "Redis negative did not explicitly reject authentication")

    def counter_snapshot(self, identity):
        password = self.read("redis_password_file")
        snapshot = {}
        for pod in self.f["redis_pods"]:
            if not self.redis(pod, ["ROLE"], password).startswith(b"master\n"):
                continue
            keys = self.redis(pod, ["KEYS", "fluxgate:bucket:*" + identity + "*"], password)
            for key in keys.decode().splitlines():
                if key.startswith("fluxgate:bucket:"):
                    value = self.redis(pod, ["HGETALL", key], password)
                    snapshot[key] = hashlib.sha256(value).hexdigest()
        return snapshot

    def mongo(self, uri, javascript, expected=True):
        # Fresh mongosh/auth handshake. find/listCollections, unlike ping, requires authentication.
        code = ("try { const c=new Mongo(" + json.dumps(uri) + "); " + javascript +
                "; print('AUTH_PROBE_OK'); quit(0); } catch(e) { "
                "print('AUTH_PROBE_ERROR:'+e.code+':'+e.codeName); quit(31); }\n")
        result = self.kube("exec", "-i", self.f["mongo_pods"][0], "--", "mongosh", "--quiet", "--nodb",
                           data=code.encode(), check=False)
        if expected:
            require(result.returncode == 0 and b"AUTH_PROBE_OK" in result.stdout,
                    "authenticated Mongo operation failed")
        else:
            require(result.returncode != 0 and any(marker in result.stdout for marker in
                    (b":18:", b":13:", b"AuthenticationFailed", b"Unauthorized")),
                    "Mongo negative did not explicitly reject authentication")
        return result.stdout

    def preserve_sampler_progress(self, name, phase):
        try:
            contents = (self.work / name / "progress.json").read_bytes()
            require(self.api_key.encode() not in contents, "sampler progress contains a secret")
            report = validate_sampler_partial(json.loads(contents))
            report["partial_failed_phase"] = True
            private_write(self.work / (phase + "-traffic-partial.json"), json.dumps(report, indent=2) + "\n")
        except Exception:
            # Failure to preserve diagnostics cannot prevent either resource's cleanup attempt.
            private_write(self.work / (phase + "-traffic-partial-unavailable.json"),
                          json.dumps({"partial_failed_phase": True, "progress_available": False}) + "\n")

    def cleanup_sampler_resources(self, name, expected_uids):
        failures = []
        for kind in ("pod", "networkpolicy"):
            resource = kind + "/" + name
            try:
                current = self.kube("get", resource, "--ignore-not-found=true", "-o", "json",
                                    "--request-timeout=10s", check=False)
                require(current.returncode == 0, "sampler cleanup cannot inspect " + kind)
                if not current.stdout.strip():
                    continue
                obj = json.loads(current.stdout)
                metadata = obj["metadata"]
                require(metadata.get("labels", {}).get("fluxgate.io/credential-sampler") == name,
                        "sampler cleanup ownership mismatch: " + kind)
                require(kind not in expected_uids or metadata["uid"] == expected_uids[kind],
                        "sampler cleanup UID mismatch: " + kind)
                deleted = self.kube("delete", resource, "--ignore-not-found=true", "--wait=true",
                                    "--timeout=30s", "--request-timeout=10s", check=False)
                require(deleted.returncode == 0, "sampler deletion failed: " + kind)
                absent = self.kube("get", resource, "--ignore-not-found=true", "-o", "json",
                                   "--request-timeout=10s", check=False)
                require(absent.returncode == 0 and not absent.stdout.strip(), "sampler resource remains: " + kind)
            except Exception:
                # Each resource is attempted independently, including after stop/deletion failure.
                failures.append(kind)
        if failures:
            private_write(self.work / (name + "-cleanup-failed.json"), json.dumps({"failed": failures}) + "\n")
            raise ProofError("sampler cleanup failed: " + ",".join(failures))
        return {"pod_absent": True, "networkpolicy_absent": True, "ownership_checked": True}

    @contextlib.contextmanager
    def traffic_sampler(self, phase):
        name = "credential-traffic-" + secrets.token_hex(6)
        service = self.get("service/" + self.f["gateway_service"], self.f["gateway_namespace"])
        ports = [p for p in service["spec"]["ports"] if p["port"] == 80]
        require(len(ports) == 1 and isinstance(ports[0]["targetPort"], int), "numeric Gateway sampler port required")
        selector = service["spec"].get("selector")
        require(isinstance(selector, dict) and bool(selector)
                and all(isinstance(key, str) and bool(key.strip())
                        and isinstance(value, str) and bool(value.strip())
                        for key, value in selector.items()), "nonempty Gateway sampler selector required")
        labels = {"fluxgate.io/credential-sampler": name}
        policy = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
                  "metadata": {"name": name, "namespace": self.ns, "labels": labels},
                  "spec": {"podSelector": {"matchLabels": labels}, "policyTypes": ["Ingress", "Egress"], "ingress": [], "egress": [
                      {"to": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
                               "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
                       "ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]},
                      {"to": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": self.f["gateway_namespace"]}},
                               "podSelector": {"matchLabels": selector}}],
                       "ports": [{"protocol": "TCP", "port": ports[0]["targetPort"]}]}]}}
        upstream = self.f["gateway_service"] + "." + self.f["gateway_namespace"] + ".svc.cluster.local"
        relay = relay_program(upstream)
        host_work = self.work / name
        host_work.mkdir(mode=0o700)
        stop, positive, started, progress = (host_work / leaf for leaf in ("stop", "positive", "started", "progress.json"))
        forwards = contextlib.ExitStack()
        pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name, "namespace": self.ns, "labels": labels},
               "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                        "terminationGracePeriodSeconds": 1,
                        "securityContext": {"runAsNonRoot": True, "runAsUser": 1000,
                                            "seccompProfile": {"type": "RuntimeDefault"}},
                        "containers": [{"name": "sampler", "image": self.f.get("generator_image", "python:3.12-alpine"),
                                        "command": ["python3", "-c", relay],
                                        "securityContext": {"allowPrivilegeEscalation": False,
                                            "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}},
                                        "readinessProbe": {"tcpSocket": {"port": 18080}, "periodSeconds": 2, "timeoutSeconds": 2},
                                        "resources": {"requests": {"cpu": "25m", "memory": "32Mi"},
                                                      "limits": {"cpu": "250m", "memory": "128Mi"}}}]}}
        process = None
        traffic_complete = False
        expected_uids = {}
        try:
            for kind, resource in (("networkpolicy", policy), ("pod", pod)):
                created = self.kube("create", "-f", "-", "-o", "json", data=json.dumps(resource).encode())
                expected_uids[kind] = json.loads(created.stdout)["metadata"]["uid"]
            self.kube("wait", "--for=condition=Ready", "pod/" + name, "--timeout=180s")
            port = forwards.enter_context(self.forward("pod/" + name, 18080))
            code = sampler_program(stop, started)
            # Host sampler credentials enter stdin only; relay command has no authentication data.
            process = subprocess.Popen([sys.executable, "-c", code], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
            config = {"service": "127.0.0.1", "port": port, "host": self.f["gateway_host"],
                      "path": self.f["load_path"], "api_key": self.api_key,
                      "body": self.f.get("backend_body", "fluxgate-resilience-ok"),
                      "positive_path": str(positive), "progress_path": str(progress)}
            process.stdin.write((json.dumps(config) + "\n").encode())
            process.stdin.close()
            process.stdin = None
            for _ in range(60):
                require(process.poll() is None, "credential sampler exited before mutation")
                if positive.exists() and positive.read_bytes() == b"yes":
                    break
                time.sleep(.1)
            else:
                raise ProofError("credential sampler did not start")
            yield
        finally:
            try:
                if process:
                    private_write(stop, "stop")
                    output, _ = process.communicate(timeout=15)
                    require(process.returncode == 0, "credential sampler failed")
                    require(self.api_key.encode() not in output, "credential sampler leaked secret; evidence suppressed")
                    report = json.loads(output)
                    report["transport"] = "host Python + loopback API port-forward + owned relay to Gateway Service"
                    report["sampler_runtime"] = {"python": sys.version.split()[0],
                        "program_sha256": hashlib.sha256(code.encode()).hexdigest(),
                        "relay_sha256": hashlib.sha256(relay.encode()).hexdigest(),
                        "relay_resources": pod["spec"]["containers"][0]["resources"]}
                    # Preserve screened raw evidence even when reporter/drain validation fails.
                    private_write(self.work / (phase + "-traffic.json"), json.dumps(report, indent=2) + "\n")
                    report = sampler_summary(report)
                    self.results.setdefault("rotation_traffic", {})[phase] = report
                    traffic_complete = True
            finally:
                try:
                    if process and not traffic_complete:
                        self.preserve_sampler_progress(name, phase)
                    if process and process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
                finally:
                    try:
                        forwards.close()  # Remains open through host drain/reap, even on protocol failure.
                    finally:
                        cleanup = self.cleanup_sampler_resources(name, expected_uids)
                        if phase in self.results.get("rotation_traffic", {}):
                            self.results["rotation_traffic"][phase]["cleanup"] = cleanup
                            private_write(self.work / (phase + "-traffic.json"),
                                          json.dumps(self.results["rotation_traffic"][phase], indent=2) + "\n")


    def redis_cold_restart(self, password, retired_password):
        chosen = None
        rule_set = self.f["rule_set_id"]
        profiles = ((self.f["quota_rule_id"], "daily", b"1", b"86400000000"),
                    (self.f["load_rule_id"], "hourly", b"3", b"3600000000"))
        require(all(re.fullmatch(r"[A-Za-z0-9_-]+", identifier)
                    for identifier in (rule_set, *(profile[0] for profile in profiles))),
                "fixture policy identifiers cannot safely select Redis metadata")
        for pod in self.f["redis_pods"]:
            if not self.redis(pod, ["ROLE"], password).startswith(b"slave\n"):
                continue
            for rule_id, band, algorithm, window in profiles:
                # RedisTokenBucketStore.metadataKey prefixes the complete bucket key.
                prefix = "fluxgate:policy:fluxgate:bucket:{" + rule_set + ":" + rule_id + ":"
                shape = re.compile(re.escape(prefix) + r"[^{}]+}:" + band + r"(?::epoch:[A-Za-z0-9_-]+)?")
                cursor = "0"
                for _ in range(1000):
                    scan = self.redis(pod, ["SCAN", cursor, "MATCH", prefix + "*}:" + band + "*",
                                           "COUNT", "100"], password, readonly=True).decode().splitlines()
                    require(bool(scan) and scan[0].isdigit(), "invalid Redis metadata SCAN response")
                    cursor = scan[0]
                    for key in scan[1:]:
                        if not shape.fullmatch(key) or self.redis(pod, ["TYPE", key], password, readonly=True) != b"hash":
                            continue
                        value = self.redis(pod, ["HGETALL", key], password, readonly=True)
                        fields = redis_hash_contents(value)
                        if (set(fields) != {b"revision", b"capacity", b"window_micros", b"algorithm"}
                                or not fields[b"revision"].isdigit() or not fields[b"capacity"].isdigit()
                                or int(fields[b"capacity"]) <= 0 or fields[b"algorithm"] != algorithm
                                or fields[b"window_micros"] != window):
                            continue
                        ttl = self.redis(pod, ["TTL", key], password, readonly=True)
                        require(ttl.lstrip(b"-").isdigit(), "invalid Redis metadata TTL")
                        # Recovery can take 300s plus 180s topology reconciliation.
                        if int(ttl) > 600:
                            chosen = (pod, key, value)
                            break
                    if chosen or cursor == "0":
                        break
                else:
                    raise ProofError("Redis metadata SCAN exceeded bounded fixture selection")
                if chosen:
                    break
            if chosen:
                break
        require(chosen is not None, "no replica with real persisted policy metadata for cold restart")
        pod, key, value = chosen
        before = self.get("pod/" + pod)
        claims = [v["persistentVolumeClaim"]["claimName"] for v in before["spec"]["volumes"] if "persistentVolumeClaim" in v]
        require(bool(claims), "Redis member has no persistent data claim")
        pvcs = {claim: self.get("pvc/" + claim) for claim in claims}
        node_id = self.redis(pod, ["CLUSTER", "MYID"], password)
        sentinel = "/data/.credential-restart-" + secrets.token_hex(8)
        self.cold_sentinels.append((pod, sentinel))
        self.kube("exec", pod, "--", "sh", "-c", 'head -c 32 /dev/urandom > "$1"', "sentinel", sentinel)
        content = self.kube("exec", pod, "--", "cat", sentinel).stdout
        require(len(content) == 32, "restart data sentinel must contain exactly 32 bytes")
        started = time.monotonic()
        self.kube("delete", "pod/" + pod, "--wait=true")
        for _ in range(300):
            try:
                fresh = self.get("pod/" + pod)
                ready = any(c["type"] == "Ready" and c["status"] == "True" for c in fresh.get("status", {}).get("conditions", []))
                if fresh["metadata"]["uid"] != before["metadata"]["uid"] and ready:
                    self.redis_check(pod, password, True)
                    info = self.redis(pod, ["INFO", "replication"], password)
                    if b"role:slave" in info and b"master_link_status:up" in info:
                        break
            except ProofError:
                pass
            time.sleep(1)
        else:
            raise ProofError("Redis cold member failed to recover with new Secret credential")
        require(self.redis(pod, ["CLUSTER", "MYID"], password) == node_id, "cold restart lost persisted Redis node identity")
        require(redis_hash_contents(self.redis(pod, ["HGETALL", key], password, readonly=True)) ==
                redis_hash_contents(value), "cold restart lost real policy metadata")
        require(self.kube("exec", pod, "--", "cat", sentinel).stdout == content, "cold restart lost mounted data")
        validate_restart_identity(before, fresh, pvcs, {claim: self.get("pvc/" + claim) for claim in pvcs})
        self.redis_check(pod, retired_password, False)
        self.redis_check(pod, secrets.token_urlsafe(40), False)
        self.redis_check(pod, None, False)
        self.redis_check(pod, password, True)
        restored = False
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            restored = True
            for member in self.f["redis_pods"]:
                info = self.redis(member, ["CLUSTER", "INFO"], password)
                nodes = self.redis(member, ["CLUSTER", "NODES"], password)
                lines = [line.split() for line in nodes.decode().splitlines() if line.startswith(node_id.decode() + " ")]
                restored = restored and b"cluster_state:ok" in info and (
                    "cluster_known_nodes:" + str(len(self.f["redis_pods"]))).encode() in info and len(lines) == 1
                if lines:
                    restored = restored and len(lines[0]) >= 8 and lines[0][7] == "connected" and (
                        lines[0][1].startswith(fresh["status"]["podIP"] + ":")) and not any(
                            flag in lines[0][2].split(",") for flag in ("fail", "fail?", "noaddr", "handshake"))
            if restored:
                break
            time.sleep(1)
        require(restored, "Redis topology did not converge to cold member's new address")
        self.available()
        self.kube("exec", pod, "--", "sh", "-c", 'rm -- "$1"', "sentinel", sentinel)
        self.cold_sentinels.remove((pod, sentinel))
        return {"replica": pod, "before_uid": before["metadata"]["uid"], "after_uid": fresh["metadata"]["uid"],
                "pvc_uids": {c: v["metadata"]["uid"] for c, v in pvcs.items()}, "redis_node_id_preserved": True,
                "policy_metadata_preserved": True, "mounted_data_sha256": hashlib.sha256(content).hexdigest(),
                "new_correct_accepted": True, "retired_wrong_missing_rejected": True,
                "replication_link_up": True, "cluster_restored": True, "gateway_positive": True,
                "recovery_seconds": time.monotonic() - started}

    def stores(self):
        uri = self.read("mongo_uri_file")
        admin = self.read("mongo_admin_uri_file")
        parsed = urlsplit(uri)
        # Mongo URI may include multiple hosts; preserve complete authority after '@'.
        hosts = parsed.netloc.rsplit("@", 1)[-1]
        username = unquote(parsed.username or "fluxgate")
        protected_read = "c.getDB('fluxgate').getCollection('rate_limit_rules').findOne()"
        self.mongo(uri, protected_read)
        wrong_uri = urlunsplit(parsed._replace(netloc=quote(username) + ":" + secrets.token_hex(24) + "@" + hosts))
        missing_uri = urlunsplit(parsed._replace(netloc=hosts))
        self.mongo(wrong_uri, protected_read, False)
        self.mongo(missing_uri, protected_read, False)
        old_password = self.read("redis_password_file")
        require(len(old_password) >= 32 and len(unquote(parsed.password or "")) >= 32,
                "initial store credentials must be strong generated values")
        for pod in self.f["redis_pods"]:
            self.redis_check(pod, old_password, True)
            self.redis_check(pod, secrets.token_hex(24), False)
            self.redis_check(pod, None, False)
        new_user, new_mongo_password = "fluxgate_rotation_" + secrets.token_hex(4), secrets.token_urlsafe(40)
        auth_db = self.f.get("mongo_auth_database", dict(parse_qsl(parsed.query)).get("authSource", "admin"))
        self.store_rollback = {"old_uri": uri, "admin": admin, "auth_db": auth_db,
                               "old_password": old_password, "new_password": None,
                               "files": {Path(self.f[field]): Path(self.f[field]).read_bytes()
                                         for field in ("mongo_uri_file", "redis_uri_file", "redis_password_file",
                                                       "mongo_app_password_file") if self.f.get(field)},
                               "fixture": self.fixture_path.read_bytes(),
                               "new_user": new_user, "ownership": secrets.token_hex(24)}
        self.perform("stores.mongo-overlap", self.mongo, admin, "c.getDB(" + json.dumps(auth_db) + ").createUser({user:" + json.dumps(new_user) +
                   ",pwd:" + json.dumps(new_mongo_password) + ",customData:" +
                   json.dumps({"fluxgateCredentialProof": self.store_rollback["ownership"]}) + ",roles:[{role:'readWrite',db:'fluxgate'}]})")
        new_uri = urlunsplit(parsed._replace(netloc=quote(new_user) + ":" + quote(new_mongo_password) + "@" + hosts))
        self.mongo(new_uri, protected_read)
        self.mongo(uri, protected_read)  # overlap: both users still work over new connections
        new_redis_password = secrets.token_urlsafe(40)
        self.store_rollback["new_password"] = new_redis_password
        for pod in self.f["redis_pods"]:
            output = self.perform("stores.redis-overlap", self.redis, pod, ["ACL", "SETUSER", "default", ">" + new_redis_password], old_password)
            require(b"OK" in output and b"ERR" not in output, "Redis overlapping password add failed")
            self.redis_check(pod, new_redis_password, True)
            self.redis_check(pod, old_password, True)
        redis_uri = self.read("redis_uri_file")
        # All comma-separated seed URIs need the same newly accepted credential.
        new_redis_uri = ",".join(urlunsplit(urlsplit(seed)._replace(
            netloc=":" + quote(new_redis_password) + "@" + urlsplit(seed).netloc.rsplit("@", 1)[-1]))
                                 for seed in redis_uri.split(","))
        self.perform("stores.app-switch", self.patch_data, "secret", self.f["credentials_secret"],
                        {"mongo-app-password": new_mongo_password, "redis-password": new_redis_password,
                         "fluxgate.mongo.uri": new_uri, "fluxgate.redis.uri": new_redis_uri})
        self.perform("stores.app-roll", self.rollout)
        self.available()
        # Update replication password before retiring the old default-user password.
        for pod in self.f["redis_pods"]:
            output = self.redis(pod, ["CONFIG", "SET", "masterauth", new_redis_password], new_redis_password)
            require(b"OK" in output and b"ERR" not in output, "Redis replication credential update failed")
        self.perform("stores.retire-mongo", self.mongo, admin, "const removed=c.getDB(" + json.dumps(auth_db) + ").dropUser(" + json.dumps(username) +
                   "); if(!removed) throw Error('user retirement failed')")
        self.mongo(uri, protected_read, False)
        self.mongo(new_uri, protected_read)
        for pod in self.f["redis_pods"]:
            output = self.perform("stores.retire-redis", self.redis, pod, ["ACL", "SETUSER", "default", "resetpass", ">" + new_redis_password],
                                new_redis_password)
            require(b"OK" in output and b"ERR" not in output, "Redis old password retirement failed")
            self.redis_check(pod, old_password, False)
            self.redis_check(pod, new_redis_password, True)
        cold_restart = self.perform("stores.cold-restart", self.redis_cold_restart, new_redis_password, old_password)
        self.available()
        private_write(self.f["mongo_uri_file"], new_uri)
        if self.f.get("mongo_app_password_file"):
            private_write(self.f["mongo_app_password_file"], new_mongo_password)
        self.f["mongo_app_user"] = new_user
        private_write(self.fixture_path, json.dumps(self.f, indent=2) + "\n")
        private_write(self.f["redis_uri_file"], new_redis_uri)
        private_write(self.f["redis_password_file"], new_redis_password)
        self.results["stores"] = {"mongo": {"valid": True, "wrong_rejected": True, "missing_rejected": True,
                                             "overlap_users": True, "old_user_retired": True},
                                  "redis": {"nodes": len(self.f["redis_pods"]), "valid": True,
                                            "wrong_rejected": True, "missing_rejected": True,
                                            "overlap_passwords": True, "old_password_retired": True,
                                            "cold_restart": cold_restart},
                                  "new_connections_and_replaced_app_pods": True}
        self.backups.clear()
        self.store_rollback = None

    def jwt(self):
        jar = Path(self.f.get("studio_jar", "/Users/jaeseoh/Documents/workspace/openfluxgate/"
                             "fluxgate-studio-envoy-hardening/fluxgate-studio-admin-api/target/"
                             "fluxgate-studio-admin-api-0.0.1-SNAPSHOT.jar"))
        require(jar.is_file(), "fresh Studio JAR required")
        keys = []
        for kid in ("old-" + secrets.token_hex(6), "new-" + secrets.token_hex(6)):
            key = self.work / (kid + ".key")
            run([self.openssl, "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                 "-out", str(key)])
            modulus = run([self.openssl, "rsa", "-in", str(key), "-noout", "-modulus"]).stdout
            public = {"kty": "RSA", "kid": kid, "use": "sig", "alg": "RS256",
                      "n": b64url(bytes.fromhex(modulus.decode().strip().split("=", 1)[1])),
                      "e": b64url((65537).to_bytes(3, "big"))}
            keys.append((key, public))
        jwks = {"keys": [keys[0][1]]}
        fetches = []

        class JWKS(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/jwks":
                    self.send_error(404)
                    return
                fetches.append(time.monotonic())
                data = json.dumps(jwks).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), JWKS)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        issuer = "http://127.0.0.1:" + str(server.server_port) + "/local-proof"
        audience = "fluxgate-studio-admin-api"
        expiry = int(time.time()) + 1800

        def token(index, kid=None, aud=audience, token_issuer=issuer, expires_at=expiry, algorithm="RS256"):
            return signed_jwt(self.openssl, keys[index][0], kid or keys[index][1]["kid"],
                              token_issuer, aud, expires_at, algorithm)

        old_token, new_token = token(0), token(1)
        admin = self.read("mongo_admin_uri_file")
        output = self.mongo(admin, "print('PRIMARY:'+c.getDB('admin').hello().primary)")
        primary_host = mongo_marker(output, "PRIMARY:")
        primary = primary_host.split(".")[0]
        require(primary in self.f["mongo_pods"], "Mongo primary does not belong to isolated fixture")
        events = {}
        try:
            with self.forward("pod/" + primary, 27017) as mongo_port:
                parsed = urlsplit(self.read("mongo_uri_file"))
                userinfo = parsed.netloc.rsplit("@", 1)[0]
                query = dict(parse_qsl(parsed.query))
                query.pop("replicaSet", None)
                query["directConnection"] = "true"
                mongo = urlunsplit(parsed._replace(netloc=userinfo + "@127.0.0.1:" + str(mongo_port),
                                                   query=urlencode(query)))
                studio_port = unused_port()
                path = "/api/rule-sets/" + quote(self.f.get("rule_set_id", "resilience-limits"), safe="") + "/active"

                def api(bearer):
                    connection = http.client.HTTPConnection("127.0.0.1", studio_port, timeout=10)
                    try:
                        headers = {"Connection": "close"}
                        if bearer:
                            headers["Authorization"] = "Bearer " + bearer
                        connection.request("GET", path, headers=headers)
                        response = connection.getresponse()
                        body = response.read()
                        return response.status, json.loads(body) if response.status == 200 else None
                    finally:
                        connection.close()

                @contextlib.contextmanager
                def app(stem):
                    env = dict(os.environ)
                    env.update({"SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(studio_port),
                                "KEYCLOAK_ISSUER_URI": issuer,
                                "KEYCLOAK_JWK_SET_URI": issuer.rsplit("/", 1)[0] + "/jwks",
                                "STUDIO_JWT_VALIDATE_AUDIENCE": "true", "STUDIO_JWT_AUDIENCE": audience,
                                "SPRING_AUTOCONFIGURE_EXCLUDE": "org.fluxgate.control.autoconfigure.ControlSupportAutoConfiguration",
                                "FLUXGATE_MONGO_URI": mongo, "FLUXGATE_MONGO_DB": "fluxgate",
                                "LOGGING_LEVEL_ORG_FLUXGATE": "INFO"})
                    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
                    with open(self.work / (stem + ".log"), "wb") as log:
                        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=log)
                        try:
                            for _ in range(120):
                                require(process.poll() is None, "Studio exited before readiness")
                                try:
                                    if api(new_token if stem == "studio-cold" else old_token)[0] == 200:
                                        break
                                except (OSError, http.client.HTTPException):
                                    pass
                                time.sleep(.5)
                            else:
                                raise ProofError("Studio JWT protected active endpoint did not become ready")
                            yield
                        finally:
                            process.terminate()
                            try:
                                process.wait(timeout=15)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=5)

                with app("studio-warm"):
                    status, active = api(old_token)
                    require(status == 200, "old JWT not accepted initially")
                    events["old_initial"] = status
                    negatives = {
                        "missing": None,
                        "wrong_audience": token(0, aud="wrong-api"),
                        "expired": token(0, expires_at=int(time.time()) - 120),
                        "wrong_issuer": token(0, token_issuer=issuer + "/untrusted"),
                        # The trusted old kid names the wrong signing key: this is a real bad signature.
                        "bad_signature": token(1, kid=keys[0][1]["kid"]),
                        "wrong_algorithm": token(0, algorithm="RS512"),
                    }
                    events["negative_controls"] = {}
                    for label, bearer in negatives.items():
                        require(api(bearer)[0] == 401, "JWT negative accepted: " + label)
                        require(api(old_token)[0] == 200, "valid JWT failed after negative: " + label)
                        events["negative_controls"][label] = 401
                    jwks["keys"] = [keys[0][1], keys[1][1]]
                    require(api(new_token)[0] == 200 and api(old_token)[0] == 200,
                            "JWKS overlap rejected valid signature")
                    events["overlap_new"] = 200
                    events["overlap_old"] = 200
                    jwks["keys"] = [keys[1][1]]
                    warm = api(old_token)[0]
                    require(warm in (200, 401), "unexpected warm decoder retired-key response")
                    events["retired_before_cache_refresh"] = warm
                    before = len(fetches)
                    require(api(token(1, kid="unknown-" + secrets.token_hex(8)))[0] == 401,
                            "unknown JWT kid accepted")
                    require(len(fetches) > before, "unknown-kid probe did not actually refresh JWKS")
                    require(api(old_token)[0] == 401, "retired JWT accepted after observed JWKS refresh")
                    require(api(new_token)[0] == 200, "new JWT rejected after retirement")
                    events["retired_after_observed_refresh"] = 401
                with app("studio-cold"):
                    require(int(time.time()) < expiry, "old JWT expired before retirement proof")
                    require(api(old_token)[0] == 401, "retired JWT accepted by fresh decoder")
                    status, final = api(new_token)
                    require(status == 200, "new JWT rejected by fresh decoder")
                    for field in ("revision", "counterEpoch", "snapshotId"):
                        require(active[field] == final[field], "JWT proof mutated published policy")
                    events["retired_cold_decoder"] = 401
                    events["new_cold_decoder"] = 200
                events.update({"jwks_fetches": len(fetches), "old_token_unexpired": True,
                               "documented_cache_ttl_seconds": 300,
                               "documented_cache_refresh_lock_timeout_seconds": 15,
                               "documented_refresh_ahead_enabled": False,
                               "cache_source": "Spring Security 6.5.11 JWKSourceBuilder / Nimbus 9.37.4",
                               "cache_scope": "default Nimbus cache; lock timeout is not a periodic refresh or revocation bound",
                               "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                               "new_connections": True, "publication_unchanged": True})
                self.results["jwt_jwks"] = events
        finally:
            server.shutdown()
            server.server_close()

    def remove_owned_mongo_user(self, state):
        if not state.get("new_user") or not state.get("ownership"):
            return
        user, owner = json.dumps(state["new_user"]), json.dumps(state["ownership"])
        database = "c.getDB(" + json.dumps(state["auth_db"]) + ")"
        output = self.mongo(state["admin"], "const u=" + database + ".getUser(" + user +
                            "); print('OWNERSHIP:'+JSON.stringify(u ? u.customData || null : null))")
        metadata = json.loads(mongo_marker(output, "OWNERSHIP:"))
        if not isinstance(metadata, dict) or metadata.get("fluxgateCredentialProof") != state["ownership"]:
            return
        # Recheck ownership on the deleting connection, including a changed user between reads.
        self.mongo(state["admin"], "const d=" + database + "; const u=d.getUser(" + user +
                   "); if(u) { if(!u.customData || u.customData.fluxgateCredentialProof!==" + owner +
                   ") throw Error('overlap ownership changed'); if(!d.dropUser(" + user +
                   ")) throw Error('overlap user cleanup failed'); }")

    def cleanup_failed(self):
        failures = []
        for pod, sentinel in self.cold_sentinels:
            try:
                result = self.kube("exec", pod, "--", "sh", "-c", 'rm -- "$1"', "sentinel", sentinel, check=False)
                if result.returncode:
                    failures.append("cold-restart-sentinel-cleanup")
            except Exception:
                failures.append("cold-restart-sentinel-cleanup")
        for path, original in (getattr(self, "api_rollback", None) or {}).items():
            try:
                if original is None:
                    path.unlink(missing_ok=True)
                else:
                    contents, mode = original
                    private_write(path, contents)
                    os.chmod(path, mode)
            except Exception:
                failures.append("private-api-file-restore")
        if self.tls_rollback:
            for path, contents in self.tls_rollback.items():
                try:
                    private_write(path, contents)
                except Exception:
                    failures.append("private-tls-file-restore")
        if self.store_rollback:
            state = self.store_rollback
            for path, contents in state["files"].items():
                try:
                    private_write(path, contents)
                except Exception:
                    failures.append("private-credential-file-restore")
            try:
                private_write(self.fixture_path, state["fixture"])
            except Exception:
                failures.append("fixture-metadata-restore")
            parsed = urlsplit(state["old_uri"])
            for pod in self.f["redis_pods"]:
                try:
                    output = self.redis(pod, ["ACL", "SETUSER", "default", "resetpass",
                                             ">" + state["old_password"]],
                                        state["new_password"] or state["old_password"])
                    if b"OK" not in output:
                        output = self.redis(pod, ["ACL", "SETUSER", "default", "resetpass",
                                                 ">" + state["old_password"]], state["old_password"])
                    require(b"OK" in output, "Redis rollback failed")
                    self.redis(pod, ["CONFIG", "SET", "masterauth", state["old_password"]], state["old_password"])
                except Exception:
                    failures.append("redis-credential-restore")
            try:
                user = unquote(parsed.username)
                password = unquote(parsed.password)
                self.mongo(state["admin"], "const d=c.getDB(" + json.dumps(state["auth_db"]) +
                           "); if(!d.getUser(" + json.dumps(user) + ")) d.createUser({user:" +
                           json.dumps(user) + ",pwd:" + json.dumps(password) +
                           ",roles:[{role:'readWrite',db:'fluxgate'}]})")
                self.mongo(state["old_uri"], "c.getDB('fluxgate').getCollection('rate_limit_rules').findOne()")
                self.remove_owned_mongo_user(state)
            except Exception:
                failures.append("mongo-credential-restore")
        for (namespace, kind, name), data in reversed(list(self.backups.items())):
            try:
                self.kube("patch", kind, name, "--type=json", "--patch-file=/dev/stdin",
                          data=json.dumps([{"op": "replace", "path": "/data", "value": data}]).encode(),
                          namespace=namespace)
            except Exception:
                failures.append("kubernetes-secret-restore")
        if self.backups:
            try:
                self.rollout()
                self.envoy_rollout()
            except Exception:
                failures.append("replacement-pod-restore")
        if failures:
            print("Cleanup incomplete: " + ",".join(failures), flush=True)


def sampler_cleanup_self_test():
    import io
    with tempfile.TemporaryDirectory(prefix="credential-cleanup-unit-") as directory:
        for pod_delete_fails, stop_fails, retained_pod in ((True, False, False), (False, True, False), (False, False, True)):
            proof = Proof.__new__(Proof)
            proof.ns, proof.work, proof.api_key = "fluxgate-resilience", Path(directory), "offline-only-key"
            proof.results = {}
            proof.forward = lambda *args, **kwargs: contextlib.nullcontext(12345)
            proof.f = {"gateway_service": "gateway", "gateway_namespace": "envoy-gateway-system",
                       "kubeconfig": "offline", "context": "kind-fluxgate-resilience",
                       "gateway_host": "local", "load_path": "/load", "backend_body": "marker"}
            proof.get = lambda *args: {"spec": {"ports": [{"port": 80, "targetPort": 10080}], "selector": {"app": "gateway"}}}
            resources = {}
            deletions = []
            baseline = {"scheduled": 1, "omitted_schedules": 0,
                        "samples": [{"status": 200, "body_valid": True}]}
            def kube(*args, data=None, check=True, **kwargs):
                status, output = 0, b""
                if args[0] == "create":
                    obj = json.loads(data)
                    kind = "pod" if obj["kind"] == "Pod" else "networkpolicy"
                    obj["metadata"]["uid"] = kind + "-uid"
                    resources[kind] = obj
                    output = json.dumps(obj).encode()
                elif args[0] == "get":
                    kind = args[1].split("/")[0]
                    output = json.dumps(resources[kind]).encode() if kind in resources else b""
                elif args[0] == "delete":
                    kind = args[1].split("/")[0]
                    deletions.append(kind)
                    if kind == "pod" and pod_delete_fails:
                        status = 1
                    elif not (kind == "pod" and retained_pod):
                        resources.pop(kind, None)
                elif args[0] == "exec" and "touch" in args and stop_fails:
                    status = 1
                elif args[0] == "exec" and "cat" in args:
                    output = json.dumps({"progress_schema": 1, "partial": True, "complete": False,
                        "interval_ms": 100, "scheduled": 1, "completed": 1, "pending": 0,
                        "pending_sequences": [], "omitted_schedules": 0, "status_counts": {"200": 1},
                        "worker_failures": 0, "progress_writer_failures": 0,
                        "captured_utc_ns": 1, "captured_monotonic_ns": 1}).encode()
                if status and check:
                    raise ProofError("offline sampler stop failure")
                return subprocess.CompletedProcess(args, status, output, b"")
            proof.kube = kube
            class Process:
                def __init__(self, *args, **kwargs):
                    class Input(io.BytesIO):
                        def write(inner, data):
                            config = json.loads(data)
                            original_write(config["positive_path"], "yes")
                            original_write(config["progress_path"], json.dumps({"progress_schema": 1,
                                "partial": True, "complete": False, "interval_ms": 100,
                                "scheduled": 1, "completed": 1, "pending": 0,
                                "pending_sequences": [], "omitted_schedules": 0,
                                "status_counts": {"200": 1}, "worker_failures": 0,
                                "progress_writer_failures": 0, "captured_utc_ns": 1,
                                "captured_monotonic_ns": 1}))
                            return super().write(data)
                    self.stdin, self.returncode = Input(), None
                def poll(self):
                    return self.returncode
                def communicate(self, **kwargs):
                    self.returncode = 0
                    return json.dumps(baseline).encode(), b""
                def terminate(self):
                    self.returncode = -15
                def wait(self, **kwargs):
                    return self.returncode
                def kill(self):
                    self.returncode = -9
            original_popen, original_write = subprocess.Popen, private_write
            def offline_write(path, content):
                if stop_fails and Path(path).name == "stop":
                    raise ProofError("offline sampler stop failure")
                return original_write(path, content)
            subprocess.Popen = Process
            globals()["private_write"] = offline_write
            passed = False
            try:
                try:
                    with proof.traffic_sampler("unit"):
                        pass
                    passed = True
                except ProofError:
                    pass
            finally:
                subprocess.Popen = original_popen
                globals()["private_write"] = original_write
            require(not passed, "sampler stop/cleanup failure permitted PASS")
            require(deletions == ["pod", "networkpolicy"], "cleanup did not attempt both resources independently")
            require("networkpolicy" not in resources, "policy cleanup was skipped after failure")
            if stop_fails:
                require((Path(directory) / "unit-traffic-partial.json").exists(), "failed sampler lost private progress")
                require("pod" not in resources, "stop failure blocked Pod cleanup")
            else:
                require("pod" in resources, "offline deletion failure was not exercised")
                require(bool(list(Path(directory).glob("*-cleanup-failed.json"))), "cleanup failure diagnostics missing")


def failed_rotation_cleanup_self_test():
    """Exercise partial local writes and ownership-bound Mongo cleanup without live services."""
    import io
    with tempfile.TemporaryDirectory(prefix="credential-rollback-unit-") as directory:
        for retained_exists in (False, True):
            proof = Proof.__new__(Proof)
            proof.work = Path(directory) / str(retained_exists)
            proof.work.mkdir()
            mapping = proof.work / "mapping"
            original = b'{"fluxgate":{"envoy":{"api-keys":[]}}}\n'
            private_write(mapping, original)
            os.chmod(mapping, 0o640)
            retained = proof.work / "retained-api-key"
            if retained_exists:
                private_write(retained, b"original-retained")
                os.chmod(retained, 0o640)
            proof.f = {"api_key_mapping_file": str(mapping), "quota_path": "/quota", "load_path": "/load"}
            proof.results, proof.backups = {}, {("local", "secret", "mapping"): {}}
            proof.store_rollback, proof.tls_rollback, proof.cold_sentinels = None, None, []
            proof.policy_stamp = lambda: {"revision": 1}
            proof.api_key_pod_barrier = lambda *args: None
            proof.api_mapping = lambda entries: json.dumps({"fluxgate": {"envoy": {"api-keys": entries}}})
            statuses = iter((200, 200, 200, 403, 200, 200, 429, 403, 403))
            proof.gateway = lambda *args: next(statuses)
            counters = iter(({"bucket": b"before"}, {"bucket": b"overlap"}, {"bucket": b"final"}))
            proof.counter_snapshot = lambda *args: next(counters)
            def fail_remote(*args, **kwargs):
                raise ProofError("offline Secret restore failure")
            proof.kube, proof.rollout, proof.envoy_rollout = fail_remote, lambda: None, lambda: None
            writer = globals()["private_write"]
            def partial_write(path, content):
                writer(path, content)
                if Path(path) == retained:
                    raise ProofError("offline retained-key partial write failure")
            globals()["private_write"] = partial_write
            try:
                try:
                    proof.api_keys()
                except ProofError:
                    pass
                else:
                    raise ProofError("partial write negative did not execute")
            finally:
                globals()["private_write"] = writer
            with contextlib.redirect_stdout(io.StringIO()):
                proof.cleanup_failed()
            require(mapping.read_bytes() == original and mapping.stat().st_mode & 0o777 == 0o640,
                    "API partial write failed to restore original mapping bytes/mode independently")
            require((retained.read_bytes() == b"original-retained" and retained.stat().st_mode & 0o777 == 0o640)
                    if retained_exists else not retained.exists(), "API partial write lost retained-key bytes/mode/absence")
            # A local restore failure must not skip the independently captured key file.
            private_write(retained, b"partial-again")
            def failed_mapping_restore(path, content):
                if Path(path) == mapping:
                    raise ProofError("offline local mapping restore failure")
                writer(path, content)
            globals()["private_write"] = failed_mapping_restore
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    proof.cleanup_failed()
            finally:
                globals()["private_write"] = writer
            require(retained.read_bytes() == b"original-retained" if retained_exists else not retained.exists(),
                    "failed mapping restore skipped independent key cleanup")
            # Successful completion commits this phase; later failures must not undo it.
            statuses = iter((200, 200, 200, 403, 200, 200, 429, 403, 403))
            counters = iter(({"bucket": b"before"}, {"bucket": b"overlap"}, {"bucket": b"final"}))
            proof.api_keys()
            committed = mapping.read_bytes(), retained.read_bytes()
            proof.cleanup_failed()
            require((mapping.read_bytes(), retained.read_bytes()) == committed,
                    "completed API phase was rolled back by later cleanup")
        for owned, old_auth_ok in ((True, True), (False, True), (True, False)):
            proof = Proof.__new__(Proof)
            proof.backups, proof.tls_rollback, proof.cold_sentinels = {}, None, []
            proof.fixture_path = Path(directory) / "fixture"
            proof.f = {"redis_pods": []}
            proof.store_rollback = {"files": {}, "fixture": b"{}", "old_uri": "mongodb://old:offline@local/fluxgate",
                                    "admin": "admin-offline", "auth_db": "fluxgate", "old_password": "offline",
                                    "new_password": None, "new_user": "new-owned", "ownership": "owned-marker"}
            calls = []
            def mongo(uri, javascript, *args):
                if uri == proof.store_rollback["old_uri"]:
                    calls.append("old-auth")
                    require(old_auth_ok, "offline old auth rejection")
                elif "OWNERSHIP:" in javascript:
                    calls.append("ownership")
                    return ('OWNERSHIP:' + json.dumps({"fluxgateCredentialProof": "owned-marker" if owned else "foreign"})).encode()
                elif "dropUser" in javascript:
                    calls.append("drop")
                    require("fluxgateCredentialProof" in javascript and "owned-marker" in javascript,
                            "Mongo deletion lacks final ownership check")
                else:
                    calls.append("restore-old")
                return b"AUTH_PROBE_OK"
            proof.mongo = mongo
            with contextlib.redirect_stdout(io.StringIO()):
                proof.cleanup_failed()
            require(calls[:2] == ["restore-old", "old-auth"], "Mongo overlap cleanup did not verify restored old auth")
            require(("drop" in calls) == (owned and old_auth_ok), "Mongo cleanup deleted foreign user or left owned overlap")
            proof.store_rollback = None
            calls.clear()
            proof.cleanup_failed()
            require(not calls, "completed store phase deleted its active user")


def backend_body_self_test():
    """Exercise the gateway's real 200 control against fake responses containing the marker."""
    proof = Proof.__new__(Proof)
    proof.f = {"gateway_service": "offline", "gateway_namespace": "offline",
               "gateway_host": "offline", "load_path": "/load", "backend_body": "marker"}
    proof.forward = lambda *args: contextlib.nullcontext(1)
    original = http.client.HTTPConnection
    class Connection:
        def __init__(self, *args, **kwargs):
            pass
        def request(self, *args, **kwargs):
            pass
        def getresponse(self):
            return self
        def read(self):
            return body
        def close(self):
            pass
        status = 200
    http.client.HTTPConnection = Connection
    try:
        for body in (b"marker", b"marker\n"):
            require(proof.gateway() == 200, "gateway rejected exact backend/LF positive")
        for body in (b"prefix-marker", b"marker-suffix", b"marker\n\n", b"marker\r\n", b"junk\nmarker\njunk"):
            try:
                proof.gateway()
            except ProofError:
                pass
            else:
                raise ProofError("gateway accepted fake 200 containing backend marker")
    finally:
        http.client.HTTPConnection = original


def strict_tls_self_test():
    """Validate generated chains and actual localhost mTLS with unchanged SSL verification."""
    import importlib.util
    with tempfile.TemporaryDirectory(prefix="credential-strict-tls-") as directory:
        proof = Proof.__new__(Proof)
        proof.work = Path(directory)
        proof.openssl = "/opt/homebrew/bin/openssl" if Path("/opt/homebrew/bin/openssl").exists() else "openssl"
        ca, key = proof.ca("strict-ca")
        server_cert, server_key = proof.certificate("strict-server", ca, key, "localhost", server=True)
        client, client_key = proof.certificate("strict-client", ca, key, "trusted-gateway")
        wrong, wrong_key = proof.certificate("strict-wrong", ca, key, "wrong-gateway")
        expired, expired_key = proof.certificate("strict-expired", ca, key, "trusted-gateway", expired=True)
        for cert, purpose in ((server_cert, "sslserver"), (client, "sslclient"), (wrong, "sslclient")):
            checked = run([proof.openssl, "verify", "-x509_strict", "-purpose", purpose,
                           "-CAfile", str(ca), str(cert)], check=False)
            require(checked.returncode == 0, "generated chain failed strict purpose verification: " + checked.stderr.decode())
            opposite = "sslclient" if purpose == "sslserver" else "sslserver"
            checked = run([proof.openssl, "verify", "-x509_strict", "-purpose", opposite,
                           "-CAfile", str(ca), str(cert)], check=False)
            require(checked.returncode != 0 and b"unsuitable certificate purpose" in checked.stderr.lower(),
                    "rotated certificate accepted opposite TLS purpose")
        checked = run([proof.openssl, "verify", "-x509_strict", "-CAfile", str(ca), str(expired)], check=False)
        require(checked.returncode != 0 and b"expired" in checked.stderr.lower(), "strict expired control accepted")
        spec = importlib.util.spec_from_file_location("credential_setup", Path(__file__).with_name("setup.py"))
        initial = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(initial)
        initial_dir = proof.work / "initial"
        initial_dir.mkdir(mode=0o700)
        def initial_run(args, **kwargs):
            return run([proof.openssl] + args[1:]).stdout
        initial.generate_tls(initial_dir, "localhost", initial_run)
        for role in ("server", "client"):
            run([proof.openssl, "verify", "-x509_strict", "-purpose", "ssl" + role,
                 "-CAfile", str(initial_dir / (role + "-ca.crt")), str(initial_dir / (role + ".crt"))])
            opposite = "sslclient" if role == "server" else "sslserver"
            checked = run([proof.openssl, "verify", "-x509_strict", "-purpose", opposite,
                           "-CAfile", str(initial_dir / (role + "-ca.crt")), str(initial_dir / (role + ".crt"))], check=False)
            require(checked.returncode != 0 and b"unsuitable certificate purpose" in checked.stderr.lower(),
                    "initial certificate accepted opposite TLS purpose")
        if not ssl.HAS_TLSv1_3:
            return
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                subject = dict(item for group in self.connection.getpeercert()["subject"] for item in group)
                self.send_response(200 if subject.get("commonName") == "trusted-gateway" else 403)
                self.send_header("Content-Length", "0")
                self.end_headers()
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(server_cert), str(server_key))
        context.load_verify_locations(cafile=str(ca))
        context.verify_mode = ssl.CERT_REQUIRED
        server.socket = context.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        proof.f, proof.api_key = {"server_name": "localhost"}, "offline-only-key"
        proof.forward = lambda *args: contextlib.nullcontext(server.server_port)
        try:
            positive = proof.tls_probe_pod("offline", ca, client, client_key)
            require(positive["http"] == 200 and positive["protocol"] == "TLSv1.3", "modern mTLS positive failed")
            require(proof.tls_probe_pod("offline", ca, expected="tls-reject")["tls_rejected"], "missing cert accepted")
            require(proof.tls_probe_pod("offline", ca, expired, expired_key, expected="tls-reject")["tls_rejected"],
                    "expired cert accepted")
            require(proof.tls_probe_pod("offline", ca, wrong, wrong_key, path="/authz", expected=403)["http"] == 403,
                    "wrong client subject accepted")
            require(proof.tls_probe_pod("offline", ca, client, client_key)["http"] == 200,
                    "valid client failed after negatives")
        finally:
            server.shutdown()
            server.server_close()


def credential_acceptance_self_test():
    from unittest.mock import patch
    import io
    clean = {"interval_ms": 100, "scheduled": 1, "omitted_schedules": 0,
             "samples": [{"sequence": 0, "status": 200, "body_valid": True}],
             "cleanup": {"pod_absent": True, "networkpolicy_absent": True, "ownership_checked": True}}
    with tempfile.TemporaryDirectory(prefix="credential-acceptance-test-") as directory:
        output = Path(directory) / "result.json"
        retained = Path(directory) / "committed-credential"
        actions = []
        selected_report = [clean]
        class CompletedProof:
            def __init__(self, fixture):
                self.f, self.ns = {"context": "offline"}, "offline"
                self.results = {}
            def validate(self):
                pass
            def operation(self, label):
                return contextlib.nullcontext()
            @contextlib.contextmanager
            def traffic_sampler(self, phase):
                yield
                self.results.setdefault("rotation_traffic", {})[phase] = json.loads(json.dumps(selected_report[0]))
            def completed(self):
                actions.append("completed")
                private_write(retained, "successfully-rotated-private-value")
            tls = api_keys = stores = jwt = completed
            def cleanup_failed(self):
                actions.append("rollback")
                retained.unlink(missing_ok=True)
        failed = json.loads(json.dumps(clean))
        failed["samples"][0]["status"] = 503
        for phases, report, passed, complete in (("mtls,api-key,stores,jwt", failed, False, True),
                ("mtls,api-key,stores,jwt", clean, True, True), ("stores,jwt", clean, True, False)):
            actions.clear()
            selected_report[0] = report
            exit_code = 0
            with patch.dict(globals(), {"Proof": CompletedProof, "credential_runtime": lambda: {}}), \
                    patch.object(sys, "argv", ["proof", "--fixture", "offline", "--output", str(output), "--phases", phases]), \
                    contextlib.redirect_stdout(io.StringIO()):
                try:
                    main()
                except SystemExit as error:
                    exit_code = error.code
            result = json.loads(output.read_text())
            require(result["passed"] is passed and (exit_code == 0) == passed,
                    "completed credential protocol hid rotation availability failure")
            require(result["protocol_passed"] is True and result["rotation_availability_passed"] is passed
                    and result["complete"] is complete and result["final_acceptance_passed"] is (passed and complete),
                    "credential proof confused protocol, requested availability and final acceptance")
            require("rollback" not in actions and retained.read_text() == "successfully-rotated-private-value",
                    "availability scoring rolled back completed credential rotations")
        for mutation in ("omitted", "wrong-body", "transport", "cleanup", "missing-report", "empty", "accounting", "contradictory-error", "contradictory-transport-error", "pending", "drain", "worker-failure"):
            report = json.loads(json.dumps(clean))
            if mutation == "omitted":
                report["scheduled"], report["omitted_schedules"] = 2, 1
            elif mutation == "wrong-body":
                report["samples"][0]["body_valid"] = False
            elif mutation == "transport":
                report["samples"][0]["status"] = None
            elif mutation == "cleanup":
                report["cleanup"]["pod_absent"] = False
            elif mutation == "empty":
                report["samples"], report["scheduled"] = [], 0
            elif mutation == "accounting":
                report["scheduled"] = 2
            elif mutation == "contradictory-error":
                report["samples"][0]["error"] = "TimeoutError"
            elif mutation == "contradictory-transport-error":
                report["samples"][0]["transport_error"] = "ConnectionResetError"
            elif mutation == "pending":
                report["pending"] = 1
            elif mutation == "drain":
                report["drain_complete"] = False
            elif mutation == "worker-failure":
                report["worker_failures"] = 1
            results = {"rotation_traffic": {"stores": report}} if mutation != "missing-report" else {}
            require(not credential_acceptance(results, ["stores"])["rotation_availability_passed"],
                    "credential acceptance allowed missing/failed/omitted/unclean traffic")
        require(not credential_acceptance({}, ["jwt"])["rotation_availability_passed"],
                "empty availability observations passed vacuously")


def envoy_tls_barrier_self_test():
    from unittest.mock import patch
    def pem(value):
        return b"-----BEGIN CERTIFICATE-----\n" + base64.b64encode(value) + b"\n-----END CERTIFICATE-----\n"
    old, new, client = pem(b"old-ca-der"), pem(b"new-ca-der"), pem(b"client-leaf-der")
    cluster = "securitypolicy/fluxgate-resilience/resilience-ext-auth/extauth/0"
    client_name = "fluxgate-resilience/fluxgate-envoy-client-tls"
    ca_name = "authz-backend-tls/fluxgate-resilience-ca"
    tls = {"@type": "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
           "sni": "authz.local", "common_tls_context": {
        "tls_certificate_sds_secret_configs": [{"name": client_name}],
        "combined_validation_context": {"validation_context_sds_secret_config": {"name": ca_name}}}}
    transport = {"transport_socket": {"typed_config": tls}}
    def secret(name, kind, field, data):
        return {"name": name, "secret": {"name": name, kind: {field: {"inline_bytes": base64.b64encode(data).decode()}}}}
    dump = {"configs": [{"dynamic_active_clusters": [{"cluster": {"name": cluster,
                            "transport_socket_matches": [transport], "transport_socket": {"typed_config": {
                                "@type": "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext",
                                "common_tls_context": {}}}}}]},
                         {"dynamic_active_secrets": [secret(client_name, "tls_certificate", "certificate_chain", client),
                            secret(ca_name, "validation_context", "trusted_ca", new + old),
                            secret("xds_certificate", "tls_certificate", "certificate_chain", pem(b"unrelated"))]}]}
    def validate(value, trust=old+new):
        return loaded_envoy_tls(value, cluster, "authz.local", client_name, ca_name, trust, client)
    evidence = validate(dump)
    require(evidence["ca_fingerprints"] == certificate_fingerprints(old + new), "DER trust sets depended on PEM order")
    def clone():
        return json.loads(json.dumps(dump))
    mutations = []
    wrong_type = clone(); wrong_type["configs"][0]["dynamic_active_clusters"][0]["cluster"]["transport_socket_matches"][0]["transport_socket"]["typed_config"]["@type"] = "unrelated-context"; mutations.append(wrong_type)
    alternate = clone(); alternate["configs"][0]["dynamic_active_clusters"][0]["cluster"]["transport_socket"]["typed_config"] = tls; mutations.append(alternate)
    no_cluster = clone(); no_cluster["configs"][0]["dynamic_active_clusters"] = []; mutations.append(no_cluster)
    missing = clone(); missing["configs"][1]["dynamic_active_secrets"].pop(0); mutations.append(missing)
    binding = clone(); binding["configs"][0]["dynamic_active_clusters"][0]["cluster"]["transport_socket_matches"][0]["transport_socket"]["typed_config"]["common_tls_context"]["tls_certificate_sds_secret_configs"] = [{"name": "xds_certificate"}]; mutations.append(binding)
    sni = clone(); sni["configs"][0]["dynamic_active_clusters"][0]["cluster"]["transport_socket_matches"][0]["transport_socket"]["typed_config"]["sni"] = "wrong.local"; mutations.append(sni)
    warming = clone(); warming["configs"][1]["dynamic_warming_secrets"] = [{"name": ca_name}]; mutations.append(warming)
    warm_cluster = clone(); warm_cluster["configs"][0]["dynamic_warming_clusters"] = [{"cluster": {"name": cluster}}]; mutations.append(warm_cluster)
    ambiguous = clone(); ambiguous["configs"][0]["dynamic_active_clusters"] *= 2; mutations.append(ambiguous)
    for material in (old, old + new + pem(b"excess-ca")):
        value = clone(); value["configs"][1]["dynamic_active_secrets"][1] = secret(ca_name, "validation_context", "trusted_ca", material); mutations.append(value)
    wrong_leaf = clone(); wrong_leaf["configs"][1]["dynamic_active_secrets"][0] = secret(client_name, "tls_certificate", "certificate_chain", old); mutations.append(wrong_leaf)
    for value in mutations:
        try:
            validate(value)
        except ProofError:
            pass
        else:
            raise ProofError("TLS barrier accepted missing/wrong/warming/ambiguous bound configuration")
    proof = Proof.__new__(Proof)
    proof.ns, proof.api_key = "fluxgate-resilience", "private-offline-api-key"
    proof.f = {"gateway_namespace": "envoy-gateway-system", "gateway_service": "gateway", "server_name": "authz.local",
               "envoy_client_tls_secret": "fluxgate-envoy-client-tls", "load_path": "/api/load", "gateway_host": "gateway.local", "backend_body": "backend-ok"}
    proof.get = lambda *args, **kwargs: {"spec": {"selector": {"gateway": "test", "component": "envoy"},
                                        "ports": [{"port": 80, "targetPort": 10080}]}}
    reads = []
    changed = False
    queried = []
    def kube(*args, **kwargs):
        from types import SimpleNamespace
        require(args[3] == "component=envoy,gateway=test", "TLS barrier ignored part of service selector")
        queried.append(args)
        pods = [{"metadata": {"name": "envoy-" + str(i), "uid": "uid-" + str(i)},
                 "status": {"conditions": [{"type": "Ready", "status": "True"}]}} for i in range(2)]
        if changed and len(queried) > 1:
            pods[0]["metadata"]["uid"] = "replaced"
        return SimpleNamespace(stdout=json.dumps({"items": pods}).encode())
    proof.kube = kube
    active_pod = [None]
    @contextlib.contextmanager
    def forward(resource, port, namespace, deadline=None):
        active_pod[0] = resource
        yield port
    proof.forward = forward
    bad_body = [None]
    clock = [0]
    late_stage = [None]
    class Connection:
        def __init__(self, host, port, timeout):
            self.port = port
        def request(self, method, path, headers):
            if self.port == 10080:
                require(headers["x-api-key"] == proof.api_key and headers["Connection"] == "close", "TLS barrier positive was unauthenticated or reused")
                reads.append(active_pod[0])
        def getresponse(self):
            return self
        status = 200
        def read(self, *args):
            if active_pod[0] == "pod/envoy-1" and self.port == late_stage[0]:
                clock[0] = 91
            if self.port == 19000:
                return json.dumps(dump).encode()
            return b"junk-backend-ok" if active_pod[0] == bad_body[0] else b"backend-ok\n"
        def close(self):
            pass
    with patch.object(http.client, "HTTPConnection", Connection):
        outcome = proof.envoy_tls_barrier(old + new, client)
        require(reads == ["pod/envoy-0", "pod/envoy-1"] and len(outcome["pods"]) == 2,
                "TLS barrier did not verify each Envoy Pod")
        for bad_body[0] in ("pod/envoy-0", "pod/envoy-1"):
            try:
                proof.envoy_tls_barrier(old + new, client)
            except ProofError:
                pass
            else:
                raise ProofError("TLS barrier accepted a wrong per-Pod backend body")
        bad_body[0] = None
        changed = True
        queried.clear()
        try:
            proof.envoy_tls_barrier(old + new, client)
        except ProofError as error:
            require(str(error) == "TLS barrier Envoy Pod identity changed", "stale-UID test failed for unrelated reason")
        else:
            raise ProofError("TLS barrier accepted replaced Envoy Pod identity")
        changed = False
        for late_stage[0] in (19000, 10080):
            clock[0] = 0
            with patch.object(time, "monotonic", side_effect=lambda: clock[0]):
                try:
                    proof.envoy_tls_barrier(old + new, client)
                except ProofError as error:
                    require(str(error) == "TLS barrier exceeded 90-second deadline", "deadline control failed for unrelated reason")
                else:
                    raise ProofError("TLS barrier accepted late second-Pod configuration or backend control")


def rotation_timeline_self_test():
    with tempfile.TemporaryDirectory(prefix="credential-timeline-test-") as directory:
        proof = Proof.__new__(Proof)
        proof.work, proof.results = Path(directory), {}
        secret = "private-uri-key-jwt-do-not-record"
        with proof.operation("mtls.ca-overlap"):
            pass
        try:
            with proof.operation("stores.app-roll"):
                raise RuntimeError(secret)
        except RuntimeError:
            pass
        else:
            raise ProofError("timeline swallowed operation failure")
        path = proof.work / "rotation-timeline.json"
        saved = path.read_text()
        events = json.loads(saved)
        require([event["state"] for event in events] == ["start", "end", "start", "failure"],
                "timeline omitted failure-finalization or completed prior action")
        require(all(set(event) == {"action", "state", "utc_ns", "monotonic_ns"} for event in events)
                and secret not in saved and secret not in json.dumps(proof.results), "timeline leaked operation inputs")
        require(all(events[i]["monotonic_ns"] <= events[i+1]["monotonic_ns"] for i in range(len(events)-1))
                and all(event["utc_ns"] > 0 for event in events), "timeline lost clock accounting")
        require(path.stat().st_mode & 0o077 == 0, "private timeline permissions are unsafe")
        try:
            with proof.operation(secret):
                pass
        except ProofError:
            pass
        else:
            raise ProofError("timeline allowed caller-provided sensitive label")
        require(path.read_text() == saved, "rejected label changed retained evidence")


def redis_policy_selection_self_test():
    """Select actual Lua metadata, never bucket counters or revision fences."""
    proof = Proof.__new__(Proof)
    proof.f = {"redis_pods": ["replica"], "rule_set_id": "resilience-limits",
               "quota_rule_id": "quota-rule", "load_rule_id": "load-rule"}
    prefix = "fluxgate:policy:fluxgate:bucket:{resilience-limits:quota-rule:"
    key = prefix + "api-key:rotation-test}:daily:epoch:c3RhYmxl"
    root = Path(__file__).resolve().parents[2]
    store = (root / "fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/RedisTokenBucketStore.java").read_text()
    lua = (root / "fluxgate-redis-ratelimiter/src/main/resources/lua/token_bucket_consume.lua").read_text()
    java_codes = dict(re.findall(r"case (TOKEN_BUCKET|FIXED_WINDOW):\s*return (\d+);", store))
    lua_codes = dict(re.findall(r"local ALG_(TOKEN_BUCKET|FIXED_WINDOW)\s*=\s*(\d+)", lua))
    require(java_codes == lua_codes and set(java_codes) == {"TOKEN_BUCKET", "FIXED_WINDOW"},
            "Java/Lua algorithm code contract changed")
    require("args[base + 2] = String.valueOf(algorithmCode(band.getAlgorithm()))" in store
            and re.search(r"local alg\s*=\s*tonumber\(ARGV\[base \+ 3\]\)", lua) is not None
            and re.search(r"algorithms\[i\]\s*=\s*alg", lua) is not None
            and re.search(r"'algorithm',\s*algorithms\[i\]", lua) is not None,
            "Lua no longer stores the numeric Java algorithm argument")
    metadata = (b"revision\n7\ncapacity\n5\nwindow_micros\n86400000000\nalgorithm\n"
                + java_codes["TOKEN_BUCKET"].encode())
    calls = []
    band = "daily"
    scenario = "valid"
    def redis(pod, command, password=None, readonly=False):
        calls.append(command)
        if command == ["ROLE"]:
            return b"slave\nmaster\n6379\n0"
        if command[0] == "KEYS":
            return b""  # Invented namespace contains no actual store metadata.
        if command[0] == "SCAN":
            allowed = {"fluxgate:policy:fluxgate:bucket:{resilience-limits:quota-rule:*}:daily*",
                       "fluxgate:policy:fluxgate:bucket:{resilience-limits:load-rule:*}:hourly*"}
            require(command[3] in allowed, "metadata scan escaped fixture rules")
            if command[3] != prefix + "*}:" + band + "*":
                return b"0"
            require(command == ["SCAN", "0", "MATCH", prefix + "*}:" + band + "*", "COUNT", "100"],
                    "metadata selection did not bound the fixture namespace")
            return b"0\n" + (prefix + "api-key:ordinary}:" + band).encode() + b"\n" + key.encode()
        if command[0] == "TYPE":
            return b"hash"
        if command[0] == "HGETALL":
            if scenario == "ordinary" or command[1] != key:
                return b"tokens\n5\nlast_refill_micros\n1"
            return metadata
        if command[0] == "TTL":
            return b"600" if scenario == "short-ttl" else b"86400"
        raise ProofError("unexpected metadata selection command")
    proof.redis = redis
    class Selected(RuntimeError):
        pass
    proof.get = lambda *args: (_ for _ in ()).throw(Selected())
    try:
        proof.redis_cold_restart("offline-password", "offline-retired")
    except Selected:
        pass
    else:
        raise ProofError("actual policy metadata was not selected before cold restart")
    require(not any(c[0] == "KEYS" for c in calls), "metadata selection used blocking KEYS")
    for scenario in ("ordinary", "short-ttl"):
        try:
            proof.redis_cold_restart("offline-password", "offline-retired")
        except ProofError as error:
            require(str(error) == "no replica with real persisted policy metadata for cold restart",
                    "invalid metadata regression failed for another reason")
        else:
            raise ProofError("ordinary hash or expiring metadata selected as persisted policy")
    require(redis_hash_contents(metadata) != redis_hash_contents(metadata.replace(b"revision\n7", b"revision\n8")),
            "policy retention comparison hid changed revision")
    prefix = "fluxgate:policy:fluxgate:bucket:{resilience-limits:load-rule:"
    band = "hourly"
    key = prefix + "api-key:load-test}:hourly:epoch:c3RhYmxl"
    metadata = (b"revision\n7\ncapacity\n10000000\nwindow_micros\n3600000000\nalgorithm\n"
                + java_codes["FIXED_WINDOW"].encode())
    scenario = "valid"
    try:
        proof.redis_cold_restart("offline-password", "offline-retired")
    except Selected:
        pass
    else:
        raise ProofError("actual FIXED_WINDOW algorithm code was not selected")


def tls_alert_read_self_test():
    """A negative TLS control consumes its alert without sending HTTP bytes."""
    from unittest.mock import patch
    proof = Proof.__new__(Proof)
    proof.f, proof.api_key = {"server_name": "localhost"}, "offline-only-key"
    proof.forward = lambda *args: contextlib.nullcontext(8443)
    for reason in ("TLSV13_ALERT_CERTIFICATE_REQUIRED", None, "SSLV3_ALERT_HANDSHAKE_FAILURE"):
        calls = []
        class Connection:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def recv(self, count):
                calls.append(("recv", count))
                if reason is None:
                    return b""
                error = ssl.SSLError(1, reason)
                error.reason = reason
                raise error
            def sendall(self, data):
                raise ProofError("negative TLS control sent HTTP before reading certificate alert")
        class Context:
            def wrap_socket(self, *args, **kwargs):
                return Connection()
        with patch.object(ssl, "create_default_context", return_value=Context()), \
                patch.object(socket, "create_connection", return_value=Connection()):
            try:
                result = proof.tls_probe_pod("offline", "unused-ca", expected="tls-reject")
            except ProofError:
                if reason == "TLSV13_ALERT_CERTIFICATE_REQUIRED":
                    raise
            else:
                require(reason == "TLSV13_ALERT_CERTIFICATE_REQUIRED" and result["tls_rejected"],
                        "EOF or generic TLS alert passed negative certificate control")
        require(calls == [("recv", 1)], "negative TLS control did not first read queued certificate alert")


def tls_runtime_self_test():
    original = ssl.HAS_TLSv1_3
    try:
        ssl.HAS_TLSv1_3 = False
        try:
            credential_runtime()
        except ProofError:
            pass
        else:
            raise ProofError("unsupported TLS runtime passed live preflight")
        ssl.HAS_TLSv1_3 = True
        require(credential_runtime()["tls13_available"], "TLS 1.3 runtime rejected by preflight")
        for reason in ("SSLV3_ALERT_HANDSHAKE_FAILURE", "UNEXPECTED_EOF_WHILE_READING", "WRONG_VERSION_NUMBER"):
            try:
                validate_certificate_rejection(reason)
            except ProofError:
                pass
            else:
                raise ProofError("generic TLS transport failure passed certificate control")
        validate_certificate_rejection("TLSV13_ALERT_CERTIFICATE_REQUIRED")
    finally:
        ssl.HAS_TLSv1_3 = original


def sampler_parallel_self_test():
    with tempfile.TemporaryDirectory(prefix="sampler-parallel-unit-") as directory:
        stop = Path(directory) / "stop"
        arrivals = []
        arrival_lock = threading.Lock()
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                with arrival_lock:
                    arrivals.append(time.monotonic())
                    number = len(arrivals)
                if number == 1:
                    time.sleep(.510)
                if number == 3:
                    private_write(stop, "stop")
                self.send_response(200)
                self.send_header("Content-Length", "7")
                self.end_headers()
                self.wfile.write(b"marker\n")
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            configuration = {"service": "127.0.0.1", "port": server.server_port, "path": "/load",
                "host": "offline", "api_key": "offline-private-key", "body": "marker",
                "positive_path": str(Path(directory) / "positive"),
                "progress_path": str(Path(directory) / "progress")}
            program = sampler_program(stop, Path(directory) / "started")
            result = subprocess.run([sys.executable, "-c", program],
                input=(json.dumps(configuration) + "\n").encode(), capture_output=True, timeout=5)
            require(result.returncode == 0 and not result.stderr, "parallel generated sampler failed")
            report = sampler_summary(json.loads(result.stdout))
            require(arrivals[1] - arrivals[0] < .35, "slow response coalesced fixed 100ms arrivals")
            require(report["scheduled"] == 3 and report["omitted_schedules"] == 0 and
                    [s["sequence"] for s in report["samples"]] == [0, 1, 2],
                    "parallel sampler omitted schedules or lost drained/out-of-order responses")
            require(report["samples"][0]["service_latency_ms"] >= 500 and
                    report["samples"][1]["elapsed_seconds"] < .35 and report["pending"] == 0 and
                    report["drain_complete"], "sampler hid pending work or excluded scheduled-origin timing")
            for sample in report["samples"]:
                require(sample["latency_ms"] >= sample["service_latency_ms"] and
                        sample["latency_ms"] >= sample["dispatch_lag_ms"], "sampler latency excludes dispatch lag")
            progress = json.loads((Path(directory) / "progress").read_text())
            require(progress["scheduled"] == len(progress["samples"]) + progress["pending"] +
                    progress["omitted_schedules"], "private progress lost unfinished accounting")
        finally:
            server.shutdown()
            server.server_close()



def sampler_contention_self_test():
    from types import SimpleNamespace
    for stop_during_lock in (False, True):
        namespace = {}
        exec(sampler_program().rsplit("\nconfig = ", 1)[0], namespace)
        clock = SimpleNamespace(now=0.0)
        holding, attempting, release = threading.Event(), threading.Event(), threading.Event()
        result, connections = {}, []
        with tempfile.TemporaryDirectory(prefix="sampler-contention-unit-") as directory:
            stop = Path(directory) / "stop"
            class ContendedCondition(threading.Condition):
                def __enter__(self):
                    name = threading.current_thread().name
                    if name == "sampler-scheduler-test":
                        self.scheduler_entries = getattr(self, "scheduler_entries", 0) + 1
                        if self.scheduler_entries == 2:
                            attempting.set()  # Observe the actual blocked scheduler acquisition.
                    acquired = super().__enter__()
                    if name.endswith("(worker)") and not holding.is_set():
                        holding.set()  # Explicit contention; progress I/O no longer owns this lock.
                        require(release.wait(2), "contention lock was not released")
                    return acquired
            namespace["threading"] = SimpleNamespace(Condition=ContendedCondition,
                BoundedSemaphore=threading.BoundedSemaphore, Event=threading.Event,
                Thread=threading.Thread, Lock=threading.Lock)
            def sleep(seconds):
                if seconds > 0:
                    require(holding.wait(2), "worker did not hold the real scheduler lock")
                    clock.now += seconds
                time.sleep(.001)
            namespace["time"] = SimpleNamespace(monotonic=lambda: clock.now,
                monotonic_ns=lambda: int(clock.now * 1e9), time_ns=lambda: int(clock.now * 1e9), sleep=sleep)
            def write(path, value):
                private_write(path, value)
            namespace["private_write"] = write
            class Connection:
                def __init__(self, *args, **kwargs):
                    connections.append(self)
                    self.number = len(connections)
                def request(self, *args, **kwargs):
                    pass
                def getresponse(self):
                    return SimpleNamespace(status=200, read=lambda: b"marker\n")
                def close(self):
                    if self.number == 2:
                        private_write(stop, "stop")
            namespace["http"] = SimpleNamespace(client=SimpleNamespace(HTTPConnection=Connection))
            configuration = {"service": "offline", "port": 80, "path": "/load", "host": "offline",
                "api_key": "offline-private-key", "body": "marker",
                "positive_path": str(Path(directory) / "positive"),
                "progress_path": str(Path(directory) / "progress")}
            def run():
                try:
                    result["report"] = namespace["sampler_worker"](configuration, str(stop))
                except BaseException as error:
                    result["error"] = error
            runner = threading.Thread(target=run, name="sampler-scheduler-test")
            runner.start()
            try:
                require(attempting.wait(2), "scheduler did not contend on the worker lock")
                clock.now = .35  # Deadline .1 is overdue by .25 while lock acquisition blocks.
                if stop_during_lock:
                    private_write(stop, "stop")
                release.set()
                runner.join(3)
                require(not runner.is_alive() and "error" not in result, "contended sampler failed to drain")
                report = result["report"]
                if stop_during_lock:
                    require(report["scheduled"] == 1 and len(connections) == 1 and
                            [s["sequence"] for s in report["samples"]] == [0],
                            "stop during scheduler lock admitted an extra arrival/socket")
                else:
                    require(report["scheduled"] == 4 and report["omitted_schedules"] == 2 and
                            [s["sequence"] for s in report["samples"]] == [0, 3] and
                            report["omissions"] == [{"first_sequence": 1, "count": 2, "reason": "lateness"}],
                            "scheduler used stale pre-lock lag and hid overdue arrivals")
                require(report["drain_complete"] and report["pending"] == 0 and
                        report["scheduled"] == len(report["samples"]) + report["omitted_schedules"],
                        "contention lost final schedule accounting")
            finally:
                private_write(stop, "stop")
                release.set()
                runner.join(3)


def sampler_program_accounting_self_test():
    from types import SimpleNamespace
    for scenario in ("lateness", "capacity", "drain"):
        namespace = {}
        exec(sampler_program().rsplit("\nconfig = ", 1)[0], namespace)
        clock = SimpleNamespace(now=0.0, delayed=False)
        release, closed = threading.Event(), threading.Event()
        connections, progress_reports, progress_posts = [], [], []
        class CaptureQueue(queue.Queue):
            def put_nowait(self, item):
                if isinstance(item, dict) and item.get("partial"):
                    progress_posts.append(item)
                return super().put_nowait(item)
        namespace["queue"] = SimpleNamespace(Queue=CaptureQueue, Empty=queue.Empty, Full=queue.Full)
        connection_lock = threading.Lock()
        with tempfile.TemporaryDirectory(prefix="sampler-program-accounting-") as directory:
            stop = Path(directory) / "stop"
            def sleep(seconds):
                if scenario == "lateness" and seconds > 0 and not clock.delayed:
                    clock.now += .35
                    clock.delayed = True
                else:
                    clock.now += seconds
                if scenario == "capacity" and clock.now >= 2.6:
                    private_write(stop, "stop")
                    release.set()
                if scenario == "drain" and clock.now >= .1:
                    private_write(stop, "stop")
                time.sleep(.01)  # Let real worker threads dispatch before advancing synthetic time.
            namespace["time"] = SimpleNamespace(monotonic=lambda: clock.now,
                monotonic_ns=lambda: int(clock.now * 1e9), time_ns=lambda: int(clock.now * 1e9), sleep=sleep)
            if scenario == "drain":
                class DrainCondition(threading.Condition):
                    def wait(self, timeout=None):
                        clock.now += timeout
                        return False
                namespace["threading"] = SimpleNamespace(Condition=DrainCondition,
                    BoundedSemaphore=threading.BoundedSemaphore, Event=threading.Event,
                    Thread=threading.Thread, Lock=threading.Lock)
            class Connection:
                def __init__(self, *args, **kwargs):
                    require(kwargs["timeout"] == 2, "generated sampler changed HTTP timeout")
                    with connection_lock:
                        connections.append(self)
                        self.number = len(connections)
                def request(self, method, path, headers):
                    require(method == "GET" and headers["Connection"] == "close", "sampler reused transport")
                def getresponse(self):
                    if scenario == "lateness" and self.number == 1:
                        raise TimeoutError("offline transport failure")
                    if scenario in ("capacity", "drain"):
                        release.wait()
                    return SimpleNamespace(status=200, read=lambda: b"marker\n")
                def close(self):
                    if scenario == "lateness" and self.number == 2:
                        private_write(stop, "stop")
                    closed.set()
            namespace["http"] = SimpleNamespace(client=SimpleNamespace(HTTPConnection=Connection))
            writer = namespace["private_write"]
            def capture_write(path, content):
                if Path(path).name.startswith("progress"):
                    progress_reports.append(json.loads(content))
                writer(path, content)
            namespace["private_write"] = capture_write
            try:
                report = namespace["sampler_worker"]({"service": "offline", "port": 80, "host": "offline",
                    "path": "/load", "api_key": "offline-only", "body": "marker",
                    "positive_path": str(Path(directory) / "positive"),
                    "progress_path": str(Path(directory) / "progress")}, str(stop))
                require(report["interval_ms"] == 100 and report["scheduled"] == len(report["samples"]) +
                        report["pending"] + report["omitted_schedules"], "generated sampler lost scheduled work")
                require(all(r["scheduled"] == (r["completed"] if r.get("partial") else len(r["samples"]))
                            + r["pending"] + r["omitted_schedules"] for r in progress_reports + progress_posts)
                        and any(r["pending"] for r in progress_posts),
                        "private progress excluded unfinished schedules")
                require(report["peak_inflight"] <= 24 and "offline-only" not in json.dumps(report),
                        "sampler exceeded hard inflight bound or leaked credentials")
                if scenario == "lateness":
                    require(report["scheduled"] == 4 and report["omitted_schedules"] == 2 and
                            [s["sequence"] for s in report["samples"]] == [0, 3] and
                            report["samples"][0]["error"] == "TimeoutError" and report["drain_complete"],
                            "generated sampler hid scheduler omissions or transport errors")
                    require(report["omissions"] == [{"first_sequence": 1, "count": 2, "reason": "lateness"}],
                            "sampler omission provenance changed")
                elif scenario == "capacity":
                    require(report["scheduled"] == 26 and len(report["samples"]) == 24 and
                            report["omitted_schedules"] == 2 and report["peak_inflight"] == 24 and
                            report["pending"] == 0 and report["drain_complete"] and
                            all(item["reason"] == "capacity" for item in report["omissions"]),
                            "bounded saturation omitted work silently or failed to drain submitted samples")
                    require(not sampler_summary(report)["uninterrupted_observed"], "saturation omissions passed acceptance")
                else:
                    require(report["pending"] == 1 and not report["drain_complete"], "stuck request disappeared from final report")
                    try:
                        sampler_summary(report)
                    except ProofError:
                        pass
                    else:
                        raise ProofError("unfinished sampler drain passed summary")
            finally:
                release.set()
                if connections:
                    require(closed.wait(2), "offline blocked connection did not close")


def self_test():
    """Own temporary localhost processes only; no fixture, kube mutations or application dependencies."""
    credential_acceptance_self_test()
    envoy_tls_barrier_self_test()
    rotation_timeline_self_test()
    redis_policy_selection_self_test()
    tls_alert_read_self_test()
    strict_tls_self_test()
    tls_runtime_self_test()
    failed_rotation_cleanup_self_test()
    backend_body_self_test()
    sampler_cleanup_self_test()
    require(redis_hash_contents(b"revision\n1\nepoch\nstable") ==
            redis_hash_contents(b"epoch\nstable\nrevision\n1"), "Redis metadata compared hash iteration order")
    program = sampler_program()
    require(len(program.encode()) < 16 * 1024, "sampler program exceeds conservative command argument budget")
    compile(program, "sampler-worker", "exec")
    from unittest.mock import patch
    with patch.object(Path, "read_text", return_value="# unrelated module growth\n" * 6000):
        require(sampler_program() == program, "sampler command grew with unrelated module source")
    sampler_parallel_self_test()
    sampler_contention_self_test()
    sampler_program_accounting_self_test()
    original = {"metadata": {"uid": "old"}, "spec": {"volumes": [{"persistentVolumeClaim": {"claimName": "data"}}]}}
    replacement = {"metadata": {"uid": "new"}, "spec": original["spec"]}
    pvc = {"data": {"metadata": {"uid": "pvc"}, "spec": {"volumeName": "pv"}}}
    validate_restart_identity(original, replacement, pvc, pvc)
    for after, now in ((original, pvc), (replacement, {"data": {"metadata": {"uid": "different"}, "spec": {"volumeName": "pv"}}}),
                       ({"metadata": {"uid": "new"}, "spec": {"volumes": []}}, pvc)):
        try:
            validate_restart_identity(original, after, pvc, now)
        except ProofError:
            pass
        else:
            raise ProofError("cold restart accepted old UID or lost PVC")
    report = sampler_summary({"scheduled": 4, "omitted_schedules": 1, "samples": [
        {"status": 200, "body_valid": True}, {"status": 503, "body_valid": False},
        {"status": None, "body_valid": False, "error": "TimeoutError"}]})
    require(report["statuses"] == {"200": 1, "503": 1, "ERROR": 1} and not report["uninterrupted_observed"] and
            report["observed_availability"] == 1 / 3, "traffic summary hid outages or missed samples")
    with tempfile.TemporaryDirectory(prefix="fluxgate-sampler-unit-") as directory:
        stop = Path(directory) / "stop"
        calls = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path)
                status = 503 if len(calls) == 2 else 200
                body = {1: b"backend-marker", 2: b"unavailable", 3: b"prefix-backend-marker",
                        4: b"backend-marker-suffix", 5: b"backend-marker\n"}[len(calls)]
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                # Publish stop before the final body unblocks the sampler's reader.
                if len(calls) == 5:
                    private_write(stop, "stop")
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            configuration = {"service": "127.0.0.1", "port": server.server_port,
                "path": "/load", "host": "local", "api_key": "offline-fixture", "body": "backend-marker",
                "positive_path": str(Path(directory) / "positive"),
                "progress_path": str(Path(directory) / "progress.json")}
            worker_program = sampler_program(stop, Path(directory) / "started")
            worker_args = [sys.executable, "-c", worker_program]
            require(max(len(arg.encode()) for arg in worker_args) < 16 * 1024 and
                    "offline-fixture" not in worker_program, "sampler argv contains credentials or oversized program")
            executed = subprocess.run(worker_args, input=(json.dumps(configuration) + "\n").encode(),
                                      capture_output=True, timeout=10)
            require(executed.returncode == 0 and not executed.stderr, "standalone sampler program failed")
            observed = sampler_summary(json.loads(executed.stdout))
            require((Path(directory) / "started").read_text() == "yes" and
                    (Path(directory) / "positive").read_text() == "yes" and
                    (Path(directory) / "progress.json").stat().st_mode & 0o777 == 0o600,
                    "standalone sampler markers/progress changed private-write contract")
            require("offline-fixture" not in executed.stdout.decode(), "standalone sampler leaked stdin API key")
            require(observed["statuses"] == {"200": 4, "503": 1} and observed["unexpected_body_responses"] == 2 and
                    observed["backend_successes"] == 2 and not observed["uninterrupted_observed"],
                    "actual traffic sampler hid status/body failures")
            require(observed["started_utc_ns"] <= observed["stopped_utc_ns"]
                    and observed["started_monotonic_ns"] <= observed["stopped_monotonic_ns"]
                    and observed["scheduled"] == len(observed["samples"]) + observed["omitted_schedules"],
                    "sampler clock anchors changed schedule accounting")
            progress = json.loads((Path(directory) / "progress.json").read_text())
            require(progress["started_utc_ns"] == observed["started_utc_ns"]
                    and progress["started_monotonic_ns"] == observed["started_monotonic_ns"],
                    "partial sampler evidence lost invocation anchor")
            stop.unlink()
            calls.clear()
            original_sleep = time.sleep
            def stop_during_wait(delay):
                if delay > 0:
                    private_write(stop, "stop")
                else:
                    original_sleep(0)
            time.sleep = stop_during_wait
            try:
                stopped = sampler_summary(sampler_worker({"service": "127.0.0.1", "port": server.server_port,
                    "path": "/load", "host": "local", "api_key": "offline-fixture", "body": "backend-marker",
                    "positive_path": str(Path(directory) / "positive"),
                "progress_path": str(Path(directory) / "progress.json")}, str(stop)))
                require(len(calls) == 1 and stopped["scheduled"] == 1 and stopped["omitted_schedules"] == 0,
                        "sampler dispatched after stop arrived during schedule wait")
            finally:
                time.sleep = original_sleep
        finally:
            server.shutdown()
            server.server_close()
    require(mongo_marker(b'POLICY:{"revision":1}\nAUTH_PROBE_OK\n', "POLICY:") == '{"revision":1}',
            "direct Mongo marker parsing failed")
    require(mongo_marker(b'> PRIMARY:mongo-0.mongo.local:27017\nAUTH_PROBE_OK\n', "PRIMARY:") ==
            'mongo-0.mongo.local:27017', "prompt-prefixed Mongo marker parsing failed")
    for output in (b'AUTH_PROBE_OK\n', b'POLICY:\n', b'POLICY:one\n> POLICY:two\n',
                   b'noise POLICY:one\n', b'>> POLICY:one\n'):
        try:
            mongo_marker(output, "POLICY:")
        except ProofError:
            pass
        else:
            raise ProofError("missing/ambiguous Mongo marker was accepted")
    with tempfile.TemporaryDirectory(prefix="fluxgate-credential-unit-") as directory:
        proof = Proof.__new__(Proof)
        proof.work, proof.openssl = Path(directory), "openssl"
        proof.backups, proof.store_rollback, proof.cold_sentinels = {}, None, []
        originals = {Path(directory) / "tls" / name: ("original-" + name).encode()
                     for name in ("server-ca.crt", "server-ca.key", "client-ca.crt", "client-ca.key",
                                  "server.crt", "server.key", "client.crt", "client.key")}
        for path, content in originals.items():
            private_write(path, content)
        proof.tls_rollback = originals
        # Reproduce a failed copy after only some new TLS files have reached disk.
        for path in list(originals)[:3]:
            private_write(path, b"partial-new-certificate")
        proof.cleanup_failed()
        require(all(path.read_bytes() == content for path, content in originals.items()),
                "partial TLS copy rollback left mixed CA/certificate generations")
        ca, key = proof.ca("unit-ca")
        cert, cert_key = proof.certificate("expired", ca, key, "gateway", expired=True)
        result = run([proof.openssl, "verify", "-CAfile", str(ca), str(cert)], check=False)
        require(result.returncode != 0 and b"expired" in (result.stdout + result.stderr).lower(),
                "explicit expired certificate fixture was not expired")
        valid, valid_key = proof.certificate("valid", ca, key, "gateway")
        run([proof.openssl, "verify", "-CAfile", str(ca), str(valid)])
        public = Path(directory) / "jwt-public.pem"
        run([proof.openssl, "pkey", "-in", str(valid_key), "-pubout", "-out", str(public)])
        expiry = int(time.time()) + 1800
        jwt_controls = {
            "valid": signed_jwt(proof.openssl, valid_key, "trusted", "issuer", "audience", expiry),
            "expired": signed_jwt(proof.openssl, valid_key, "trusted", "issuer", "audience", int(time.time()) - 120),
            "wrong_issuer": signed_jwt(proof.openssl, valid_key, "trusted", "untrusted", "audience", expiry),
            "bad_signature": signed_jwt(proof.openssl, cert_key, "trusted", "issuer", "audience", expiry),
            "wrong_algorithm": signed_jwt(proof.openssl, valid_key, "trusted", "issuer", "audience", expiry, "RS512"),
        }
        def decode(segment):
            return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))
        for label, bearer in jwt_controls.items():
            head, body, signature = bearer.split(".")
            claims, header = json.loads(decode(body)), json.loads(decode(head))
            require(header["kid"] == "trusted", "negative fixture changed trusted kid")
            signature_path = Path(directory) / "jwt-signature.bin"
            private_write(signature_path, decode(signature))
            digest = "-sha512" if label == "wrong_algorithm" else "-sha256"
            verified = run([proof.openssl, "dgst", digest, "-verify", str(public), "-signature", str(signature_path)],
                           data=(head + "." + body).encode(), check=False)
            require((verified.returncode == 0) == (label != "bad_signature"), "JWT signature fixture incorrect")
            if label == "expired":
                require(claims["iat"] < claims["exp"] < int(time.time()) - 60, "JWT not expired beyond clock skew")
            else:
                require(claims["exp"] == expiry, "negative fixture mutated retirement expiry")
            require(claims["iss"] == ("untrusted" if label == "wrong_issuer" else "issuer"), "JWT issuer fixture incorrect")
            require(header["alg"] == ("RS512" if label == "wrong_algorithm" else "RS256"), "JWT algorithm fixture incorrect")
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        failures = []

        def responder():
            for _ in range(3):
                try:
                    conn, _ = listener.accept()
                    with conn, conn.makefile("rwb") as stream:
                        def command():
                            count = int(stream.readline()[1:])
                            values = []
                            for _ in range(count):
                                size = int(stream.readline()[1:])
                                values.append(stream.read(size))
                                stream.read(2)
                            return values
                        cmd = command()
                        if cmd[0] == b"AUTH":
                            require(len(cmd) == 2, "invalid AUTH wire format")
                            if cmd[1] != b"correct":
                                stream.write(b"-WRONGPASS invalid username-password pair\r\n")
                                stream.flush()
                                continue
                            stream.write(b"+OK\r\n")
                            stream.flush()
                            require(command() == [b"PING"], "invalid PING wire format")
                            stream.write(b"+PONG\r\n")
                        else:
                            require(cmd == [b"PING"], "invalid anonymous command")
                            stream.write(b"-NOAUTH Authentication required\r\n")
                        stream.flush()
                except Exception as error:
                    failures.append(type(error).__name__)
            listener.close()
        thread = threading.Thread(target=responder, daemon=True)
        thread.start()
        @contextlib.contextmanager
        def forward(*args):
            yield port
        proof.forward = forward
        proof.redis_check("unused", "correct", True)
        proof.redis_check("unused", "incorrect", False)
        proof.redis_check("unused", None, False)
        thread.join(timeout=5)
        require(not thread.is_alive() and not failures, "RESP regression server failed")
    print("Offline credential regressions passed: checked sampler cleanup failures, cold UID/PVC, actual traffic sampler, Mongo markers, TLS rollback, X.509/JWT and fresh RESP")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", help="fixture.json from resilience setup")
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true", help="offline protocol/certificate regressions")
    parser.add_argument("--phases", default="mtls,api-key,stores,jwt",
                        help="serial phase selection; partial runs are explicitly labelled")
    args = parser.parse_args()
    os.umask(0o077)
    if args.self_test:
        self_test()
        return
    require(bool(args.fixture and args.output), "--fixture and --output required for live proof")
    runtime = credential_runtime()
    proof = Proof(args.fixture)
    phases = args.phases.split(",")
    try:
        require(len(phases) == len(set(phases)) and bool(phases)
                and set(phases) <= {"mtls", "api-key", "stores", "jwt"}, "invalid or duplicate credential phases")
        proof.validate()
        for phase in phases:
            print("Credential phase: " + phase, flush=True)
            method = {"mtls": proof.tls, "api-key": proof.api_keys, "stores": proof.stores,
                      "jwt": proof.jwt}[phase]
            with proof.operation("phase." + phase):
                if phase in ("mtls", "api-key", "stores"):
                    with proof.traffic_sampler(phase):
                        method()
                else:
                    method()
    except Exception as error:
        proof.cleanup_failed()
        # Error details may be secrets; output the safe proof assertion only.
        print("Credential proof failed: " + (str(error) if isinstance(error, ProofError) else type(error).__name__))
        raise SystemExit(1)

    result = {**credential_acceptance(proof.results, phases),
                  "context": proof.f["context"], "namespace": proof.ns, "runtime": runtime, "results": proof.results,
                  "limits": ["Local ephemeral fixture only; no external credential authority tested.",
                             "Rotation availability is measured by 100ms in-cluster samples with explicit errors and omissions; no zero-downtime guarantee is inferred.",
                             "JWKS retirement records warm-cache behavior, checks an observed unknown-kid refresh, and checks a fresh decoder; key removal alone is not immediate revocation."]}
    private_write(args.output, json.dumps(result, indent=2) + "\n")
    if result["passed"]:
        print("Credential proof passed; evidence: " + str(Path(args.output).resolve()))
    else:
        print("Credential protocol passed; rotation availability failed; evidence: " + str(Path(args.output).resolve()))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
