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
from pathlib import Path
import secrets
import socket
import ssl
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
                    require(self.f.get("backend_body", "fluxgate-resilience-ok").encode() in body,
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
                        request = ("GET " + path + " HTTP/1.1\r\nHost: " + self.f["server_name"] +
                                   "\r\nx-api-key: " + self.api_key + "\r\nConnection: close\r\n\r\n")
                        conn.sendall(request.encode())
                        response = http.client.HTTPResponse(conn)
                        response.begin()
                        response.read()
                        require(response.status == expected, "unexpected TLS HTTP status")
                        return {"http": response.status, "new_connection": True}
            except ssl.SSLError as error:
                require(expected == "tls-reject", "positive TLS probe rejected")
                # DNS/refused/reset/timeout or generic handshake errors are not certificate controls.
                require(error.reason in {"CERTIFICATE_VERIFY_FAILED", "TLSV13_ALERT_CERTIFICATE_REQUIRED",
                                         "TLSV1_ALERT_UNKNOWN_CA", "SSLV3_ALERT_BAD_CERTIFICATE",
                                         "SSLV3_ALERT_CERTIFICATE_EXPIRED", "TLSV1_ALERT_BAD_CERTIFICATE",
                                         "SSLV3_ALERT_CERTIFICATE_UNKNOWN"},
                        "TLS negative lacked explicit certificate rejection")
                return {"tls_rejected": True, "reason": error.reason, "new_connection": True}
        raise ProofError("negative TLS probe unexpectedly completed")

    def ca(self, stem):
        p = self.work / stem
        run([self.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-sha256", "-keyout", str(p) + ".key", "-out", str(p) + ".crt", "-subj", "/CN=" + stem])
        return Path(str(p) + ".crt"), Path(str(p) + ".key")

    def certificate(self, stem, ca, ca_key, subject, server=False, expired=False):
        p = self.work / stem
        run([self.openssl, "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(p) + ".key",
             "-out", str(p) + ".csr", "-subj", "/CN=" + subject])
        ext = "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
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
        stamp = next((line[len("POLICY:"):] for line in output.decode().splitlines()
                      if line.startswith("POLICY:")), "")
        require(bool(stamp), "published policy pointer missing")
        return json.loads(stamp)

    def api_keys(self):
        policy_before = self.policy_stamp()
        mappings_path = Path(self.f["api_key_mapping_file"])
        document = json.loads(mappings_path.read_text())
        original = document["fluxgate"]["envoy"]["api-keys"]
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
        self.backups.clear()

    def redis(self, pod, command, password=None):
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
                               "fixture": self.fixture_path.read_bytes()}
        self.mongo(admin, "c.getDB(" + json.dumps(auth_db) + ").createUser({user:" + json.dumps(new_user) +
                   ",pwd:" + json.dumps(new_mongo_password) + ",roles:[{role:'readWrite',db:'fluxgate'}]})")
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
                                            "overlap_passwords": True, "old_password_retired": True},
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

        def token(index, kid=None, aud=audience):
            header = b64url(json.dumps({"alg": "RS256", "kid": kid or keys[index][1]["kid"],
                                       "typ": "JWT"}, separators=(",", ":")).encode())
            claims = b64url(json.dumps({"iss": issuer, "aud": aud, "sub": "credential-proof-admin",
                                       "iat": int(time.time()), "exp": expiry,
                                       "realm_access": {"roles": ["admin"]}}, separators=(",", ":")).encode())
            message = (header + "." + claims).encode()
            signature = run([self.openssl, "dgst", "-sha256", "-sign", str(keys[index][0])], data=message).stdout
            return message.decode() + "." + b64url(signature)

        old_token, new_token = token(0), token(1)
        admin = self.read("mongo_admin_uri_file")
        output = self.mongo(admin, "print('PRIMARY:'+c.getDB('admin').hello().primary)")
        primary_host = next((line.removeprefix("PRIMARY:") for line in output.decode().splitlines()
                             if line.startswith("PRIMARY:")), "")
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
                    require(api(None)[0] == 401, "missing JWT accepted")
                    require(api(token(0, aud="wrong-api"))[0] == 401, "wrong JWT audience accepted")
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

    def cleanup_failed(self):
        failures = []
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


def self_test():
    """Own temporary localhost processes only; no fixture, kube mutations or application dependencies."""
    with tempfile.TemporaryDirectory(prefix="fluxgate-credential-unit-") as directory:
        proof = Proof.__new__(Proof)
        proof.work, proof.openssl = Path(directory), "openssl"
        ca, key = proof.ca("unit-ca")
        cert, cert_key = proof.certificate("expired", ca, key, "gateway", expired=True)
        result = run([proof.openssl, "verify", "-CAfile", str(ca), str(cert)], check=False)
        require(result.returncode != 0 and b"expired" in (result.stdout + result.stderr).lower(),
                "explicit expired certificate fixture was not expired")
        valid, valid_key = proof.certificate("valid", ca, key, "gateway")
        run([proof.openssl, "verify", "-CAfile", str(ca), str(valid)])
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
    print("Offline credential regressions passed: valid/expired X.509 and fresh RESP valid/wrong/missing authentication")


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
    proof = Proof(args.fixture)
    phases = args.phases.split(",")
    try:
        proof.validate()
        for phase in phases:
            print("Credential phase: " + phase, flush=True)
            method = {"mtls": proof.tls, "api-key": proof.api_keys, "stores": proof.stores,
                      "jwt": proof.jwt}[phase]
            method()
        result = {"passed": True, "complete": set(phases) == {"mtls", "api-key", "stores", "jwt"},
                  "context": proof.f["context"], "namespace": proof.ns, "results": proof.results,
                  "limits": ["Local ephemeral fixture only; no external credential authority tested.",
                             "JWKS retirement is checked with a fresh decoder, not a warm cache."]}
        private_write(args.output, json.dumps(result, indent=2) + "\n")
        print("Credential proof passed; evidence: " + str(Path(args.output).resolve()))
    except Exception as error:
        proof.cleanup_failed()
        # Error details may be secrets; output the safe proof assertion only.
        print("Credential proof failed: " + (str(error) if isinstance(error, ProofError) else type(error).__name__))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
