"""Actual mTLS API mapping controls and fail-closed pinned replica checks; no cluster."""
import contextlib
import copy
import http.server
import json
from pathlib import Path
import runpy
import ssl
import tempfile
import threading
import time
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'verify-credentials.py'))
Proof, ProofError = MODULE['Proof'], MODULE['ProofError']


class ApiPodControls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='api-pod-protocol-')
        cls.proof = Proof.__new__(Proof)
        cls.proof.work = Path(cls.directory.name)
        cls.proof.openssl = '/opt/homebrew/bin/openssl' if Path('/opt/homebrew/bin/openssl').exists() else 'openssl'
        ca, ca_key = cls.proof.ca('server-ca')
        _, server_key = cls.proof.certificate('server', ca, ca_key, 'localhost', server=True)
        cls.proof.certificate('client', ca, ca_key, 'trusted-gateway')
        cls.proof.f = {'tls_dir': str(cls.proof.work), 'server_name': 'localhost', 'load_path': '/load', 'gateway_host': 'offline'}
        cls.proof.results = {}
        cls.requests, cls.states, cls.servers = [], {}, []
        cls.pods = [{'metadata': {'name': 'pod-' + str(i), 'uid': 'uid-' + str(i)},
                     'status': {'conditions': [{'type': 'Ready', 'status': 'True'}]}} for i in range(2)]
        for index in range(2):
            def handler(index):
                class Handler(http.server.BaseHTTPRequestHandler):
                    def do_GET(self):
                        key = self.headers.get('x-api-key')
                        cls.requests.append((index, self.path, key, bool(self.connection.getpeercert())))
                        state = cls.states[index]
                        if state == 'timeout':
                            time.sleep(.3)
                            return
                        accepted = {'old'} if state == 'old-only' else {'old', 'new'} if state == 'overlap' else {'new'}
                        self.send_response(200 if key in accepted or state == 'missing-accepted' and key is None else 403)
                        self.send_header('Content-Length', '0')
                        self.end_headers()
                    def log_message(self, *args):
                        pass
                return Handler
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler(index))
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(str(cls.proof.work / 'server.crt'), str(server_key))
            ctx.load_verify_locations(cafile=str(ca))
            ctx.verify_mode = ssl.CERT_REQUIRED
            server.socket = ctx.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            cls.servers.append(server)
        cls.proof.forward = lambda resource, *args, **kwargs: contextlib.nullcontext(
            cls.servers[int(resource.rsplit('-', 1)[1])].server_port)

    @classmethod
    def tearDownClass(cls):
        for server in cls.servers:
            server.shutdown()
            server.server_close()
        cls.directory.cleanup()

    def setUp(self):
        self.requests.clear()
        self.proof.results = {}
        self.proof.get = lambda *args, **kwargs: {'spec': {'selector': {'matchLabels': {'app': 'authz'}}}}
        self.proof.kube = lambda *args, **kwargs: type('Output', (), {
            'stdout': json.dumps({'items': copy.deepcopy(self.pods)}).encode()})()

    def test_all_three_stages_authenticate_each_replica_without_quota(self):
        for stage in ('old-only', 'overlap', 'retirement'):
            self.states.update({0: stage, 1: stage})
            result = self.proof.api_key_pod_barrier(stage, 'old', 'new')
            self.assertEqual(len(result['pods']), 2)
            self.assertEqual({p['pod_uid'] for p in result['pods']}, {'uid-0', 'uid-1'})
            for pod in result['pods']:
                self.assertEqual(pod['statuses'], {'old': 403 if stage == 'retirement' else 200,
                    'new': 403 if stage == 'old-only' else 200, 'missing': 403, 'wrong': 403})
                self.assertTrue(pod['new_connections'])
            self.assertEqual(len(self.requests), 8 * (('old-only', 'overlap', 'retirement').index(stage) + 1))
        self.assertTrue(all(path == '/authz/load' and mtls for _, path, _, mtls in self.requests))
        self.assertNotIn('credentials', json.dumps(result))

    def test_one_wrong_loaded_replica_fails_without_retry(self):
        self.states.update({0: 'overlap', 1: 'old-only'})
        with self.assertRaises(ProofError):
            self.proof.api_key_pod_barrier('overlap', 'old', 'new')
        self.assertEqual(sum(i == 1 and key == 'new' for i, _, key, _ in self.requests), 1)

    def test_partial_unready_and_terminating_replicas_rejected(self):
        for pods in (self.pods[:1], self.pods + [copy.deepcopy(self.pods[0])],
                     [self.pods[0], dict(self.pods[1], status={})],
                     [self.pods[0], dict(self.pods[1], metadata=dict(self.pods[1]['metadata'], deletionTimestamp='now'))]):
            self.proof.kube = lambda *a, **k: type('Output', (), {'stdout': json.dumps({'items': pods}).encode()})()
            with self.assertRaises(ProofError):
                self.proof.api_key_pod_barrier('overlap', 'old', 'new')
        self.assertEqual(self.requests, [])

    def test_replacement_uid_after_controls_fails(self):
        self.states.update({0: 'overlap', 1: 'overlap'})
        calls = 0
        def kube(*args, **kwargs):
            nonlocal calls
            calls += 1
            pods = copy.deepcopy(self.pods)
            if calls == 2:
                pods[1]['metadata']['uid'] = 'replaced'
            return type('Output', (), {'stdout': json.dumps({'items': pods}).encode()})()
        self.proof.kube = kube
        with self.assertRaises(ProofError):
            self.proof.api_key_pod_barrier('overlap', 'old', 'new')
        self.assertEqual(len(self.requests), 8)

    def test_untrusted_server_rejected(self):
        untrusted, _ = self.proof.ca('untrusted-ca')
        with self.assertRaises(ProofError):
            self.proof.api_key_pod_probe('pod-0', untrusted, self.proof.work / 'client.crt',
                self.proof.work / 'client.key', '/authz/load', 'old', 200, time.monotonic() + 1)

    def test_socket_timeout_rejected_without_retry(self):
        self.states[0] = 'timeout'
        start = time.monotonic()
        with self.assertRaises(ProofError):
            self.proof.api_key_pod_probe('pod-0', self.proof.work / 'server-ca.crt',
                self.proof.work / 'client.crt', self.proof.work / 'client.key',
                '/authz/load', 'old', 200, start + .1)
        self.assertLess(time.monotonic() - start, .5)
        self.assertEqual(len(self.requests), 1)

    def test_missing_header_must_be_rejected_by_each_replica(self):
        self.states.update({0: 'retirement', 1: 'missing-accepted'})
        with self.assertRaises(ProofError):
            self.proof.api_key_pod_barrier('retirement', 'old', 'new')
        self.assertEqual(sum(i == 1 and key is None for i, _, key, _ in self.requests), 1)

    def test_one_deadline_propagates_to_queries_and_forwarding(self):
        self.states.update({0: 'overlap', 1: 'overlap'})
        deadlines, query_timeouts = [], []
        forward, get, kube = self.proof.forward, self.proof.get, self.proof.kube
        def recording_forward(*args, **kwargs):
            deadlines.append(kwargs['deadline'])
            return forward(*args, **kwargs)
        def recording_get(*args, **kwargs):
            query_timeouts.append(kwargs['timeout'])
            return get(*args, **kwargs)
        def recording_kube(*args, **kwargs):
            query_timeouts.append(kwargs['timeout'])
            return kube(*args, **kwargs)
        self.proof.forward, self.proof.get, self.proof.kube = recording_forward, recording_get, recording_kube
        try:
            self.proof.api_key_pod_barrier('overlap', 'old', 'new')
        finally:
            self.proof.forward = forward
        self.assertEqual(len(deadlines), 8)
        self.assertEqual(len(set(deadlines)), 1)
        self.assertTrue(all(0 < timeout <= 5 for timeout in query_timeouts))

    def test_barrier_failure_preserves_rotation_rollback_and_failure_timeline(self):
        mapping = self.proof.work / 'offline-mapping'
        original = b'{"fluxgate":{"envoy":{"api-keys":[]}}}'
        MODULE['private_write'](mapping, original)
        self.proof.f['api_key_mapping_file'] = str(mapping)
        self.proof.f['quota_path'] = '/quota'
        self.proof.policy_stamp = lambda: {'revision': 1}
        self.proof.backups = {}
        self.proof.store_rollback = self.proof.tls_rollback = None
        self.proof.cold_sentinels = []
        calls = []
        def mapping_change(entries):
            calls.append('mapping')
            mapping.write_text(json.dumps({'fluxgate': {'envoy': {'api-keys': entries}}}))
        self.proof.api_mapping = mapping_change
        self.proof.gateway = lambda *args: self.fail('quota must not run after barrier failure')
        self.states.update({0: 'retirement', 1: 'retirement'})
        with self.assertRaises(ProofError):
            self.proof.api_keys()  # Real mTLS replica deliberately lacks the just-created old key.
        self.assertEqual(calls, ['mapping'])
        self.assertIsNotNone(self.proof.api_rollback)
        self.assertEqual(self.proof.results['rotation_timeline'][-1]['action'], 'api.barrier-old-only')
        self.assertEqual(self.proof.results['rotation_timeline'][-1]['state'], 'failure')
        self.proof.cleanup_failed()
        self.assertEqual(mapping.read_bytes(), original)
