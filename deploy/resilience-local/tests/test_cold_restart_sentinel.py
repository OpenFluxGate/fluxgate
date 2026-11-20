"""Offline cold-restart sentinel controls; never access a cluster or credentials."""
import copy
import hashlib
from pathlib import Path
import runpy
from types import SimpleNamespace
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'verify-credentials.py'))
Proof, ProofError = MODULE['Proof'], MODULE['ProofError']


class ColdRestartSentinel(unittest.TestCase):
    def fixture(self, baseline=bytes(range(32)), recovered=None, write_error=False):
        proof = Proof.__new__(Proof)
        proof.f = {'rule_set_id': 'fixture', 'quota_rule_id': 'quota',
                   'load_rule_id': 'load', 'redis_pods': ['redis-0']}
        proof.cold_sentinels = []
        proof.backups, proof.tls_rollback, proof.store_rollback = {}, None, None
        calls, checks, state = [], [], {'deleted': False}
        pod = {'metadata': {'uid': 'old'}, 'spec': {'volumes': [
            {'persistentVolumeClaim': {'claimName': 'data'}}]},
            'status': {'podIP': '10.0.0.2', 'conditions': [{'type': 'Ready', 'status': 'True'}]}}
        pvc = {'metadata': {'uid': 'pvc-original'}, 'spec': {'volumeName': 'pv-original'}}
        metadata_key = 'fluxgate:policy:fluxgate:bucket:{fixture:quota:identity}:daily'
        metadata = b'revision\n2\ncapacity\n100\nwindow_micros\n86400000000\nalgorithm\n1'

        def get(resource):
            if resource.startswith('pvc/'):
                return copy.deepcopy(pvc)
            result = copy.deepcopy(pod)
            if state['deleted']:
                result['metadata']['uid'] = 'new'
            return result

        def redis(member, args, password, **kwargs):
            command = tuple(args[:2])
            return {('ROLE',): b'slave\n', ('SCAN', '0'): ('0\n' + metadata_key).encode(),
                    ('TYPE', metadata_key): b'hash', ('HGETALL', metadata_key): metadata,
                    ('TTL', metadata_key): b'700', ('CLUSTER', 'MYID'): b'node-id',
                    ('INFO', 'replication'): b'role:slave\nmaster_link_status:up',
                    ('CLUSTER', 'INFO'): b'cluster_state:ok\ncluster_known_nodes:1',
                    ('CLUSTER', 'NODES'): b'node-id 10.0.0.2:6379@16379 myself,slave parent 0 0 1 connected'}[command]

        def kube(*args, **kwargs):
            calls.append((args, kwargs))
            if args[0] == 'delete':
                state['deleted'] = True
            elif args[0] == 'exec' and 'head -c 32 /dev/urandom > "$1"' in args:
                # Cleanup ownership must precede even a failed generation command.
                self.assertEqual(proof.cold_sentinels, [('redis-0', args[-1])])
                if write_error:
                    raise ProofError('injected sentinel generation failure')
            elif args[0] == 'exec' and 'cat' in args:
                return SimpleNamespace(stdout=(baseline if not state['deleted'] else
                                              baseline if recovered is None else recovered), returncode=0)
            return SimpleNamespace(stdout=b'', returncode=0)

        proof.get, proof.redis, proof.kube = get, redis, kube
        proof.redis_check = lambda *args: checks.append(args)
        proof.available = lambda: checks.append(('gateway',))
        return proof, calls, checks

    def test_pod_generates_binary_baseline_without_stdin_and_cold_read_matches_exactly(self):
        baseline = bytes(range(32))  # Includes NUL/newline; binary data stays unmodified.
        proof, calls, checks = self.fixture(baseline)
        result = proof.redis_cold_restart('new', 'retired')
        generation = [args for args, _ in calls if 'head -c 32 /dev/urandom > "$1"' in args]
        self.assertEqual(len(generation), 1)
        self.assertEqual(generation[0][:6], ('exec', 'redis-0', '--', 'sh', '-c',
                         'head -c 32 /dev/urandom > "$1"'))
        self.assertTrue(all('-i' not in args and 'data' not in kwargs for args, kwargs in calls))
        self.assertEqual(result['mounted_data_sha256'], hashlib.sha256(baseline).hexdigest())
        self.assertEqual(result['before_uid'], 'old')
        self.assertEqual(result['after_uid'], 'new')
        self.assertTrue(result['policy_metadata_preserved'])
        self.assertIn(('redis-0', 'retired', False), checks)
        self.assertIn(('redis-0', None, False), checks)
        self.assertIn(('gateway',), checks)
        self.assertEqual(proof.cold_sentinels, [])
        self.assertTrue(any('rm -- "$1"' in args for args, _ in calls))

    def assert_invalid_baseline(self, baseline):
        proof, calls, _ = self.fixture(baseline)
        with self.assertRaisesRegex(ProofError, 'exactly 32 bytes'):
            proof.redis_cold_restart('new', 'retired')
        self.assertFalse(any(args[0] == 'delete' for args, _ in calls))
        self.assertEqual(len(proof.cold_sentinels), 1)
        sentinel = proof.cold_sentinels[0][1]
        proof.cleanup_failed()
        self.assertEqual(calls[-1], (('exec', 'redis-0', '--', 'sh', '-c',
                                     'rm -- "$1"', 'sentinel', sentinel), {'check': False}))

    def test_empty_initial_read_rejected_before_deletion_and_cleaned(self):
        self.assert_invalid_baseline(b'')

    def test_truncated_initial_read_rejected_before_deletion_and_cleaned(self):
        self.assert_invalid_baseline(bytes(range(31)))

    def test_oversized_initial_read_rejected_before_deletion_and_cleaned(self):
        self.assert_invalid_baseline(bytes(range(33)))

    def test_same_size_changed_cold_read_rejected_and_cleaned(self):
        proof, calls, _ = self.fixture(recovered=b'x' * 32)
        with self.assertRaisesRegex(ProofError, 'lost mounted data'):
            proof.redis_cold_restart('new', 'retired')
        self.assertTrue(any(args[0] == 'delete' for args, _ in calls))
        self.assertEqual(len(proof.cold_sentinels), 1)
        proof.cleanup_failed()
        self.assertIn('rm -- "$1"', calls[-1][0])

    def test_generation_failure_is_registered_for_cleanup_before_deletion(self):
        proof, calls, _ = self.fixture(write_error=True)
        with self.assertRaisesRegex(ProofError, 'generation failure'):
            proof.redis_cold_restart('new', 'retired')
        self.assertFalse(any(args[0] == 'delete' for args, _ in calls))
        self.assertEqual(len(proof.cold_sentinels), 1)
        proof.cleanup_failed()
        self.assertIn('rm -- "$1"', calls[-1][0])


if __name__ == '__main__':
    unittest.main()
