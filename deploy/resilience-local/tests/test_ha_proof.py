"""Offline HA proof controls; run with unittest discover, without a cluster or credentials."""
import ast
import copy
from contextlib import redirect_stdout
import io
import json
import os
import signal
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import MagicMock, patch


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
                  error_body=b'unavailable', handshake_delay=None):
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

    class Input:
        def readline(self):
            clock.sleep(handshake_delay or 0)
            return json.dumps({'remaining_seconds': budget}) + '\n'

    output = io.StringIO()
    budget_arg = 'handshake' if handshake_delay is not None else str(budget)
    with patch.object(sys, 'argv', ['dense', 'http://gateway', '/api/load', 'fixture', 'backend', budget_arg]), \
            patch.object(sys, 'stdin', Input()), \
            patch('pathlib.Path.read_text', return_value='public-test-key'), \
            patch('urllib.request.build_opener', return_value=Opener()), \
            patch('time.monotonic', clock.monotonic), patch('time.time_ns', clock.time_ns), \
            patch('time.sleep', clock.sleep), redirect_stdout(output):
        exec(MODULE['DENSE_RECOVERY_SOURCE'], {})
    events = [json.loads(line) for line in output.getvalue().splitlines()]
    result = events[-1]
    if handshake_delay is not None:
        result['stream_events'] = events[:-1]
    return result


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
                       'json': json, 'ns': 'fixture', 'probe': 'probe', 'kube_base': ['kubectl'], 'env': {},
                       'run_dense_gateway_probe': lambda command, env, deadline: json.loads(kube(command, deadline - clock.now)),
                       'DenseProbeFailure': MODULE['DenseProbeFailure'], 'subprocess': subprocess,
                       'DENSE_RECOVERY_SOURCE': MODULE['DENSE_RECOVERY_SOURCE'],
                       'utc_milestone': lambda: {'unix_ms': clock.time_ns() // 1_000_000}}
        function = nested_function('dense_gateway_recovery')
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), 'exec'), environment)
        evidence = {'timestamps': {}}
        elapsed, _ = environment['dense_gateway_recovery'](0, lambda: True, background, evidence)
        self.assertEqual(elapsed, 6)
        self.assertEqual(len(calls), 3)
        self.assertTrue(evidence['dense_gateway_batches'][0]['background_window_rejected'])
        clock.now = 30
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
                       'repair_replica_chains': lambda *args: [],
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


class ReplicaRepairTests(unittest.TestCase):
    def topology(self):
        return {f'id{i}': {'pod': f'redis-{i}-0', 'flags': ['master'] if i < 3 else ['slave'],
                'primary': '-' if i < 3 else f'id{i % 3}',
                'slots': [f'{i * 5461}-{16383 if i == 2 else (i + 1) * 5461 - 1}'] if i < 3 else []}
                for i in range(9)}

    def targets(self, nodes):
        return MODULE['replica_reparent_targets'](nodes, [f'redis-{i}-0' for i in range(9)])

    def test_existing_direct_replicas_need_no_repair(self):
        self.assertEqual(self.targets(self.topology()), [])

    def test_chained_replica_reparents_only_to_its_existing_shard_primary(self):
        nodes = self.topology()
        nodes['id8']['primary'] = 'id5'
        self.assertEqual(self.targets(nodes), [{'pod': 'redis-8-0', 'identity': 'id8',
                         'previous_primary_id': 'id5', 'primary_id': 'id2', 'primary_pod': 'redis-2-0'}])

    def test_foreign_shard_cycle_slots_unhealthy_or_missing_member_rejected(self):
        for change in ('foreign-shard', 'cycle', 'replica-slots', 'failed-master', 'two-masters', 'missing'):
            with self.subTest(change=change):
                nodes = self.topology()
                if change == 'foreign-shard': nodes['id8']['primary'] = 'id1'
                elif change == 'cycle': nodes['id8']['primary'], nodes['id5']['primary'] = 'id5', 'id8'
                elif change == 'replica-slots': nodes['id8']['slots'] = ['1']
                elif change == 'failed-master': nodes['id2']['flags'].append('fail')
                elif change == 'two-masters': nodes['id5'].update(flags=['master'], primary='-', slots=['1'])
                else: nodes.pop('id8')
                with self.assertRaises(AssertionError): self.targets(nodes)


    def repair(self, disagreement=False, drift=None, expired=False):
        nodes = self.topology()
        nodes['id8']['primary'] = 'id5'
        pods = [f'redis-{i}-0' for i in range(9)]
        commands, budgets = [], []
        clock = Clock()
        if expired: clock.now = 180

        def topology(pod=None):
            view = copy.deepcopy(nodes)
            if pod is not None:
                next(node for node in view.values() if node['pod'] == pod)['flags'].append('myself')
            if disagreement and pod == 'redis-7-0': view['id8']['primary'] = 'id2'
            return view

        def redis(pod, args):
            commands.append((pod, args))
            if args == ['CLUSTER', 'INFO']:
                return 'cluster_state:ok\ncluster_slots_ok:16384\ncluster_known_nodes:9'
            if args == ['INFO', 'replication']:
                return 'role:master' if pod == 'redis-2-0' else 'master_link_status:up'
            self.assertEqual((pod, args), ('redis-8-0', ['CLUSTER', 'REPLICATE', 'id2']))
            nodes['id8']['primary'] = 'id2'
            return 'OK'

        def wait(label, action, budget):
            budgets.append(budget)
            if budget <= 0: raise RuntimeError('Original restoration deadline expired')
            return action(), 0

        environment = {'fixture': {'redis_pods': pods}, 'time': clock, 'wait': wait,
                       'topology': topology, 'redis': redis,
                       'replica_reparent_targets': MODULE['replica_reparent_targets'],
                       'policy': lambda: {'revision': 2 if drift == 'policy' else 1},
                       'bucket': lambda: {'tokens': '1' if drift == 'counter' else '0'}}
        function = nested_function('repair_replica_chains')
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), 'exec'), environment)
        repairs = environment['repair_replica_chains'](180, {'revision': 1}, {'tokens': '0'})
        self.assertTrue(all(0 < budget <= 180 for budget in budgets))
        self.assertEqual([entry for entry in commands if entry[1][:2] == ['CLUSTER', 'REPLICATE']],
                         [('redis-8-0', ['CLUSTER', 'REPLICATE', 'id2'])])
        self.assertFalse(any(args[0] in ('FLUSHALL', 'FLUSHDB', 'DEL', 'RESET', 'FAILOVER')
                             or 'FORCE' in args or 'TAKEOVER' in args for _, args in commands))
        self.assertTrue(repairs[0]['operator_assisted'])
        self.assertTrue(repairs[0]['policy_exact_preserved'] and repairs[0]['ha_counter_exact_preserved'])
        return repairs

    def test_actual_repair_orchestration_uses_only_normal_same_shard_replicate(self):
        self.repair()

    def test_consensus_deadline_and_policy_counter_drift_block_repair_proof(self):
        with self.assertRaises(AssertionError): self.repair(disagreement=True)
        with self.assertRaisesRegex(RuntimeError, 'deadline'): self.repair(expired=True)
        for drift in ('policy', 'counter'):
            with self.subTest(drift=drift), self.assertRaises(AssertionError): self.repair(drift=drift)


class DenseDeadlineDiagnosticsTests(unittest.TestCase):
    def test_partial_stream_whitelist_excludes_credentials_and_raw_bodies(self):
        partial = '\n'.join([json.dumps({'event': 'ready', 'remote_started_unix_ms': 100}),
                    json.dumps({'event': 'sample', 'status': 503, 'body_valid': False,
                                'request_seconds': 2, 'api_key': 'DO-NOT-PERSIST', 'body': 'DO-NOT-PERSIST'}),
                    'incomplete {'])
        result = MODULE['dense_stream_evidence'](partial)
        self.assertEqual(result['samples'][0]['status'], 503)
        self.assertNotIn('DO-NOT-PERSIST', json.dumps(result))
        self.assertNotIn('recovered', result)

    def test_expired_root_deadline_starts_no_process(self):
        with patch('subprocess.Popen') as popen, patch('time.monotonic', return_value=30):
            with self.assertRaises(MODULE['DenseProbeFailure']):
                MODULE['run_dense_gateway_probe'](['owned-kubectl'], {}, 30)
            popen.assert_not_called()


    def test_actual_stream_budget_includes_grant_transit_and_preserves_public_samples(self):
        result = dense_samples([200] * 100, budget=3.5, handshake_delay=2)
        self.assertFalse(result['recovered'])  # Grant is measured from READY, not its late arrival.
        self.assertEqual(result['stream_events'][0]['event'], 'ready')
        samples = result['stream_events'][1:]
        self.assertTrue(samples)
        self.assertTrue(all(sample['event'] == 'sample' for sample in samples))
        self.assertTrue(all('request_seconds' in sample and 'body_valid' in sample for sample in samples))
        self.assertNotIn('public-test-key', json.dumps(result))
        self.assertTrue(dense_samples([200] * 100, budget=5, handshake_delay=2)['recovered'])

    def rpc(self, overrun=False, timeout=False, denied_group=False, denied_child=False):
        clock = Clock()
        process = MagicMock()
        process.pid, process.returncode = 12345, 0
        process.stdout.fileno.return_value = 42
        process.poll.return_value = None if timeout or overrun else 0
        if denied_child:
            process.terminate.side_effect = PermissionError(1, 'private error')
            process.kill.side_effect = PermissionError(1, 'private error')
        ready = json.dumps({'event': 'ready', 'remote_started_unix_ms': 900000,
                            'remote_started_monotonic_seconds': 1000}).encode() + b'\n'
        sample = json.dumps({'event': 'sample', 'status': 503, 'body_valid': False,
                             'request_seconds': 2, 'requested_unix_ms': 910000,
                             'api_key': 'DO-NOT-PERSIST', 'body': 'DO-NOT-PERSIST'}).encode() + b'\n'
        result = json.dumps({'event': 'result', 'recovered': True, 'samples': [],
                             'consecutive_successes': 10, 'success_window': []}).encode() + b'\n'
        grants = []
        timeout_failure = timeout

        def ready_readable(*args):
            clock.now = 3  # Remote startup/readiness transit consumes three of the ORIGINAL thirty seconds.
            return [process.stdout], [], []

        def communicate(input=None, timeout=None):
            if input is None:
                if denied_child:
                    raise subprocess.TimeoutExpired('cleanup', timeout)
                process.poll.return_value = 0
                return b'', b''  # Independent bounded child-group cleanup.
            grants.append(json.loads(input)['remaining_seconds'])
            self.assertEqual(timeout, 27)
            clock.now = 31 if overrun or timeout_failure else 5
            if timeout_failure: raise subprocess.TimeoutExpired('dense-probe', timeout, output=sample)
            return sample + result, b'ignored-private-stderr'

        process.communicate.side_effect = communicate
        with patch('subprocess.Popen', return_value=process) as popen, \
                patch('time.monotonic', clock.monotonic), patch('time.time_ns', clock.time_ns), \
                patch('time.sleep', clock.sleep), patch('select.select', side_effect=ready_readable), patch('os.read', return_value=ready), \
                patch('os.killpg') as kill:
            def group_signal(pid, signal):
                if denied_group:
                    raise PermissionError(1, 'private group error')
                if signal == 0:
                    raise ProcessLookupError()
            kill.side_effect = group_signal
            if timeout or overrun:
                with self.assertRaises(subprocess.TimeoutExpired) as raised:
                    MODULE['run_dense_gateway_probe'](['owned-kubectl'], {}, 30)
                evidence = raised.exception.dense_evidence
                self.assertTrue(evidence['root_rpc_timed_out'])
                self.assertEqual(evidence['sample_count'], 1)
                self.assertNotIn('DO-NOT-PERSIST', json.dumps(evidence))
                self.assertNotIn('ignored-private-stderr', json.dumps(evidence))
                self.assertNotIn('recovered', evidence)  # Final or partial recovery can NEVER override root timeout.
                if denied_group:
                    self.assertEqual(evidence['process_cleanup']['process_absent'], not denied_child)
                    self.assertFalse(evidence['process_cleanup']['passed'])
                    self.assertFalse(evidence['process_cleanup']['group_absent'])
                    self.assertTrue(evidence['process_cleanup']['issues'])
                    self.assertNotIn('private', json.dumps(evidence))
                    process.terminate.assert_called_once()
                    if denied_child:
                        process.kill.assert_called_once()
                    else:
                        process.kill.assert_not_called()
                else:
                    self.assertGreaterEqual(kill.call_count, 2)
            elif denied_group:
                with self.assertRaises(MODULE['DenseProbeFailure']) as raised:
                    MODULE['run_dense_gateway_probe'](['owned-kubectl'], {}, 30)
                cleanup = raised.exception.evidence['process_cleanup']
                self.assertTrue(cleanup['process_absent'])
                self.assertFalse(cleanup['group_absent'])
                self.assertFalse(cleanup['passed'])
                self.assertNotIn('recovered', raised.exception.evidence)
            else:
                accepted = MODULE['run_dense_gateway_probe'](['owned-kubectl'], {}, 30)
                self.assertTrue(accepted['recovered'])
                self.assertEqual(accepted['rpc_diagnostics']['rpc_startup_seconds'], 3)
                self.assertEqual(accepted['rpc_diagnostics']['root_rpc_seconds'], 5)
            self.assertTrue(popen.call_args.kwargs['start_new_session'])
        self.assertAlmostEqual(grants[0], 26.9)

    def test_group_permission_denied_preserves_timeout_samples_and_falls_back_to_owned_child(self):
        self.rpc(timeout=True, denied_group=True)

    def test_group_and_child_permission_denied_preserve_primary_timeout_with_failed_cleanup(self):
        self.rpc(timeout=True, denied_group=True, denied_child=True)

    def test_rpc_startup_consumes_original_root_budget_without_utc_clock_alignment(self):
        self.rpc()

    def test_rpc_timeout_retains_whitelisted_partial_samples_and_failure(self):
        self.rpc(timeout=True)

    def test_late_final_recovery_result_cannot_override_original_root_deadline(self):
        self.rpc(overrun=True)


    def test_exited_leader_surviving_closed_pipe_descendant_is_cleaned_without_foreign_session(self):
        foreign = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(20)'],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        source = "import subprocess,sys;child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(20)']," \
                 "stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);print(child.pid,flush=True)"
        parent = subprocess.Popen([sys.executable, '-u', '-c', source], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            output, _ = parent.communicate(timeout=3)
            self.assertEqual(parent.poll(), 0)
            child_pid = int(output.strip())
            os.kill(child_pid, 0)  # The descendant is real, alive, and has closed the leader's pipes.
            with patch.object(parent, 'terminate') as direct_term, patch.object(parent, 'kill') as direct_kill:
                cleanup = MODULE['cleanup_dense_probe'](parent)
                direct_term.assert_not_called()
                direct_kill.assert_not_called()
            self.assertTrue(cleanup['process_absent'])
            self.assertTrue(cleanup['group_absent'])
            self.assertTrue(cleanup['passed'])
            self.assertIsNone(foreign.poll())
            self.assertNotEqual(os.getpgid(foreign.pid), parent.pid)
        finally:
            try:
                os.killpg(parent.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            try:
                os.kill(child_pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            parent.communicate(timeout=3)
            foreign.terminate()
            foreign.wait(timeout=3)

    def test_real_owned_group_timeout_is_cleaned_and_caller_retains_partial_evidence(self):
        source = "import json,time;print(json.dumps({'event':'ready'}),flush=True);" \
                 "print(json.dumps({'event':'sample','status':503,'body_valid':False}),flush=True);time.sleep(10)"
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            MODULE['run_dense_gateway_probe']([sys.executable, '-u', '-c', source], {}, MODULE['time'].monotonic() + .5)
        error = raised.exception
        self.assertEqual(error.dense_evidence['sample_count'], 1)
        self.assertTrue(error.dense_evidence['process_cleanup']['passed'])
        self.assertTrue(error.dense_evidence['process_cleanup']['process_absent'])
        self.assertTrue(error.dense_evidence['process_cleanup']['group_absent'])
        self.assertFalse(error.dense_evidence['process_cleanup']['issues'])
        environment = {'time': Clock(), 'fixture': {'gateway_service': 'gateway', 'gateway_namespace': 'eg',
                       'load_path': '/api/load', 'gateway_host': 'fixture', 'backend_body': 'backend'},
                       'ns': 'fixture', 'probe': 'probe', 'kube_base': ['kubectl'], 'env': {},
                       'DenseProbeFailure': MODULE['DenseProbeFailure'], 'subprocess': subprocess,
                       'DENSE_RECOVERY_SOURCE': MODULE['DENSE_RECOVERY_SOURCE']}
        def probe(*args):
            raise error
        environment['run_dense_gateway_probe'] = probe
        function = nested_function('dense_gateway_recovery')
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SCRIPT), 'exec'), environment)
        evidence = {'timestamps': {}}
        with self.assertRaises(subprocess.TimeoutExpired) as retained:
            environment['dense_gateway_recovery'](0, lambda: True, [], evidence)
        self.assertIs(retained.exception, error)
        self.assertEqual(evidence['dense_gateway_batches'], [error.dense_evidence])

    def test_successful_result_cannot_pass_with_unverified_process_group_cleanup(self):
        self.rpc(denied_group=True)

    def test_actual_payload_streams_through_real_owned_local_child(self):
        # Real pipes/select/communicate; the child HTTP opener and credential reader are offline fakes.
        setup = """import pathlib,sys,urllib.request
pathlib.Path.read_text=lambda self:'public-test-key'
class Response:
    status=200
    headers={}
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def read(self):return b'backend\\n'
class Opener:
    def open(self,*args,**kwargs):return Response()
urllib.request.build_opener=lambda *args:Opener()
sys.argv=['dense','http://offline','/api/load','fixture','backend','handshake']
"""
        command = [sys.executable, '-u', '-c', setup + MODULE['DENSE_RECOVERY_SOURCE']]
        result = MODULE['run_dense_gateway_probe'](command, {}, MODULE['time'].monotonic() + 8)
        self.assertTrue(result['recovered'])
        self.assertGreaterEqual(result['consecutive_successes'], 10)
        self.assertGreaterEqual(result['rpc_diagnostics']['sample_count'], 10)
        self.assertNotIn('public-test-key', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
