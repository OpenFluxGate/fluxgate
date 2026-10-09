#!/usr/bin/env python3
"""Serial fault proof for the isolated fixture. Root must schedule this run exclusively."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time
import threading
import uuid



def main():
    if not __debug__:
        raise RuntimeError('Assertions disabled; run Python without -O')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--phase', choices=['all', 'baseline', 'redis', 'mongo', 'pod-restarts', 'node', 'no-quorum'], default='all')
    parser.add_argument('--publisher-hook', type=Path, default=Path(__file__).with_name('publish-hook.py'))
    parser.add_argument('--proof', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    fixture_file = args.fixture / 'fixture.json' if args.fixture.is_dir() else args.fixture
    fixture = json.loads(fixture_file.read_text())
    assert fixture['context'] == 'kind-fluxgate-resilience' and fixture['namespace'] == 'fluxgate-resilience'
    ns = fixture['namespace']
    expected_nodes = {ns + suffix for suffix in ['-control-plane', '-worker', '-worker2']}
    assert set(fixture['node_container_names']) == expected_nodes
    env = dict(os.environ, KUBECONFIG=fixture['kubeconfig'])
    kube_base = ['kubectl', '--context', fixture['context']]
    proof = args.proof or Path(tempfile.mkdtemp(prefix='fluxgate-ha-proof-'))
    proof.mkdir(mode=0o700, parents=True, exist_ok=True)
    checks = []
    paused = {}
    stopped_nodes = set()
    probe = 'ha-probe-' + uuid.uuid4().hex[:8]
    probe_secret = probe + '-key'
    probe_policy = probe + '-egress'
    sampler_stop = None
    sampler_thread = None
    route_restore = None
    route_header = None
    restore_deadline = None
    fault_deadline = None
    failure_phase = 'preflight'
    phase_evidence = {}
    outage_samples = []
    sentinels = []

    def kube(command, stdin=None, timeout=45, binary=False):
        deadlines = [d for d in (restore_deadline, fault_deadline) if d is not None]
        if deadlines:
            remaining = min(deadlines) - time.monotonic()
            if remaining <= 0:
                raise RuntimeError('Command exceeded its shared phase deadline')
            timeout = min(timeout, remaining)
        result = subprocess.run(kube_base + command, input=stdin, env=env, capture_output=True,
                                text=not binary, timeout=timeout)
        if result.returncode:
            # Never echo arbitrary driver/tool output that might include credentials.
            raise RuntimeError(f'kubectl execution failed ({result.returncode}); command category {command[0]}')
        return result.stdout

    def obj(kind, name=None, namespace=ns, selector=None):
        command = ['-n', namespace, 'get', kind] + ([name] if name else [])
        if selector:
            command += ['-l', selector]
        return json.loads(kube(command + ['-o', 'json']))

    def apply(resource):
        kube(['apply', '-f', '-'], stdin=json.dumps(resource))

    def record(label, **data):
        checks.append({'check': label, **data})
        (proof / 'progress.json').write_text(json.dumps({'checks': checks}, indent=2) + '\n')
        print(label, flush=True)

    def wait(label, action, timeout=30):
        started = time.monotonic()
        while True:
            try:
                result = action()
                elapsed = time.monotonic() - started
                if elapsed > timeout:
                    raise RuntimeError(f'{label} completed after its deadline')
                return result, round(elapsed, 3)
            except (RuntimeError, AssertionError, ValueError, subprocess.TimeoutExpired):
                if time.monotonic() - started >= timeout:
                    raise RuntimeError(f'{label} exceeded the declared {timeout}s recovery bound') from None
                time.sleep(0.25)

    def diagnostic_pods(candidates):
        eligible = [pod for pod in candidates if pod not in paused]
        if stopped_nodes:
            eligible = [pod for pod in eligible if obj('pod', pod)['spec']['nodeName'] not in stopped_nodes]
        if not eligible:
            raise RuntimeError('No surviving diagnostic store Pod is available')
        return eligible

    def redis(pod, arguments):
        script = 'export REDISCLI_AUTH="$(cat /credentials/redis-password)"; exec redis-cli -c "$@"'
        return kube(['-n', ns, 'exec', pod, '--', 'sh', '-c', script, 'probe'] + arguments, timeout=12).strip()

    def mongo(source):
        seed = ','.join(f'mongo-{i}.mongo.{ns}.svc.cluster.local:27017' for i in range(3))
        preamble = f'''const fs=require('fs');const pw=fs.readFileSync('/admin/mongo-admin-password','utf8').trim();
const conn=new Mongo('mongodb://admin:'+encodeURIComponent(pw)+'@{seed}/admin?replicaSet=rs0&authSource=admin&connectTimeoutMS=2000&serverSelectionTimeoutMS=2000&socketTimeoutMS=2000&readConcernLevel=majority');const admin=conn.getDB('admin');
'''
        pod = diagnostic_pods(fixture['mongo_pods'])[0]
        return json.loads(kube(['-n', ns, 'exec', '-i', pod, '--', 'mongosh', '--quiet', '--nodb', '--file', '/dev/stdin'],
                              stdin=preamble + source + '\n', timeout=12))

    def policy():
        return mongo("const d=conn.getDB('fluxgate');const p=d.rate_limit_rules_policies.findOne({_id:'resilience-limits'});const s=d.rate_limit_rules_revisions.findOne({_id:p.snapshotId});if(!s||String(p.revision)!==String(s.revision)||p.counterEpoch!==s.counterEpoch)quit(2);print(JSON.stringify({revision:String(p.revision),counterEpoch:p.counterEpoch,snapshotId:p.snapshotId,checksum:s.checksum,operationId:s.operationId,rules:s.rules,accessControl:s.accessControl}));")

    def mongo_primary():
        status = mongo('print(JSON.stringify(admin.runCommand({replSetGetStatus:1})));')
        assert status.get('ok') == 1
        return next(m['name'].split('.')[0] for m in status['members'] if m['state'] == 1)

    def request(path, allowed, method='GET', expected_header=None):
        source = '''import json,pathlib,urllib.request,urllib.error,sys
req=urllib.request.Request(sys.argv[1]+sys.argv[2],method=sys.argv[3],headers={'Host':sys.argv[4],'X-API-Key':pathlib.Path('/key/load-api-key' if sys.argv[2]==sys.argv[5] else '/key/api-key').read_text().strip()})
try: response=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=7)
except urllib.error.HTTPError as error: response=error
with response: print(json.dumps({'status':response.status,'body':response.read().decode(),'headers':dict((k.lower(),v) for k,v in response.headers.items())}))
'''
        host = f'http://{fixture["gateway_service"]}.{fixture["gateway_namespace"]}.svc.cluster.local'
        response = json.loads(kube(['-n', ns, 'exec', probe, '--', 'python', '-c', source,
                                   host, path, method, fixture['gateway_host'], fixture['load_path']], timeout=12))
        assert response['status'] in allowed, f'Unexpected real Envoy HTTP status {response["status"]}'
        assert (fixture['backend_body'] in response['body']) == (response['status'] == 200), 'Denied/error request reached backend'
        if expected_header is not None:
            assert response['headers'].get(expected_header[0]) == expected_header[1]
        return response['status']

    def start_sampler(started):
        nonlocal sampler_stop, sampler_thread
        sampler_stop = threading.Event()
        observations = []
        def sample():
            while not sampler_stop.is_set():
                item = {'elapsed_seconds': round(time.monotonic() - started, 3)}
                try:
                    item['status'] = request(fixture['load_path'], {200, 503})
                except AssertionError as error:
                    item['unexpected'] = str(error)
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    item['transport_error'] = type(error).__name__
                observations.append(item)
                sampler_stop.wait(0.25)
        sampler_thread = threading.Thread(target=sample, daemon=True)
        sampler_thread.start()
        return observations

    def stop_sampler():
        if sampler_stop is not None:
            sampler_stop.set()
            sampler_thread.join(timeout=15)
            assert not sampler_thread.is_alive(), 'Continuous probe did not terminate'

    def task_state(pod):
        entry = paused[pod]
        result = subprocess.check_output(entry['ctr'] + ['ls'], text=True, timeout=10)
        return next(line.split()[-1] for line in result.splitlines() if line.split() and line.split()[0] == entry['container'])

    def signal(pod, action):
        if action == 'STOP':
            assert pod in fixture['mongo_pods'] + fixture['redis_pods']
            resource = obj('pod', pod)
            node = resource['spec']['nodeName']
            assert node in expected_nodes
            inspection = json.loads(subprocess.check_output(['docker', 'inspect', node], text=True, timeout=10))[0]
            assert inspection['Config']['Labels']['io.x-k8s.kind.cluster'] == ns
            container = next(c['containerID'] for c in resource['status']['containerStatuses'] if c['name'] in ('mongo', 'redis'))
            assert re.fullmatch(r'containerd://[0-9a-f]{64}', container)
            entry = {'node': node, 'container': container.split('://', 1)[1],
                     'ctr': ['docker', 'exec', node, 'ctr', '-n', 'k8s.io', 'tasks']}
            existing = subprocess.check_output(entry['ctr'] + ['ls'], text=True, timeout=10)
            state = next(line.split()[-1] for line in existing.splitlines() if line.split() and line.split()[0] == entry['container'])
            assert state == 'RUNNING', 'Cannot take ownership of an already paused task'
            paused[pod] = entry
            subprocess.run(paused[pod]['ctr'] + ['pause', paused[pod]['container']], check=True, timeout=10, stdout=subprocess.DEVNULL)
            assert task_state(pod) == 'PAUSED', 'Container task was not actually frozen'
        else:
            if task_state(pod) == 'PAUSED':
                subprocess.run(paused[pod]['ctr'] + ['resume', paused[pod]['container']], check=True, timeout=10, stdout=subprocess.DEVNULL)
            assert task_state(pod) == 'RUNNING'
            paused.pop(pod)

    def sustained_traffic(started, seconds, while_down=None, expected_header=None):
        streak = 0
        observations = []
        while time.monotonic() - started < seconds:
            if while_down:
                assert while_down(), 'Fault target recovered before the sustained traffic proof'
            try:
                status = request(fixture['load_path'], {200}, expected_header=expected_header)
                streak += 1
                observations.append({'elapsed_seconds': round(time.monotonic() - started, 3), 'status': status})
            except (RuntimeError, AssertionError, subprocess.TimeoutExpired):
                streak = 0
            if time.monotonic() - started > seconds:
                break
            if streak == 3:
                if while_down:
                    assert while_down()
                return round(time.monotonic() - started, 3), observations[-3:]
            time.sleep(1)
        raise RuntimeError(f'Three consecutive real Gateway 200 responses exceeded {seconds}s')

    def topology():
        pod = diagnostic_pods(fixture['redis_pods'])[0]
        lines = redis(pod, ['CLUSTER', 'NODES']).splitlines()
        result = {}
        for line in lines:
            fields = line.split()
            host = fields[1].split(',')[-1] if ',' in fields[1] else fields[1].split(':')[0]
            result[fields[0]] = {'pod': host.split('.')[0], 'flags': fields[2].split(','),
                                 'primary': fields[3], 'slots': fields[8:]}
        return result

    def owner(key):
        pod = diagnostic_pods(fixture['redis_pods'])[0]
        slot = int(redis(pod, ['CLUSTER', 'KEYSLOT', key]))
        nodes = topology()
        for identity, node in nodes.items():
            if 'master' not in node['flags']:
                continue
            for span in node['slots']:
                if span.startswith('['):
                    continue
                start, end = (map(int, span.split('-')) if '-' in span else (int(span), int(span)))
                if start <= slot <= end:
                    return identity, node, nodes
        raise AssertionError('No Redis primary owns the actual bucket slot')

    def bucket(key=None):
        if key is None:
            matches = []
            pattern = 'fluxgate:bucket:{resilience-limits:quota-rule:key:' + fixture.get('ha_api_key_id', 'resilience-ha-key') + '}:daily:epoch:*'
            for pod in diagnostic_pods(fixture['redis_pods']):
                matches += redis(pod, ['--scan', '--pattern', pattern]).splitlines()
            candidates = sorted(set(k for k in matches if not k.endswith((':meta', ':fence'))))
            key = next(k for k in candidates if redis(diagnostic_pods(fixture['redis_pods'])[0], ['HGET', k, 'tokens']) not in ('', '(nil)'))
        values = redis(diagnostic_pods(fixture['redis_pods'])[0], ['--raw', 'HGETALL', key]).splitlines()
        assert len(values) % 2 == 0 and values
        return key, dict(zip(values[::2], values[1::2]))

    def replace_pod(pod, namespace=ns):
        before = obj('pod', pod, namespace)
        selector = ','.join(f'{k}={v}' for k,v in before['metadata']['labels'].items() if k in ('app', 'redis-instance', 'gateway.envoyproxy.io/owning-gateway-namespace', 'gateway.envoyproxy.io/owning-gateway-name'))
        original_uids = {p['metadata']['uid'] for p in obj('pods', namespace=namespace, selector=selector)['items']}
        owner_ref = before['metadata']['ownerReferences'][0]
        controller = obj(owner_ref['kind'], owner_ref['name'], namespace)
        desired = controller['spec']['replicas']
        claims = {v['persistentVolumeClaim']['claimName'] for v in before['spec']['volumes'] if 'persistentVolumeClaim' in v}
        pvc_before = {v: obj('pvc', v)['metadata']['uid'] for v in claims}
        sentinel_hash = None
        sentinel_path = None
        if claims:
            mount = '/data/db' if pod.startswith('mongo-') else '/data'
            sentinel_path = mount + '/.ha-proof-' + uuid.uuid4().hex
            content = uuid.uuid4().hex
            sentinel_hash = hashlib.sha256(content.encode()).hexdigest()
            kube(['-n', namespace, 'exec', pod, '--', 'sh', '-c', 'umask 077; cat > "$1"', 'probe', sentinel_path], stdin=content)
            sentinels.append((pod, namespace, sentinel_path))
        started = time.monotonic()
        kube(['-n', namespace, 'delete', 'pod', pod, '--wait=false'])
        def replaced():
            pods = obj('pods', namespace=namespace, selector=selector)['items']
            assert before['metadata']['uid'] not in {p['metadata']['uid'] for p in pods}
            active = [p for p in pods if not p['metadata'].get('deletionTimestamp')]
            assert len(active) == desired and all(p['status'].get('containerStatuses') and all(c.get('ready') for c in p['status']['containerStatuses']) for p in active)
            fresh = [p for p in active if p['metadata']['uid'] not in original_uids and any(o['uid'] == owner_ref['uid'] for o in p['metadata']['ownerReferences'])]
            assert fresh
            result = fresh[0]
            if owner_ref['kind'] == 'StatefulSet':
                assert result['metadata']['name'] == pod
            mounted = {v['persistentVolumeClaim']['claimName'] for v in result['spec']['volumes'] if 'persistentVolumeClaim' in v}
            assert mounted == claims
            return result
        after, _ = wait('New controller-owned Pod with all desired replicas Ready', replaced, 90)
        assert all(obj('pvc', v)['metadata']['uid'] == uid for v, uid in pvc_before.items())
        if sentinel_path:
            persisted = kube(['-n', namespace, 'exec', after['metadata']['name'], '--', 'cat', sentinel_path])
            assert hashlib.sha256(persisted.encode()).hexdigest() == sentinel_hash
        seconds, samples = sustained_traffic(started, 90)
        record('pod-replacement', pod=pod, namespace=namespace, original_uids=sorted(original_uids), previous_uid=before['metadata']['uid'],
               replacement_uid=after['metadata']['uid'], controller_uid=owner_ref['uid'], desired_ready_replicas=desired,
               pvc_uids=pvc_before, mounted_sentinel_path=sentinel_path, sentinel_sha256=sentinel_hash, actual_content_preserved=sentinel_path is not None, recovery_seconds=seconds, sustained_gateway=samples)

    def publisher(mode):
        output = proof / ('publication-' + uuid.uuid4().hex + '.json')
        command = ['python3', str(args.publisher_hook), '--fixture', str(fixture_file),
                   '--expect', 'success' if mode == 'retry' else 'rejected', '--deadline', '30', '--output', str(output)]
        result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=40)
        assert result.returncode == 0, 'Actual repository publication hook did not meet its expectation'
        return json.loads(output.read_text())

    def prepare_publisher():
        prepared = subprocess.run(['python3', str(args.publisher_hook), '--fixture', str(fixture_file), '--prepare'], env=env, capture_output=True, text=True, timeout=120)
        assert prepared.returncode == 0, 'Actual repository publisher preflight preparation failed'

    def authz_processes():
        return sorted((p['metadata']['uid'], c['containerID'], c.get('restartCount', 0))
                      for p in obj('pods', selector='app=fluxgate-authz')['items']
                      for c in p['status'].get('containerStatuses', []))

    def full_restore(started):
        nonlocal restore_deadline
        restore_deadline = started + 180
        def healthy():
            nodes = obj('nodes')['items']
            assert {n['metadata']['name'] for n in nodes} == expected_nodes
            assert all(any(c['type'] == 'Ready' and c['status'] == 'True' for c in n['status']['conditions']) for n in nodes)
            counts = {}
            for namespace, selector, desired in [(ns, 'app=mongo', 3), (ns, 'app=redis', 9),
                    (ns, 'app=fluxgate-authz', 2), (ns, 'app=echo', 2),
                    (fixture['gateway_namespace'], 'control-plane=envoy-gateway', 2),
                    (fixture['gateway_namespace'], 'gateway.envoyproxy.io/owning-gateway-namespace=' + ns, 2)]:
                pods = obj('pods', namespace=namespace, selector=selector)['items']
                assert len(pods) == desired
                assert all(not p['metadata'].get('deletionTimestamp') and any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status'].get('conditions', [])) for p in pods)
                counts[selector] = desired
            members = mongo('print(JSON.stringify(admin.runCommand({replSetGetStatus:1})));')['members']
            assert len(members) == 3 and all(m['health'] == 1 for m in members)
            assert sum(m['state'] == 1 for m in members) == 1 and sum(m['state'] == 2 for m in members) == 2
            redis_nodes = topology()
            primaries = {identity: n for identity, n in redis_nodes.items() if 'master' in n['flags']}
            replicas = {identity: n for identity, n in redis_nodes.items() if 'slave' in n['flags']}
            assert len(redis_nodes) == 9 and len(primaries) == 3 and len(replicas) == 6
            assert all(not set(n['flags']).intersection({'fail', 'fail?', 'handshake', 'noaddr'}) for n in redis_nodes.values())
            for pod in fixture['redis_pods']:
                info = redis(pod, ['CLUSTER', 'INFO'])
                assert 'cluster_state:ok' in info and 'cluster_slots_ok:16384' in info and 'cluster_known_nodes:9' in info
            for identity, primary in primaries.items():
                assert sum(n['primary'] == identity for n in replicas.values()) == 2
                replication = redis(primary['pod'], ['INFO', 'replication'])
                assert 'connected_slaves:2' in replication
                assert len([line for line in replication.splitlines() if line.startswith(('slave0:', 'slave1:')) and 'state=online' in line]) == 2
            for replica in replicas.values():
                assert 'master_link_status:up' in redis(replica['pod'], ['INFO', 'replication'])
            if route_restore is not None:
                assert obj('httproute', 'resilience-api')['spec'] == route_restore
            return {'ready_counts': counts, 'mongo_healthy_voters': 3, 'redis_primaries': 3, 'redis_linked_replicas': 6}
        try:
            remaining = restore_deadline - time.monotonic()
            state, _ = wait('Complete fixture restoration', healthy, max(0, remaining))
            elapsed, responses = sustained_traffic(started, 180, expected_header=(route_header, None) if route_header else None)
            state.update({'restore_seconds': elapsed, 'sustained_gateway': responses, 'original_route_restored': route_restore is not None})
            record('complete-fixture-restoration', **state)
            return state
        finally:
            restore_deadline = None

    namespace_guard = obj('namespace', ns)
    assert namespace_guard['metadata'].get('labels', {}).get('fluxgate.io/environment') == 'local-ephemeral'

    try:
        # Probe has a single allowlisted egress policy and reads its key from a Secret file.
        import base64
        apply({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': probe_secret, 'namespace': ns},
               'data': {'api-key': base64.b64encode(Path(fixture['ha_api_key_file']).read_bytes()).decode(),
                        'load-api-key': base64.b64encode(Path(fixture['api_key_file']).read_bytes()).decode()}})
        apply({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': probe_policy, 'namespace': ns},
            'spec': {'podSelector': {'matchLabels': {'app': probe}}, 'policyTypes': ['Egress'], 'egress': [
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': fixture['gateway_namespace']}},
                         'podSelector': {'matchLabels': {'gateway.envoyproxy.io/owning-gateway-namespace': ns}}}], 'ports': [{'port': 80}, {'port': 10080}]},
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}}, 'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
                 'ports': [{'port': 53, 'protocol': 'UDP'}, {'port': 53, 'protocol': 'TCP'}]}]}})
        apply({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': probe, 'namespace': ns, 'labels': {'app': probe}},
            'spec': {'automountServiceAccountToken': False, 'nodeSelector': {'kubernetes.io/hostname': ns + '-control-plane'},
                     'tolerations': [{'key': 'node-role.kubernetes.io/control-plane', 'operator': 'Exists', 'effect': 'NoSchedule'}],
                     'containers': [{'name': 'probe', 'image': 'python:3.12-alpine', 'command': ['python', '-c', 'import time;time.sleep(3600)'],
                         'resources': {'requests': {'cpu': '10m', 'memory': '32Mi'}, 'limits': {'cpu': '100m', 'memory': '128Mi'}},
                         'volumeMounts': [{'name': 'key', 'mountPath': '/key', 'readOnly': True}]}],
                     'volumes': [{'name': 'key', 'secret': {'secretName': probe_secret, 'defaultMode': 256}}]}})
        kube(['-n', ns, 'wait', '--for=condition=Ready', 'pod/' + probe, '--timeout=180s'], timeout=190)
        baseline = policy()
        record('baseline', policy=baseline, redis=topology(), placement=json.loads((fixture_file.parent / 'placement.json').read_text()))
        request(fixture['load_path'], {200})
        if args.phase in ('all', 'redis'):
            failure_phase = 'redis-promotion'
            phase_evidence = {}
            authz_before = authz_processes()
            for _ in range(4):
                request(fixture['quota_path'], {200})
            key, before = bucket()
            phase_evidence.update({'bucket_key': key, 'raw_before': before, 'authz_processes_before': authz_before})
            assert before['tokens'] == '1', 'Four consumed permits must leave exactly one'
            old_id, old_primary, nodes = owner(key)
            replicas = [n for n in nodes.values() if n['primary'] == old_id]
            assert len(replicas) == 2
            # Script cache is volatile; clear replicas only to prove actual NOSCRIPT recovery.
            sha = hashlib.sha1((Path(__file__).resolve().parents[2] / 'fluxgate-redis-ratelimiter/src/main/resources/lua/token_bucket_consume.lua').read_bytes()).hexdigest()
            for replica in replicas:
                redis(replica['pod'], ['SCRIPT', 'FLUSH'])
                assert redis(replica['pod'], ['SCRIPT', 'EXISTS', sha]) == '0'
            phase_evidence.update({'old_primary_id': old_id, 'old_primary': old_primary})
            fault_started = time.monotonic()
            fault_deadline = fault_started + 30
            phase_evidence['fault_started_monotonic'] = fault_started
            signal(old_primary['pod'], 'STOP')
            load_key = 'fluxgate:bucket:{resilience-limits:load-rule:key:' + fixture.get('api_key_id', 'resilience-key') + '}:hourly-fw'
            assert owner(load_key)[0] == old_id, 'Continuous high-capacity load must hit the failed quota primary shard'
            outage_samples = start_sampler(fault_started)
            def promoted():
                identity, primary, _ = owner(key)
                assert identity != old_id and 'fail' not in primary['flags']
                return identity, primary
            (new_id, new_primary), seconds = wait('Redis actual primary promotion', promoted, max(0, fault_deadline - time.monotonic()))
            phase_evidence.update({'new_primary_id': new_id, 'new_primary': new_primary, 'promotion_seconds': seconds})
            _, after = bucket(key)
            phase_evidence['raw_after_promotion'] = after
            assert after['tokens'] == before['tokens']
            assert policy() == baseline
            def unchanged_paused_clients():
                assert authz_processes() == authz_before, 'Redis recovery changed the application client processes'
                return task_state(old_primary['pod']) == 'PAUSED'
            recovery_seconds, sustained = sustained_traffic(fault_started, 30, unchanged_paused_clients)
            phase_evidence.update({'recovery_seconds': recovery_seconds, 'sustained_gateway': sustained})
            assert time.monotonic() < fault_deadline
            request(fixture['quota_path'], {200})
            phase_evidence['last_quota_status'] = 200
            assert time.monotonic() < fault_deadline
            request(fixture['quota_path'], {429})
            phase_evidence['exhausted_quota_status'] = 429
            _, exhausted = bucket(key)
            phase_evidence['raw_exhausted'] = exhausted
            assert exhausted['tokens'] == '0'
            assert redis(new_primary['pod'], ['SCRIPT', 'EXISTS', sha]) == '1'
            assert authz_processes() == authz_before, 'Redis failover must recover within the same authz client processes'
            assert unchanged_paused_clients()
            assert time.monotonic() <= fault_deadline, 'Final quota checks exceeded global 30s fault budget'
            phase_evidence['final_quota_seconds'] = round(time.monotonic() - fault_started, 3)
            stop_sampler()
            assert not any('unexpected' in sample for sample in outage_samples)
            record('redis-promotion', old_primary_id=old_id, new_primary_id=new_id, target_still_paused=old_primary['pod'] in paused,
                   final_quota_seconds=phase_evidence['final_quota_seconds'], recovery_seconds=recovery_seconds, identity_promotion_seconds=seconds, sustained_gateway=sustained, task_state=task_state(old_primary['pod']), continuous_outage_samples=outage_samples, load_key_owned_failed_shard=True, bucket_key=key, raw_before=before, raw_after_promotion=after, raw_exhausted=exhausted,
                   cache_absent_before_promotion=True, cache_loaded_after_real_request=True, authz_processes_unchanged=authz_before, rpo='Observed counter preserved; asynchronous replication is not zero-loss consensus')
            fault_deadline = None
            signal(old_primary['pod'], 'CONT')
            wait('Redis old member recovery', lambda: request(fixture['load_path'], {200}), 30)
        if args.phase in ('all', 'mongo'):
            failure_phase = 'mongo-election-publication'
            phase_evidence = {}
            outage_samples = []
            prepare_publisher()
            old_primary = mongo_primary()
            fault_started = time.monotonic()
            signal(old_primary, 'STOP')
            outage_samples = start_sampler(fault_started)
            # Repository publish begins during election and retries one stable operation ID.
            published = publisher('retry')
            assert published.get('passed') is True and published.get('published') is True
            assert published['epochPreserved'] and published['majorityReadVerified'] and published['idempotentReplay']
            new_primary = mongo_primary()
            assert new_primary != old_primary
            current = policy()
            assert current['counterEpoch'] == baseline['counterEpoch']
            expected_rules = __import__('copy').deepcopy(baseline['rules'])
            expected_rules[0]['name'] = 'Resilience metadata proof ' + published['operationId']
            assert current['rules'] == expected_rules and current['accessControl'] == baseline['accessControl']
            assert int(current['revision']) == int(baseline['revision']) + 1 and current['checksum'] == published['checksum'] and current['operationId'] == published['operationId']
            recovery_seconds, sustained = sustained_traffic(fault_started, 30, lambda: task_state(old_primary) == 'PAUSED')
            if args.phase == 'all':
                request(fixture['quota_path'], {429})
            stop_sampler()
            assert not any('unexpected' in sample for sample in outage_samples)
            record('mongo-election-publication', old_primary=old_primary, new_primary=new_primary,
                   target_still_paused=task_state(old_primary) == 'PAUSED', recovery_seconds=recovery_seconds, sustained_gateway=sustained, continuous_outage_samples=outage_samples, publication=published, policy=current)
            baseline = current
            signal(old_primary, 'CONT')
        if args.phase in ('all', 'pod-restarts'):
            failure_phase = 'pod-replacement'
            phase_evidence = {}
            outage_samples = []
            replacement_targets = [('mongo-2', ns), ('redis-8-0', ns),
                (obj('pods', selector='app=fluxgate-authz')['items'][0]['metadata']['name'], ns),
                (obj('pods', selector='app=echo')['items'][0]['metadata']['name'], ns),
                (obj('pods', namespace=fixture['gateway_namespace'], selector='gateway.envoyproxy.io/owning-gateway-namespace=' + ns)['items'][0]['metadata']['name'], fixture['gateway_namespace'])]
            for pod, namespace in replacement_targets:
                replace_pod(pod, namespace)
                wait('Real Gateway recovery after Pod replacement', lambda: request(fixture['load_path'], {200}), 30)
                assert policy() == baseline
                if args.phase == 'all':
                    request(fixture['quota_path'], {429})
        if args.phase in ('all', 'node'):
            failure_phase = 'worker-stop'
            phase_evidence = {}
            outage_samples = []
            controller_ns = fixture['gateway_namespace']
            lease_name = '5b9825d2.gateway.envoyproxy.io'
            old_holder = obj('lease', lease_name, namespace=controller_ns)['spec']['holderIdentity']
            leader_pod = old_holder.split('_', 1)[0]
            node = obj('pod', leader_pod, namespace=controller_ns)['spec']['nodeName']
            assert node in expected_nodes and node != ns + '-control-plane'
            controllers = obj('pods', namespace=controller_ns, selector='control-plane=envoy-gateway')['items']
            assert len(controllers) == 2 and len({p['spec']['nodeName'] for p in controllers}) == 2
            inspection = json.loads(subprocess.check_output(['docker', 'inspect', node], text=True))[0]
            assert inspection['Config']['Labels']['io.x-k8s.kind.cluster'] == ns
            route_restore = obj('httproute', 'resilience-api')['spec']
            header = ('x-ha-controller-proof', str(uuid.uuid4()))
            route_header = header[0]
            request(fixture['load_path'], {200}, expected_header=(header[0], None))
            # A unique new header cannot be supplied by previously cached xDS.
            stopped_nodes.add(node)
            fault_started = time.monotonic()
            subprocess.run(['docker', 'stop', '-t', '1', node], check=True, stdout=subprocess.DEVNULL)
            def node_stopped():
                return json.loads(subprocess.check_output(['docker', 'inspect', node], text=True, timeout=10))[0]['State']['Running'] is False
            assert node_stopped()
            outage_samples = start_sampler(fault_started)
            def new_controller():
                assert node_stopped()
                holder = obj('lease', lease_name, namespace=controller_ns)['spec']['holderIdentity']
                assert holder != old_holder
                resource = obj('pod', holder.split('_', 1)[0], namespace=controller_ns)
                assert resource['spec']['nodeName'] != node
                assert any(c['type'] == 'Ready' and c['status'] == 'True' for c in resource['status']['conditions'])
                return holder
            new_holder, lease_seconds = wait('Surviving controller elected', new_controller, 30)
            changed = __import__('copy').deepcopy(route_restore)
            for rule in changed['rules']:
                rule.setdefault('filters', []).append({'type': 'ResponseHeaderModifier', 'responseHeaderModifier': {'add': [{'name': header[0], 'value': header[1]}]}})
            kube(['-n', ns, 'patch', 'httproute', 'resilience-api', '--type=merge', '--patch-file=/dev/stdin'], stdin=json.dumps({'spec': changed}))
            seconds, sustained = sustained_traffic(fault_started, 90, node_stopped, header)
            stop_sampler()
            assert not any('unexpected' in sample for sample in outage_samples)
            assert node_stopped() and policy() == baseline
            if args.phase == 'all':
                request(fixture['quota_path'], {429})
            record('worker-stop', node=node, docker_running=False, old_lease_holder=old_holder, new_lease_holder=new_holder,
                   lease_recovery_seconds=lease_seconds, reconciled_header=header, live_xds_update=True, continuous_outage_samples=outage_samples,
                   recovery_seconds=seconds, sustained_gateway=sustained, policy=baseline)
            kube(['-n', ns, 'patch', 'httproute', 'resilience-api', '--type=merge', '--patch-file=/dev/stdin'], stdin=json.dumps({'spec': route_restore}))
            restore_started = time.monotonic()
            subprocess.run(['docker', 'start', node], check=True, stdout=subprocess.DEVNULL)
            assert json.loads(subprocess.check_output(['docker', 'inspect', node], text=True))[0]['State']['Running']
            stopped_nodes.discard(node)
            full_restore(restore_started)
            route_restore = None
            route_header = None
        if args.phase in ('all', 'no-quorum'):
            failure_phase = 'no-quorum'
            phase_evidence = {}
            outage_samples = []
            prepare_publisher()
            primary = mongo_primary()
            secondaries = [p for p in fixture['mongo_pods'] if p != primary]
            for pod in secondaries:
                signal(pod, 'STOP')
            started = time.monotonic()
            blocked = publisher('once')
            elapsed = time.monotonic() - started
            assert blocked.get('passed') is True and blocked.get('published') is None and elapsed < 35
            assert blocked['commitStatus'] == 'unknown' and blocked['requiresRecoveryResolution']
            record('mongo-no-quorum-publication', publication=blocked, bounded_seconds=round(elapsed, 3),
                   guarantee='Active publication blocked; previously committed reads need not be unavailable')
            for pod in secondaries:
                signal(pod, 'CONT')
            wait('Mongo quorum restored', lambda: request(fixture['load_path'], {200}), 30)
            resolution_file = proof / 'publication-recovery-resolution.json'
            resolved_process = subprocess.run(['python3', str(args.publisher_hook), '--fixture', str(fixture_file), '--resolve-rejected', '--output', str(resolution_file)], env=env, text=True, capture_output=True, timeout=40)
            assert resolved_process.returncode == 0, 'Unknown publication outcome was not resolved after majority recovery'
            resolution = json.loads(resolution_file.read_text())
            assert resolution['passed'] and resolution['published'] is False
            assert resolution['commitStatus'] == 'not-published-after-majority-recovery'
            assert resolution['majorityCommitBarrier'] is True
            assert resolution['operationId'] == blocked['operationId']
            assert policy() == baseline
            record('mongo-no-quorum-majority-resolution', observation=blocked, resolution=resolution, active_pointer_unchanged=True)
            # Replica-lag write rejection is distinct from losing an entire shard.
            try:
                key, _ = bucket()
            except StopIteration:
                request(fixture['quota_path'], {200})
                key, _ = bucket()
            primary_id, primary_node, nodes = owner(key)
            replica_pods = [n['pod'] for n in nodes.values() if n['primary'] == primary_id]
            assert len(replica_pods) == 2
            for pod in replica_pods:
                signal(pod, 'STOP')
            guard_key = key + ':ha-guard'
            def replicas_guard():
                response = redis(primary_node['pod'], ['SET', guard_key, '1'])
                assert response.startswith('NOREPLICAS') or response.startswith('(error) NOREPLICAS')
                return response
            error, _ = wait('Redis min-replicas rejection', replicas_guard, 10)
            cluster_info = redis(primary_node['pod'], ['CLUSTER', 'INFO'])
            assert 'cluster_state:ok' in cluster_info
            assert all(task_state(p) == 'PAUSED' for p in replica_pods)
            request(fixture['quota_path'], {503})
            request(fixture['quota_path'], {503}, 'OPTIONS')
            record('redis-min-replicas-write-guard', primary=primary_node['pod'], paused_replicas=replica_pods,
                   exact_error=error, cluster_info=cluster_info, real_gateway_status=503)
            for pod in replica_pods:
                signal(pod, 'CONT')
            wait('Redis guard recovery', lambda: request(fixture['load_path'], {200}), 30)
            # Lose every member of the actual HA quota shard; other masters still run.
            key, _ = bucket()
            _, primary_node, nodes = owner(key)
            shard = [primary_node['pod']] + [n['pod'] for n in nodes.values() if n['primary'] == owner(key)[0]]
            assert len(shard) == 3
            for pod in shard:
                signal(pod, 'STOP')
            survivor = diagnostic_pods(fixture['redis_pods'])[0]
            wait('Redis CLUSTERDOWN distinct from transport failure', lambda: assert_cluster_down(redis(survivor, ['CLUSTER', 'INFO'])), 30)
            request(fixture['quota_path'], {503})
            request(fixture['quota_path'], {503}, 'OPTIONS')
            record('redis-shard-unavailable', paused_members=shard, cluster_info=redis(survivor, ['CLUSTER', 'INFO']), real_gateway_status=503)
            for pod in shard:
                signal(pod, 'CONT')
            wait('Redis recovery after unavailable shard restore', lambda: request(fixture['load_path'], {200}), 30)
            request(fixture['quota_path'], {429} if args.phase == 'all' else {200, 429})
        result = {'result': 'pass', 'phase': args.phase, 'context': fixture['context'], 'checks': checks,
                  'limits': ['Single Docker Desktop host only', 'AOF everysec and asynchronous replication retain nonzero RPO risk', 'Local PVCs do not prove off-host backup recovery']}
        (proof / 'pending-result.json').write_text(json.dumps({**result, 'complete': False}, indent=2) + '\n')
    except Exception as error:
        # Persist public observations before cleanup; failed attempts must remain reviewable.
        try:
            stop_sampler()
        except Exception:
            phase_evidence['sampler_shutdown_failed'] = True
        (proof / 'failure.json').write_text(json.dumps({'complete': False, 'phase': failure_phase, 'exception_class': type(error).__name__,
            'phase_evidence': phase_evidence, 'continuous_outage_samples': outage_samples, 'checks': checks,
            'paused_tasks': paused, 'stopped_nodes': sorted(stopped_nodes)}, indent=2) + '\n')
        raise
    finally:
        fault_deadline = None
        cleanup_started = time.monotonic()
        cleanup_failures = []
        try:
            stop_sampler()
        except Exception:
            cleanup_failures.append('continuous probe shutdown failed')
        for node in list(stopped_nodes):
            if subprocess.run(['docker', 'start', node], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                cleanup_failures.append('node restore failed: ' + node)
        for pod in list(paused):
            try:
                signal(pod, 'CONT')
            except Exception:
                cleanup_failures.append('process restore failed: ' + pod)
        for pod, namespace, path in sentinels:
            try:
                kube(['-n', namespace, 'exec', pod, '--', 'rm', '-f', path])
            except Exception:
                cleanup_failures.append('sentinel cleanup failed: ' + pod)
        if route_restore is not None:
            try:
                kube(['-n', ns, 'patch', 'httproute', 'resilience-api', '--type=merge', '--patch-file=/dev/stdin'], stdin=json.dumps({'spec': route_restore}))
            except Exception:
                cleanup_failures.append('route restoration failed')
        try:
            full_restore(cleanup_started)
        except Exception:
            cleanup_failures.append('complete fixture restoration failed within 180s')
        for kind, name in [('pod', probe), ('secret', probe_secret), ('networkpolicy', probe_policy)]:
            try:
                kube(['-n', ns, 'delete', kind, name, '--ignore-not-found=true', '--wait=false'])
            except Exception:
                cleanup_failures.append('probe cleanup failed: ' + kind)
        if cleanup_failures:
            raise RuntimeError('; '.join(cleanup_failures))
    result['complete'] = True
    (proof / 'ha.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'result': 'pass', 'proof': str(proof / 'ha.json'), 'checks': len(checks)}, indent=2))


def assert_cluster_down(info):
    assert 'cluster_state:fail' in info
    return True


if __name__ == '__main__':
    main()
