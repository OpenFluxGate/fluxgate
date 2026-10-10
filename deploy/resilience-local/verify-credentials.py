#!/usr/bin/env python3
"""Real credential controls/rotations for the isolated resilience fixture.

Secrets travel through private files or subprocess stdin, never command arguments or
proof output. Every HTTP/TLS/store probe creates a new connection. Run serially after
HA/load proofs; successful rotations intentionally update the private fixture files.
"""
import argparse
import base64
import contextlib
import hashlib
import http.client
import http.server
import json
import os
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
    """Fixed 100ms schedules, fresh sockets, and explicit missed schedules/errors."""
    start = time.monotonic()
    sequence = 0
    samples = []
    omitted = 0
    while not Path(stop_path).exists():
        deadline = start + sequence * .1
        lag = time.monotonic() - deadline
        if lag >= .1:
            missed = int(lag / .1)
            omitted += missed
            sequence += missed
            deadline = start + sequence * .1
        time.sleep(max(0, deadline - time.monotonic()))
        # A stop may arrive while waiting for the next schedule. Do not dispatch after it.
        if Path(stop_path).exists():
            break
        began = time.monotonic()
        sample = {"sequence": sequence, "elapsed_seconds": began - start,
                  "dispatch_lag_ms": max(0, began - deadline) * 1000}
        connection = http.client.HTTPConnection(config["service"], config["port"], timeout=2)
        try:
            connection.request("GET", config["path"], headers={"Host": config["host"],
                               "x-api-key": config["api_key"], "Connection": "close"})
            response = connection.getresponse()
            body = response.read()
            expected = config["body"].encode()
            sample.update(status=response.status, body_valid=body in (expected, expected + b"\n"))
        except Exception as error:
            sample.update(status=None, body_valid=False, error=type(error).__name__)
        finally:
            connection.close()
        sample["latency_ms"] = (time.monotonic() - began) * 1000
        samples.append(sample)
        sequence += 1
        if len(samples) == 1 or sequence % 10 == 1:
            private_write(config.get("progress_path", "/tmp/sampler-progress.json"), json.dumps({
                "interval_ms": 100, "scheduled": sequence, "omitted_schedules": omitted,
                "samples": samples, "duration_seconds": time.monotonic() - start,
                "transport": "in-cluster Gateway Service; fresh connection per sample"}))
        if sample["status"] == 200 and sample["body_valid"]:
            private_write(config.get("positive_path", "/tmp/sampler-positive"), "yes")
    return {"interval_ms": 100, "scheduled": sequence, "omitted_schedules": omitted,
            "samples": samples, "duration_seconds": time.monotonic() - start,
            "transport": "in-cluster Gateway Service; fresh connection per sample"}


def sampler_summary(report):
    samples = report["samples"]
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

    def read(self, key):
        p = Path(self.f[key])
        require(p.stat().st_mode & 0o077 == 0, key + " must be private")
        return p.read_text().strip()

    def kube(self, *args, data=None, check=True, namespace=None):
        return run(["kubectl", "--kubeconfig", self.f["kubeconfig"], "--context", self.f["context"],
                    "-n", namespace or self.ns, *args], data=data, check=check, timeout=360)

    def get(self, resource, namespace=None):
        return json.loads(self.kube("get", resource, "-o", "json", namespace=namespace).stdout)

    def validate(self):
        ns = self.get("namespace/" + self.ns)
        require(ns["metadata"].get("labels", {}).get("fluxgate.io/environment") == "local-ephemeral",
                "namespace missing local-ephemeral guard")
        require(self.f["gateway_host"] not in ("", "localhost"), "explicit Gateway host required")
        for field in ("api_key_file", "mongo_uri_file", "mongo_admin_uri_file", "redis_password_file"):
            self.read(field)

    @contextlib.contextmanager
    def forward(self, resource, remote_port, namespace=None):
        port = unused_port()
        log = open(self.work / ("forward-" + str(port) + ".log"), "wb")
        proc = subprocess.Popen(["kubectl", "--kubeconfig", self.f["kubeconfig"], "--context",
                                 self.f["context"], "-n", namespace or self.ns, "port-forward",
                                 resource, str(port) + ":" + str(remote_port)], stdout=log, stderr=log)
        try:
            for _ in range(100):
                require(proc.poll() is None, "port-forward exited")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    time.sleep(.1)
            else:
                raise ProofError("port-forward not ready")
            yield port
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
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
        self.patch_data("configmap", server_cm, {"ca.crt": overlap_server.decode()})
        self.patch_data("secret", server_secret, {"client-ca.crt": overlap_client})
        self.rollout()
        controls["overlap_old_client"] = self.tls_probe(old_server_ca, old_client, old_client_key)
        controls["overlap_new_client"] = self.tls_probe(old_server_ca, *new_client)
        self.available()
        self.patch_data("secret", server_secret,
                        {"tls.crt": new_server[0].read_bytes(), "tls.key": new_server[1].read_bytes()})
        self.rollout()
        self.patch_data("secret", client_secret,
                        {"tls.crt": new_client[0].read_bytes(), "tls.key": new_client[1].read_bytes()})
        self.envoy_rollout()
        self.available()
        controls["new_pair_overlap"] = self.tls_probe(new_sca, *new_client)
        self.patch_data("secret", server_secret, {"client-ca.crt": new_cca.read_bytes()})
        self.rollout()
        self.patch_data("configmap", server_cm, {"ca.crt": new_sca.read_text()})
        self.envoy_rollout()
        self.available()
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
        self.api_mapping(original + [old_map])
        require(self.gateway(self.f["quota_path"], old) == 200, "old API key initial quota")
        before = self.counter_snapshot(identity)
        require(before, "quota Redis counter absent; API-key identity not proven")
        self.api_mapping(original + [old_map, new_map])
        require(self.gateway(self.f["quota_path"], new) == 200, "new API key overlap quota")
        require(self.gateway(self.f["quota_path"], old) == 200, "old API key overlap quota")
        overlap = self.counter_snapshot(identity)
        require(set(before) == set(overlap), "rotation changed bucket key/epoch")
        require(before != overlap, "quota counter did not change across rotation")
        content = self.api_mapping(original + [new_map])
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
            result = self.kube("exec", name, "--request-timeout=10s", "--", "cat", "/tmp/sampler-progress.json", check=False)
            require(result.returncode == 0, "sampler progress unavailable")
            require(self.api_key.encode() not in result.stdout, "sampler progress contains a secret")
            report = sampler_summary(json.loads(result.stdout))
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
        labels = {"fluxgate.io/credential-sampler": name}
        policy = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
                  "metadata": {"name": name, "namespace": self.ns, "labels": labels},
                  "spec": {"podSelector": {"matchLabels": labels}, "policyTypes": ["Egress"], "egress": [
                      {"to": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
                               "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
                       "ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]},
                      {"to": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": self.f["gateway_namespace"]}},
                               "podSelector": {"matchLabels": service["spec"]["selector"]}}],
                       "ports": [{"protocol": "TCP", "port": ports[0]["targetPort"]}]}]}}
        pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name, "namespace": self.ns, "labels": labels},
               "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                        "terminationGracePeriodSeconds": 1,
                        "containers": [{"name": "sampler", "image": self.f.get("generator_image", "python:3.12-alpine"),
                                        "command": ["python3", "-c", "import time;time.sleep(7200)"],
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
            code = Path(__file__).read_text().rsplit('\nif __name__ == "__main__":', 1)[0]
            code += "\nconfig=json.loads(sys.stdin.readline());private_write('/tmp/sampler-started','yes');print(json.dumps(sampler_worker(config,'/tmp/sampler-stop')),flush=True)\n"
            # Code/command contains no secret. The API key enters only the exec stdin stream.
            process = subprocess.Popen(["kubectl", "--kubeconfig", self.f["kubeconfig"], "--context", self.f["context"],
                                        "-n", self.ns, "exec", "-i", name, "--", "python3", "-c", code],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            config = {"service": self.f["gateway_service"] + "." + self.f["gateway_namespace"] + ".svc.cluster.local",
                      "port": 80, "host": self.f["gateway_host"], "path": self.f["load_path"],
                      "api_key": self.api_key, "body": self.f.get("backend_body", "fluxgate-resilience-ok")}
            process.stdin.write((json.dumps(config) + "\n").encode())
            process.stdin.close()
            process.stdin = None
            for _ in range(60):
                require(process.poll() is None, "credential sampler exited before mutation")
                if self.kube("exec", name, "--", "test", "-f", "/tmp/sampler-positive", check=False).returncode == 0:
                    break
                time.sleep(.1)
            else:
                raise ProofError("credential sampler did not start")
            yield
        finally:
            try:
                if process:
                    self.kube("exec", name, "--", "touch", "/tmp/sampler-stop")
                    output, _ = process.communicate(timeout=15)
                    require(process.returncode == 0, "credential sampler failed")
                    require(self.api_key.encode() not in output, "credential sampler leaked secret; evidence suppressed")
                    report = sampler_summary(json.loads(output))
                    self.results.setdefault("rotation_traffic", {})[phase] = report
                    # Retain failed-phase observations privately even when no complete proof is emitted.
                    private_write(self.work / (phase + "-traffic.json"), json.dumps(report, indent=2) + "\n")
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
        content = secrets.token_bytes(32)
        self.cold_sentinels.append((pod, sentinel))
        self.kube("exec", "-i", pod, "--", "sh", "-c", 'cat > "$1"', "sentinel", sentinel, data=content)
        require(self.kube("exec", pod, "--", "cat", sentinel).stdout == content, "restart data sentinel unreadable")
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
        self.mongo(admin, "c.getDB(" + json.dumps(auth_db) + ").createUser({user:" + json.dumps(new_user) +
                   ",pwd:" + json.dumps(new_mongo_password) + ",customData:" +
                   json.dumps({"fluxgateCredentialProof": self.store_rollback["ownership"]}) + ",roles:[{role:'readWrite',db:'fluxgate'}]})")
        new_uri = urlunsplit(parsed._replace(netloc=quote(new_user) + ":" + quote(new_mongo_password) + "@" + hosts))
        self.mongo(new_uri, protected_read)
        self.mongo(uri, protected_read)  # overlap: both users still work over new connections
        new_redis_password = secrets.token_urlsafe(40)
        self.store_rollback["new_password"] = new_redis_password
        for pod in self.f["redis_pods"]:
            output = self.redis(pod, ["ACL", "SETUSER", "default", ">" + new_redis_password], old_password)
            require(b"OK" in output and b"ERR" not in output, "Redis overlapping password add failed")
            self.redis_check(pod, new_redis_password, True)
            self.redis_check(pod, old_password, True)
        redis_uri = self.read("redis_uri_file")
        # All comma-separated seed URIs need the same newly accepted credential.
        new_redis_uri = ",".join(urlunsplit(urlsplit(seed)._replace(
            netloc=":" + quote(new_redis_password) + "@" + urlsplit(seed).netloc.rsplit("@", 1)[-1]))
                                 for seed in redis_uri.split(","))
        self.patch_data("secret", self.f["credentials_secret"],
                        {"mongo-app-password": new_mongo_password, "redis-password": new_redis_password,
                         "fluxgate.mongo.uri": new_uri, "fluxgate.redis.uri": new_redis_uri})
        self.rollout()
        self.available()
        # Update replication password before retiring the old default-user password.
        for pod in self.f["redis_pods"]:
            output = self.redis(pod, ["CONFIG", "SET", "masterauth", new_redis_password], new_redis_password)
            require(b"OK" in output and b"ERR" not in output, "Redis replication credential update failed")
        self.mongo(admin, "const removed=c.getDB(" + json.dumps(auth_db) + ").dropUser(" + json.dumps(username) +
                   "); if(!removed) throw Error('user retirement failed')")
        self.mongo(uri, protected_read, False)
        self.mongo(new_uri, protected_read)
        for pod in self.f["redis_pods"]:
            output = self.redis(pod, ["ACL", "SETUSER", "default", "resetpass", ">" + new_redis_password],
                                new_redis_password)
            require(b"OK" in output and b"ERR" not in output, "Redis old password retirement failed")
            self.redis_check(pod, old_password, False)
            self.redis_check(pod, new_redis_password, True)
        cold_restart = self.redis_cold_restart(new_redis_password, old_password)
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
                               "documented_cache_refresh_seconds": 300, "documented_cache_lifespan_seconds": 900,
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
                    output = json.dumps(baseline).encode()
                if status and check:
                    raise ProofError("offline sampler stop failure")
                return subprocess.CompletedProcess(args, status, output, b"")
            proof.kube = kube
            class Process:
                def __init__(self, *args, **kwargs):
                    self.stdin, self.returncode = io.BytesIO(), None
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
            original_popen = subprocess.Popen
            subprocess.Popen = Process
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


def self_test():
    """Own temporary localhost processes only; no fixture, kube mutations or application dependencies."""
    redis_policy_selection_self_test()
    tls_alert_read_self_test()
    strict_tls_self_test()
    tls_runtime_self_test()
    failed_rotation_cleanup_self_test()
    backend_body_self_test()
    sampler_cleanup_self_test()
    require(redis_hash_contents(b"revision\n1\nepoch\nstable") ==
            redis_hash_contents(b"epoch\nstable\nrevision\n1"), "Redis metadata compared hash iteration order")
    worker_source = Path(__file__).read_text().rsplit('\nif __name__ == "__main__":', 1)[0]
    compile(worker_source + "\nconfig=json.loads(sys.stdin.readline());print(json.dumps(sampler_worker(config,'/tmp/stop')))\n",
            "sampler-worker", "exec")
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
            observed = sampler_summary(sampler_worker({"service": "127.0.0.1", "port": server.server_port,
                "path": "/load", "host": "local", "api_key": "offline-fixture", "body": "backend-marker",
                "positive_path": str(Path(directory) / "positive"),
                "progress_path": str(Path(directory) / "progress.json")}, str(stop)))
            require(observed["statuses"] == {"200": 4, "503": 1} and observed["unexpected_body_responses"] == 2 and
                    observed["backend_successes"] == 2 and not observed["uninterrupted_observed"],
                    "actual traffic sampler hid status/body failures")
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
        proof.validate()
        for phase in phases:
            print("Credential phase: " + phase, flush=True)
            method = {"mtls": proof.tls, "api-key": proof.api_keys, "stores": proof.stores,
                      "jwt": proof.jwt}[phase]
            if phase in ("mtls", "api-key", "stores"):
                with proof.traffic_sampler(phase):
                    method()
            else:
                method()
        result = {"passed": True, "complete": set(phases) == {"mtls", "api-key", "stores", "jwt"},
                  "context": proof.f["context"], "namespace": proof.ns, "runtime": runtime, "results": proof.results,
                  "limits": ["Local ephemeral fixture only; no external credential authority tested.",
                             "Rotation availability is measured by 100ms in-cluster samples with explicit errors and omissions; no zero-downtime guarantee is inferred.",
                             "JWKS retirement records warm-cache behavior, checks an observed unknown-kid refresh, and checks a fresh decoder; key removal alone is not immediate revocation."]}
        private_write(args.output, json.dumps(result, indent=2) + "\n")
        print("Credential proof passed; evidence: " + str(Path(args.output).resolve()))
    except Exception as error:
        proof.cleanup_failed()
        # Error details may be secrets; output the safe proof assertion only.
        print("Credential proof failed: " + (str(error) if isinstance(error, ProofError) else type(error).__name__))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
