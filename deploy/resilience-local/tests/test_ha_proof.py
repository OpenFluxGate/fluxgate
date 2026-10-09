"""Offline HA proof controls; run with unittest discover, without a cluster or credentials."""
import ast
import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'verify-ha.py'
MODULE = runpy.run_path(str(SCRIPT))  # __main__ guard prevents fixture access and mutations.
MAIN = next(node for node in ast.parse(SCRIPT.read_text()).body
            if isinstance(node, ast.FunctionDef) and node.name == 'main')


class Clock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def time_ns(self):
        return int(self.now * 1_000_000_000)

    def sleep(self, seconds):
        self.now += seconds


class Response:
    def __init__(self, status, body, headers, read_error=False):
        self.status, self.body, self.headers = status, body, headers
        self.read_error = read_error

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self):
        if self.read_error:
            raise OSError('Mock response read failure')
        return self.body


def dense_samples(statuses, budget=10, body=b'backend\n', headers=None, read_error=False,
                  error_body=b'unavailable'):
    clock, statuses = Clock(), iter(statuses)

    class Opener:
        def open(self, request, timeout):
            if not 0 < timeout <= 2:
                raise AssertionError('Unbounded dense request timeout')
            clock.now += min(.05, timeout)
            status = next(statuses, 503)
            if isinstance(status, Exception):
                raise status
            return Response(status, body if status == 200 else error_body, headers or {}, read_error)

    output = io.StringIO()
    with patch.object(sys, 'argv', ['dense', 'http://gateway', '/api/load', 'fixture', 'backend', str(budget)]), \
            patch('pathlib.Path.read_text', return_value='public-test-key'), \
            patch('urllib.request.build_opener', return_value=Opener()), \
            patch('time.monotonic', clock.monotonic), patch('time.time_ns', clock.time_ns), \
            patch('time.sleep', clock.sleep), redirect_stdout(output):
        exec(MODULE['DENSE_RECOVERY_SOURCE'], {})
    return json.loads(output.getvalue())


def nested_function(name):
    return copy.deepcopy(next(node for node in MAIN.body
                              if isinstance(node, ast.FunctionDef) and node.name == name))


class DenseRecoveryTests(unittest.TestCase):
    def test_interrupted_streak_requires_ten_successes_over_two_seconds(self):
        result = dense_samples([200] * 3 + [503] + [200] * 20)
        self.assertTrue(result['recovered'])
        self.assertEqual(result['samples'][3]['status'], 503)
        window = result['success_window']
        self.assertGreaterEqual(len(window), 10)
        self.assertGreater(window[0]['requested_unix_ms'], result['samples'][3]['completed_unix_ms'])
        self.assertGreaterEqual(window[-1]['completed_unix_ms'] - window[0]['completed_unix_ms'], 2000)

    def test_complete_body_accepts_only_optional_single_lf(self):
        for body in (b'backend', b'backend\n'):
            with self.subTest(body=body):
                self.assertTrue(dense_samples([200] * 20, body=body)['recovered'])
        for body in (b'wrong', b' backend', b'backend ', b'backend\n\n', b'\nbackend\n',
                     b'backend\r\n', b'backend-suffix', b'\xffbackend'):
            with self.subTest(body=body):
                result = dense_samples([200], body=body)
                self.assertFalse(result['recovered'])
                self.assertTrue(result['samples'][0]['unexpected'])

    def test_stale_route_marker_and_unexpected_status_fail_closed(self):
        for kwargs in ({'headers': {'x-ha-controller-proof': 'stale'}}, {}):
            with self.subTest(kwargs=kwargs):
                result = dense_samples([200] if kwargs else [500], **kwargs)
                self.assertFalse(result['recovered'])
                self.assertTrue(result['samples'][0]['unexpected'])
        result = dense_samples([503], error_body=b'prefix-backend-suffix')
        self.assertTrue(result['samples'][0]['unexpected'])

    def test_transport_and_read_failures_reset_streak(self):
        result = dense_samples([200] * 3 + [OSError('Mock transport failure')] + [200] * 20)
        self.assertTrue(result['recovered'])
        self.assertEqual(result['samples'][3]['transport_error'], 'OSError')
        self.assertGreater(result['success_window'][0]['requested_unix_ms'],
                           result['samples'][3]['completed_unix_ms'])
        result = dense_samples([200] * 100, budget=1, read_error=True)
        self.assertFalse(result['recovered'])
        self.assertTrue(all(sample.get('transport_error') for sample in result['samples']))

    def test_insufficient_streak_or_budget_cannot_recover(self):
        for statuses in ([200] * 3, [503] * 100):
            with self.subTest(statuses=statuses[:3]):
                self.assertFalse(dense_samples(statuses, budget=1)['recovered'])

    def test_concurrent_failure_rejects_candidate_and_global_deadline_remains_thirty(self):
        clock, background, calls = Clock(), [], []

        def kube(args, timeout):
            self.assertLessEqual(timeout, 30 - clock.now)
            start = clock.time_ns() // 1_000_000
            calls.append(args)
            clock.now += 2
            end = clock.time_ns() // 1_000_000
            background.append({'status': 503 if len(calls) == 1 else 200, 'completed_unix_ms': end})
            return json.dumps({'recovered': True, 'samples': [{'status': 200}],
                               'success_window': [{'requested_unix_ms': start, 'completed_unix_ms': end}]})

        environment = {'time': clock, 'fixture': {'gateway_service': 'gateway', 'gateway_namespace': 'eg',
                       'load_path': '/api/load', 'gateway_host': 'fixture', 'backend_body': 'backend'},
                       'json': json, 'ns': 'fixture', 'probe': 'probe', 'kube': kube,
                       'DENSE_RECOVERY_SOURCE': MODULE['DENSE_RECOVERY_SOURCE'],
                       'utc_milestone': lambda: {'unix_ms': clock.time_ns() // 1_000_000}}
        function = nested_function('dense_gateway_recovery')
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), 'exec'), environment)
        evidence = {'timestamps': {}}
        elapsed, _ = environment['dense_gateway_recovery'](0, lambda: True, background, evidence)
        self.assertEqual(elapsed, 6)
        self.assertEqual(len(calls), 3)
        self.assertTrue(evidence['dense_gateway_batches'][0]['background_window_rejected'])
        clock.now = 29.8
        with self.assertRaisesRegex(RuntimeError, 'original 30s'):
            environment['dense_gateway_recovery'](0, lambda: True, background, {'timestamps': {}})
        self.assertEqual(len(calls), 3)

    def test_deferred_roles_option_rejected_before_fixture_access_outside_redis(self):
        for phase in ('all', 'baseline', 'mongo', 'node', 'no-quorum', 'pod-restarts'):
            with self.subTest(phase=phase):
                result = subprocess.run([sys.executable, str(SCRIPT), '--fixture', '/does-not-exist',
                                         '--phase', phase, '--preserve-promoted-roles'],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 2)
                self.assertIn('valid only with --phase redis', result.stderr)


class RestorationTests(unittest.TestCase):
    def restoration(self, defer, policy_drift=False, counter_drift=False):
        function = nested_function('full_restore')
        # Isolate role restoration from Kubernetes health polling; retain actual failback/preservation code.
        health = next(node for node in function.body if isinstance(node, ast.FunctionDef) and node.name == 'healthy')
        health.body = ast.parse("return {'healthy': True}").body
        factory = ast.parse('def factory():\n    restore_deadline = None\n').body[0]
        factory.body.extend([function, ast.Return(value=ast.Name(id='full_restore', ctx=ast.Load()))])
        nodes = {f'id{i}': {'pod': f'redis-{i}-0', 'flags': ['master'] if i in (1, 2, 3) else ['slave'],
                 'primary': '-' if i in (1, 2, 3) else 'id3' if i in (0, 6) else 'id1' if i in (4, 7) else 'id2',
                 'slots': ['0-5460'] if i == 3 else ['5461-10922'] if i == 1 else ['10923-16383'] if i == 2 else []}
                 for i in range(9)}
        placements = {f'redis-{i}-0': f'node{i % 3}' for i in range(9)}
        placements['redis-3-0'] = 'node1'
        records, commands, policy_reads, counter_reads = [], [], [], []

        def redis(pod, args):
            commands.append(args)
            if args == ['INFO', 'replication']:
                return 'master_link_status:up'
            self.assertEqual((pod, args), ('redis-0-0', ['CLUSTER', 'FAILOVER']))
            for node in nodes.values():
                if node['primary'] == 'id3':
                    node['primary'] = 'id0'
            nodes['id0'].update(flags=['master'], primary='-', slots=nodes['id3']['slots'])
            nodes['id3'].update(flags=['slave'], primary='id0', slots=[])
            return 'OK'

        def policy():
            policy_reads.append(1)
            return {'policy': 2 if policy_drift and len(policy_reads) > 1 else 1}

        def bucket():
            counter_reads.append(1)
            return {'tokens': '1' if counter_drift and len(counter_reads) > 1 else '0'}

        environment = {'time': Clock(), 'utc_milestone': lambda: {'unix_ms': 0},
                       'wait': lambda label, action, budget: (action(), 0),
                       'topology': lambda: copy.deepcopy(nodes),
                       'obj': lambda *args, **kwargs: {'items': [{'metadata': {'name': pod},
                               'spec': {'nodeName': node}} for pod, node in placements.items()]},
                       'home_failover_targets': MODULE['home_failover_targets'], 'policy': policy,
                       'bucket': bucket, 'redis': redis, 'route_header': None, 'route_restore': None,
                       'sustained_traffic': lambda *args, **kwargs: (3, [{'status': 200}] * 3),
                       'record': lambda name, **data: records.append((name, data))}
        exec(compile(ast.fix_missing_locations(ast.Module(body=[factory], type_ignores=[])),
                     str(SCRIPT), 'exec'), environment)
        result = environment['factory']()(0, preserve_promoted_roles=defer)
        role = next(data for name, data in records if name == 'redis-primary-placement-restoration')
        self.assertTrue(role['policy_exact_preserved'] and role['ha_counter_exact_preserved'])
        self.assertEqual(role['performed'], not defer)
        self.assertEqual(result['placement_restoration_deferred'], defer)
        self.assertEqual(result['redis_primary_nodes_distinct'], not defer)
        self.assertEqual(commands.count(['CLUSTER', 'FAILOVER']), 0 if defer else 1)
        return result

    def test_deferred_roles_do_not_issue_failback(self):
        self.restoration(True)

    def test_default_restoration_uses_only_normal_failover(self):
        self.restoration(False)

    def test_policy_and_counter_drift_block_both_restoration_modes(self):
        for defer in (True, False):
            for drift in ('policy_drift', 'counter_drift'):
                with self.subTest(defer=defer, drift=drift), self.assertRaises(AssertionError):
                    self.restoration(defer, **{drift: True})


class MongoPrimaryTests(unittest.TestCase):
    def observation(self):
        host = 'mongo-1.mongo.fluxgate-resilience.svc.cluster.local:27017'
        status = {'ok': 1, 'set': 'rs0', 'myState': 1, 'members': [
            {'name': 'mongo-0.mongo.fluxgate-resilience.svc.cluster.local:27017', 'state': 1},
            {'name': host, 'state': 1, 'self': True},
            {'name': 'mongo-2.mongo.fluxgate-resilience.svc.cluster.local:27017', 'state': 2}]}
        hello = {'ok': 1, 'setName': 'rs0', 'isWritablePrimary': True, 'me': host, 'primary': host}
        return {'status': status, 'hello': hello}

    def select(self, observation):
        return MODULE['verified_mongo_primary'](observation, ['mongo-0', 'mongo-1', 'mongo-2'],
                                                'fluxgate-resilience')

    def test_stale_old_primary_member_does_not_override_writable_self(self):
        self.assertEqual(self.select(self.observation()), 'mongo-1')

    def test_foreign_host_secondary_or_ambiguous_identity_rejected(self):
        cases = [
            ('hello', 'isWritablePrimary', False), ('hello', 'me', 'foreign:27017'),
            ('hello', 'primary', 'mongo-0.mongo.fluxgate-resilience.svc.cluster.local:27017'),
            ('hello', 'setName', 'foreign-set'), ('status', 'myState', 2),
            ('status', 'ok', 0), ('hello', 'ok', 0)]
        for section, field, value in cases:
            with self.subTest(section=section, field=field):
                observation = self.observation()
                observation[section][field] = value
                with self.assertRaises(AssertionError):
                    self.select(observation)
        for change in ('duplicate-self', 'secondary-self', 'foreign-self', 'no-self'):
            with self.subTest(change=change):
                observation = self.observation()
                members = observation['status']['members']
                if change == 'duplicate-self':
                    members[0]['self'] = True
                elif change == 'secondary-self':
                    members[1]['state'] = 2
                elif change == 'foreign-self':
                    members[1]['name'] = observation['hello']['me'] = observation['hello']['primary'] = 'foreign:27017'
                else:
                    members[1].pop('self')
                with self.assertRaises(AssertionError):
                    self.select(observation)

    def test_nested_selector_uses_hello_and_status_in_same_connection(self):
        observation = self.observation()
        calls = []
        environment = {'mongo': lambda source: calls.append(source) or observation,
                       'fixture': {'mongo_pods': ['mongo-0', 'mongo-1', 'mongo-2']},
                       'ns': 'fluxgate-resilience',
                       'verified_mongo_primary': MODULE['verified_mongo_primary']}
        function = nested_function('mongo_primary')
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), 'exec'), environment)
        self.assertEqual(environment['mongo_primary'](), 'mongo-1')
        self.assertIn('hello:1', calls[0])
        self.assertIn('replSetGetStatus:1', calls[0])


if __name__ == '__main__':
    unittest.main()
