#!/usr/bin/env python3
"""Serial fault proof for the isolated fixture. Root must schedule this run exclusively."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
import re
import signal as process_signal
from pathlib import Path
import subprocess
import tempfile
import time
import threading
import uuid
import zipfile



def utc_milestone():
    unix_ms = time.time_ns() // 1_000_000
    return {'unix_ms': unix_ms, 'utc': datetime.fromtimestamp(unix_ms / 1000, timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')}


def canonical_lua_sha(script):
    # BufferedReader.readLine recognizes LF, CRLF and CR; a terminator adds no final line.
    text = script.decode('utf-8')
    lines = re.split(r'\r\n|\r|\n', text)
    if text.endswith(('\r', '\n')):
        lines.pop()
    return hashlib.sha1('\n'.join(lines).encode('utf-8')).hexdigest()


def packaged_script(jar_file):
    jar_bytes = jar_file.read_bytes()
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        libraries = [name for name in jar.namelist() if name.startswith('BOOT-INF/lib/fluxgate-redis-ratelimiter-') and name.endswith('.jar')]
        assert len(libraries) == 1, 'Expected exactly one packaged Redis rate limiter library'
        with zipfile.ZipFile(io.BytesIO(jar.read(libraries[0]))) as library:
            resource = 'lua/token_bucket_consume.lua'
            script = library.read(resource)
    return {'sha1': canonical_lua_sha(script), 'jar_file': str(jar_file), 'jar_sha256': hashlib.sha256(jar_bytes).hexdigest(),
            'nested_archive': libraries[0], 'resource': resource, 'raw_resource_sha256': hashlib.sha256(script).hexdigest(),
            'loader': 'UTF-8 BufferedReader.lines().collect(joining("\\n"))'}


def run_publisher_preparation(command, env, timeout=120):
    process = subprocess.Popen(command, env=env, text=True, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        # This dedicated session contains only the preparation helper and its descendants.
        try:
            os.killpg(process.pid, process_signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        # A leader can exit while descendants close their pipes and continue running.
        try:
            os.killpg(process.pid, process_signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate(timeout=3)
        raise
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def validate_prepared_publisher(state, pod, source_pod, baseline, rule_set_id, local_jar_sha, probe_jar_sha, source_jar_sha):
    assert isinstance(state.get('operationId'), str) and state['operationId'].strip(), 'Prepared publication operation ID missing'
    assert state.get('ruleSetId') == rule_set_id and state.get('baseline', {}).get('ruleSetId') == rule_set_id, 'Prepared policy rule set differs'
    assert state.get('pod') == pod['metadata']['name'] and state.get('pod_uid') == pod['metadata']['uid'], 'Prepared publisher Pod identity changed'
    assert not pod['metadata'].get('deletionTimestamp'), 'Prepared publisher Pod is terminating'
    assert state.get('probe_owned') is True, 'Prepared publisher is not an owned dedicated probe'
    assert pod['metadata'].get('labels', {}).get('app') == 'fluxgate-publication-probe', 'Prepared publisher is not a dedicated probe Pod'
    assert pod['metadata']['labels'].get('fluxgate.io/publication-probe') == state['pod'], 'Prepared probe ownership label differs'
    assert state.get('source_authz_pod') == source_pod['metadata']['name'] and state.get('source_authz_pod_uid') == source_pod['metadata']['uid'], 'Prepared source authz identity changed'
    assert source_pod['metadata'].get('labels', {}).get('app') == 'fluxgate-authz' and not source_pod['metadata'].get('deletionTimestamp'), 'Prepared source is not an active authz Pod'
    assert any(c['type'] == 'Ready' and c['status'] == 'True' for c in source_pod.get('status', {}).get('conditions', [])), 'Prepared source authz Pod is not Ready'
    assert any(c['type'] == 'Ready' and c['status'] == 'True' for c in pod.get('status', {}).get('conditions', [])), 'Prepared publisher Pod is not Ready'
    assert state.get('jar_sha256') == local_jar_sha == probe_jar_sha == source_jar_sha, 'Prepared publisher JAR identity changed'
    assert isinstance(local_jar_sha, str) and re.fullmatch(r'[0-9a-f]{64}', local_jar_sha), 'Prepared publisher JAR digest missing'
    for field in ('snapshotId', 'revision', 'counterEpoch', 'checksum'):
        assert field in state['baseline'] and field in baseline, 'Prepared policy identity field missing'
        assert str(state['baseline'][field]) == str(baseline[field]), 'Prepared publisher policy snapshot changed: ' + field


def validate_publisher_cleanup(result):
    assert result.get('passed') is True and result.get('pod_absent') is True and result.get('network_policy_absent') is True, 'Publication probe cleanup absence is unverified'


def home_failover_targets(redis_nodes, placements):
    homes = ['redis-' + str(i) + '-0' for i in range(3)]
    by_pod = {node['pod']: (identity, node) for identity, node in redis_nodes.items()}
    assert len({placements[pod] for pod in homes}) == 3, 'Redis home primaries must occupy three distinct nodes'
    owning_shards = []
    targets = []
    for pod in homes:
        identity, node = by_pod[pod]
        assert not set(node['flags']).intersection({'fail', 'fail?', 'handshake', 'noaddr'})
        if 'master' in node['flags']:
            owning_shards.append(identity)
        else:
            assert 'slave' in node['flags'] and not node['slots'], 'Only healthy replicas without slots may fail over'
            owning_shards.append(node['primary'])
            targets.append({'pod': pod, 'identity': identity, 'previous_primary_id': node['primary']})
    assert len(set(owning_shards)) == 3, 'Home members must represent three different shards'
    return targets


DENSE_RECOVERY_SOURCE = r'''import json,pathlib,sys,time,urllib.request,urllib.error
host,path,hostname,backend,budget=sys.argv[1:]
started=time.monotonic();deadline=started+float(budget);samples=[];streak=[];recovered=False
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
key=pathlib.Path('/key/load-api-key').read_text().strip()
while time.monotonic()<deadline:
    item={'requested_unix_ms':time.time_ns()//1000000}
    try:
        req=urllib.request.Request(host+path,headers={'Host':hostname,'X-API-Key':key})
        try: response=opener.open(req,timeout=max(.01,min(2,deadline-time.monotonic())))
        except urllib.error.HTTPError as error: response=error
        with response:
            item['status']=response.status
            body=response.read()
            item['body_valid']=body in (backend.encode(),backend.encode()+b'\n')
            item['backend_marker_present']=backend.encode() in body
            item['route_marker_valid']='x-ha-controller-proof' not in response.headers
        if item['status']==200 and (not item['body_valid'] or not item['route_marker_valid']):
            item['unexpected']=True
        if item['status'] not in (200,503) or (item['status']!=200 and item['backend_marker_present']):
            item['unexpected']=True
    except Exception as error:
        item['transport_error']=type(error).__name__
    item['completed_unix_ms']=time.time_ns()//1000000;samples.append(item)
    if item.get('unexpected'): break
    if item.get('status')==200 and item.get('body_valid') and item.get('route_marker_valid') and not item.get('transport_error'): streak.append(item)
    else: streak=[]
    if len(streak)>=10 and streak[-1]['completed_unix_ms']-streak[0]['completed_unix_ms']>=2000:
        recovered=True;break
    time.sleep(min(.2,max(0,deadline-time.monotonic())))
print(json.dumps({'recovered':recovered,'samples':samples,'consecutive_successes':len(streak),'success_window':streak}))
'''


def main():
    if not __debug__:
        raise RuntimeError('Assertions disabled; run Python without -O')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--phase', choices=['all', 'baseline', 'redis', 'mongo', 'pod-restarts', 'node', 'no-quorum'], default='all')
    parser.add_argument('--publisher-hook', type=Path, default=Path(__file__).with_name('publish-hook.py'))
    parser.add_argument('--preserve-promoted-roles', action='store_true', help='Defer Redis home failback until after the combined observation')
    parser.add_argument('--prepared-publisher', action='store_true', help='Reuse a validated helper prepared before measured Mongo load')
    parser.add_argument('--proof', type=Path)
    args = parser.parse_args()
    if args.prepared_publisher and args.phase != 'mongo':
        parser.error('--prepared-publisher is valid only with --phase mongo')
    if args.preserve_promoted_roles and args.phase != 'redis':
        parser.error('--preserve-promoted-roles is valid only with --phase redis')
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
    owned_probe_uids = {}

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

    def apply_owned_probe(resource):
        resource['metadata'].setdefault('labels', {})['fluxgate.io/ha-probe'] = probe
        apply(resource)
        current = obj(resource['kind'], resource['metadata']['name'])
        owned_probe_uids[(resource['kind'].lower(), resource['metadata']['name'])] = current['metadata']['uid']

    def remove_owned_probe(kind, name):
        started = time.monotonic()
        def lookup():
            value = kube(['-n', ns, 'get', kind, name, '--ignore-not-found=true', '-o', 'json'], timeout=max(.1, 30 - (time.monotonic() - started)))
            return json.loads(value) if value.strip() else None
        current = lookup()
        if current is not None:
            assert current['metadata'].get('labels', {}).get('fluxgate.io/ha-probe') == probe, 'Probe cleanup ownership label differs'
            expected_uid = owned_probe_uids.get((kind, name))
            assert expected_uid is None or current['metadata']['uid'] == expected_uid, 'Probe cleanup UID changed'
            delete_args = ['-n', ns, 'delete', kind, name, '--wait=true', '--timeout=25s']
            if kind == 'pod':
                delete_args.append('--grace-period=3')
            kube(delete_args, timeout=max(.1, 30 - (time.monotonic() - started)))
        assert lookup() is None and time.monotonic() - started <= 30, 'Owned probe resource remains after cleanup'
        record('owned-probe-resource-cleanup', kind=kind, name=name, uid=owned_probe_uids.get((kind, name)), absent=True)

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
        expected = fixture['backend_body']
        valid = response['body'] in (expected, expected + '\n')
        assert valid if response['status'] == 200 else expected not in response['body'], 'Invalid backend body or denied/error request reached backend'
        if expected_header is not None:
            assert response['headers'].get(expected_header[0]) == expected_header[1]
        return response['status']

    def start_sampler(started):
        nonlocal sampler_stop, sampler_thread
        sampler_stop = threading.Event()
        observations = []
        def sample():
            while not sampler_stop.is_set():
                item = {'elapsed_seconds': round(time.monotonic() - started, 3), 'requested_unix_ms': time.time_ns() // 1_000_000}
                try:
                    item['status'] = request(fixture['load_path'], {200, 503})
                except AssertionError as error:
                    item['unexpected'] = str(error)
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    item['transport_error'] = type(error).__name__
                item['completed_unix_ms'] = time.time_ns() // 1_000_000
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

    def signal(pod, action, timestamps=None):
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
            if timestamps is not None:
                timestamps['fault_start'] = utc_milestone()
            subprocess.run(paused[pod]['ctr'] + ['pause', paused[pod]['container']], check=True, timeout=10, stdout=subprocess.DEVNULL)
            assert task_state(pod) == 'PAUSED', 'Container task was not actually frozen'
            if timestamps is not None:
                timestamps['paused_verified'] = utc_milestone()
        else:
            if task_state(pod) == 'PAUSED':
                subprocess.run(paused[pod]['ctr'] + ['resume', paused[pod]['container']], check=True, timeout=10, stdout=subprocess.DEVNULL)
            assert task_state(pod) == 'RUNNING'
            paused.pop(pod)

    def sustained_traffic(started, seconds, while_down=None, expected_header=None, timestamps=None):
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
                if timestamps is not None:
                    timestamps['sustained_gateway_recovered'] = utc_milestone()
                return round(time.monotonic() - started, 3), observations[-3:]
            time.sleep(1)
        raise RuntimeError(f'Three consecutive real Gateway 200 responses exceeded {seconds}s')

    def dense_gateway_recovery(started, while_down, background, evidence):
        batches = evidence.setdefault('dense_gateway_batches', [])
        host = f'http://{fixture["gateway_service"]}.{fixture["gateway_namespace"]}.svc.cluster.local'
        while time.monotonic() - started < 30:
            assert while_down(), 'Fault target or application clients changed during dense recovery'
            remaining = 30 - (time.monotonic() - started) - .5
            if remaining <= 0:
                break
            result = json.loads(kube(['-n', ns, 'exec', probe, '--', 'python', '-c', DENSE_RECOVERY_SOURCE,
                                      host, fixture['load_path'], fixture['gateway_host'], fixture['backend_body'], str(remaining)], timeout=remaining + .5))
            batches.append(result)
            assert not any(s.get('unexpected') for s in result['samples']), 'Dense Gateway probe received invalid status/body/route marker'
            if result['recovered']:
                window_start = result['success_window'][0]['requested_unix_ms']
                observed = [s for s in list(background) if s.get('completed_unix_ms', 0) >= window_start]
                if not observed or any(s.get('status') != 200 for s in observed):
                    result['background_window_rejected'] = True
                    continue
                assert while_down() and time.monotonic() - started <= 30
                evidence['timestamps']['sustained_gateway_recovered'] = utc_milestone()
                return round(time.monotonic() - started, 3), result['success_window']
            break
        raise RuntimeError('Dense actual Gateway recovery exceeded the original 30s fault budget')

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

    def shard_placement(redis_nodes):
        placements = {p['metadata']['name']: p['spec']['nodeName']
                      for p in obj('pods', selector='app=redis')['items']}
        shards = {}
        for identity, primary in redis_nodes.items():
            if 'master' not in primary['flags']:
                continue
            members = [primary['pod']] + [n['pod'] for n in redis_nodes.values() if n['primary'] == identity]
            assert len(members) == 3, 'Each Redis primary must retain exactly two replicas'
            nodes = [placements[pod] for pod in members]
            assert set(nodes) == expected_nodes, 'Each Redis shard must span all three failure domains'
            shards[identity] = {'members': members, 'nodes': nodes, 'slots': primary['slots']}
        assert len(shards) == 3
        return shards

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
            kube(['-n', namespace, 'exec', '-i', pod, '--', 'sh', '-c', 'umask 077; cat > "$1"', 'probe', sentinel_path], stdin=content)
            sentinels.append((pod, namespace, sentinel_path))
            written = kube(['-n', namespace, 'exec', pod, '--', 'cat', sentinel_path])
            assert hashlib.sha256(written.encode()).hexdigest() == sentinel_hash, 'PVC probe must be readable before Pod deletion'
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
        if result.returncode:
            # Driver stderr may contain URIs. Retain it only inside the private proof directory.
            diagnostic = proof / 'publication-hook-private-failure.log'
            diagnostic.write_text(result.stdout + '\nSTDERR\n' + result.stderr)
            diagnostic.chmod(0o600)
        assert result.returncode == 0, 'Actual repository publication hook did not meet its expectation'
        return json.loads(output.read_text())

    def prepare_publisher():
        prepared = run_publisher_preparation(['python3', str(args.publisher_hook), '--fixture', str(fixture_file), '--prepare'], env, timeout=120)
        assert prepared.returncode == 0, 'Actual repository publisher preflight preparation failed'

    def reuse_prepared_publisher(baseline):
        state_file = fixture_file.parent / 'publication-hook-state.json'
        assert state_file.is_file() and not state_file.stat().st_mode & 0o077, 'Private prepared publication state required'
        state = json.loads(state_file.read_text())
        assert isinstance(state.get('pod'), str) and state['pod'], 'Prepared publication Pod missing'
        pod = obj('pod', state['pod'])
        jar_sha = hashlib.sha256(Path(fixture['application_jar_file']).read_bytes()).hexdigest()
        source_pod = obj('pod', state['source_authz_pod'])
        probe_sha = kube(['-n', ns, 'exec', state['pod'], '--', 'sha256sum', '/app/app.jar']).split()[0]
        source_sha = kube(['-n', ns, 'exec', state['source_authz_pod'], '--', 'sha256sum', '/app/app.jar']).split()[0]
        validate_prepared_publisher(state, pod, source_pod, baseline, fixture['rule_set_id'], jar_sha, probe_sha, source_sha)
        record('prepared-publisher-validated', source_authz_pod_uid=state['source_authz_pod_uid'], pod_uid=state['pod_uid'], jar_sha256=jar_sha, operation_id=state['operationId'],
               snapshot_id=baseline['snapshotId'], revision=baseline['revision'], counter_epoch=baseline['counterEpoch'], checksum=baseline['checksum'])

    def authz_processes():
        return sorted((p['metadata']['uid'], c['containerID'], c.get('restartCount', 0))
                      for p in obj('pods', selector='app=fluxgate-authz')['items']
                      for c in p['status'].get('containerStatuses', []))

    def full_restore(started, preserve_promoted_roles=False):
        nonlocal restore_deadline
        restore_deadline = started + 180
        restoration_started = utc_milestone()
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
                assert redis(pod, ['CONFIG', 'GET', 'cluster-allow-replica-migration']).splitlines() == ['cluster-allow-replica-migration', 'no']
            actual_shards = shard_placement(redis_nodes)
            for identity, primary in primaries.items():
                assert sum(n['primary'] == identity for n in replicas.values()) == 2
                replication = redis(primary['pod'], ['INFO', 'replication'])
                assert 'connected_slaves:2' in replication
                assert len([line for line in replication.splitlines() if line.startswith(('slave0:', 'slave1:')) and 'state=online' in line]) == 2
            for replica in replicas.values():
                assert 'master_link_status:up' in redis(replica['pod'], ['INFO', 'replication'])
            if route_restore is not None:
                assert obj('httproute', 'resilience-api')['spec'] == route_restore
            return {'ready_counts': counts, 'mongo_healthy_voters': 3, 'redis_primaries': 3, 'redis_linked_replicas': 6,
                    'redis_shard_placement': actual_shards, 'replica_migration_disabled_on_all_members': True}
        try:
            remaining = restore_deadline - time.monotonic()
            state, _ = wait('Complete fixture restoration', healthy, max(0, remaining))
            before_nodes = topology()
            placements = {p['metadata']['name']: p['spec']['nodeName'] for p in obj('pods', selector='app=redis')['items']}
            targets = home_failover_targets(before_nodes, placements)
            before_primary_nodes = {identity: placements[node['pod']] for identity, node in before_nodes.items() if 'master' in node['flags']}
            preserved_policy = policy()
            try:
                preserved_bucket = bucket()
            except StopIteration:
                preserved_bucket = None
            failbacks = [] if preserve_promoted_roles else targets
            for target in failbacks:
                current = topology()[target['identity']]
                assert 'slave' in current['flags'] and not current['slots']
                assert 'master_link_status:up' in redis(target['pod'], ['INFO', 'replication'])
                assert redis(target['pod'], ['CLUSTER', 'FAILOVER']) == 'OK'
                def promoted_home():
                    actual = topology()[target['identity']]
                    assert 'master' in actual['flags'] and actual['slots']
                    return healthy()
                state, _ = wait('Normal operator-assisted home primary failover', promoted_home, max(0, restore_deadline - time.monotonic()))
            assert policy() == preserved_policy, 'Role balancing changed the published policy'
            try:
                after_bucket = bucket()
            except StopIteration:
                after_bucket = None
            assert after_bucket == preserved_bucket, 'Role balancing changed the exact HA quota bucket'
            after_nodes = topology()
            after_primary_nodes = {identity: placements[node['pod']] for identity, node in after_nodes.items() if 'master' in node['flags']}
            assert len(after_primary_nodes) == 3
            distinct_primary_nodes = len(set(after_primary_nodes.values())) == 3
            if not preserve_promoted_roles:
                assert distinct_primary_nodes and not home_failover_targets(after_nodes, placements), 'Redis home roles were not restored'
            state['redis_primary_nodes_distinct'] = distinct_primary_nodes
            state['placement_restoration_deferred'] = preserve_promoted_roles
            elapsed, responses = sustained_traffic(started, 180, expected_header=(route_header, None) if route_header else None)
            record('redis-primary-placement-restoration', performed=bool(failbacks), command='CLUSTER FAILOVER' if failbacks else None,
                   placement_restoration_deferred=preserve_promoted_roles, home_roles_pending=bool(home_failover_targets(after_nodes, placements)),
                   actual_primary_nodes_distinct=distinct_primary_nodes,
                   before_topology=before_nodes, after_topology=after_nodes,
                   before_placements=before_primary_nodes, after_placements=after_primary_nodes,
                   policy=preserved_policy, policy_exact_preserved=True, raw_bucket_before=preserved_bucket,
                   raw_bucket_after=after_bucket, ha_counter_exact_preserved=True,
                   timestamps={'started': restoration_started, 'completed': utc_milestone()},
                   shared_restore_elapsed_seconds=elapsed, automatic_placement_claim=False)
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
        apply_owned_probe({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': probe_secret, 'namespace': ns},
               'data': {'api-key': base64.b64encode(Path(fixture['ha_api_key_file']).read_bytes()).decode(),
                        'load-api-key': base64.b64encode(Path(fixture['api_key_file']).read_bytes()).decode()}})
        apply_owned_probe({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': probe_policy, 'namespace': ns},
            'spec': {'podSelector': {'matchLabels': {'app': probe}}, 'policyTypes': ['Egress'], 'egress': [
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': fixture['gateway_namespace']}},
                         'podSelector': {'matchLabels': {'gateway.envoyproxy.io/owning-gateway-namespace': ns}}}], 'ports': [{'port': 80}, {'port': 10080}]},
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}}, 'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
                 'ports': [{'port': 53, 'protocol': 'UDP'}, {'port': 53, 'protocol': 'TCP'}]}]}})
        apply_owned_probe({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': probe, 'namespace': ns, 'labels': {'app': probe}},
            'spec': {'automountServiceAccountToken': False, 'terminationGracePeriodSeconds': 1, 'nodeSelector': {'kubernetes.io/hostname': ns + '-control-plane'},
                     'tolerations': [{'key': 'node-role.kubernetes.io/control-plane', 'operator': 'Exists', 'effect': 'NoSchedule'}],
                     'containers': [{'name': 'probe', 'image': 'python:3.12-alpine', 'command': ['python', '-c', 'import time;time.sleep(3600)'],
                         'resources': {'requests': {'cpu': '10m', 'memory': '32Mi'}, 'limits': {'cpu': '100m', 'memory': '128Mi'}},
                         'volumeMounts': [{'name': 'key', 'mountPath': '/key', 'readOnly': True}]}],
                     'volumes': [{'name': 'key', 'secret': {'secretName': probe_secret, 'defaultMode': 256}}]}})
        kube(['-n', ns, 'wait', '--for=condition=Ready', 'pod/' + probe, '--timeout=180s'], timeout=190)
        full_restore(time.monotonic())
        baseline = policy()
        redis_baseline = topology()
        record('baseline', policy=baseline, redis=redis_baseline, actual_redis_shard_placement=shard_placement(redis_baseline),
               initial_setup_placement=json.loads((fixture_file.parent / 'placement.json').read_text()))
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
            script_provenance = packaged_script(Path(fixture['application_jar_file']))
            sha = script_provenance['sha1']
            authz_pod = obj('pods', selector='app=fluxgate-authz')['items'][0]['metadata']['name']
            deployed_jar_sha = kube(['-n', ns, 'exec', authz_pod, '--', 'sha256sum', '/app/app.jar']).split()[0]
            assert deployed_jar_sha == script_provenance['jar_sha256'], 'Local packaged script artifact differs from deployed app'
            assert redis(old_primary['pod'], ['SCRIPT', 'EXISTS', sha]) == '1', 'Canonical packaged Lua SHA must exist before replica flush'
            phase_evidence.update({'script_provenance': script_provenance, 'script_exists_old_primary_before_flush': True})
            for replica in replicas:
                redis(replica['pod'], ['SCRIPT', 'FLUSH'])
                assert redis(replica['pod'], ['SCRIPT', 'EXISTS', sha]) == '0'
            phase_evidence.update({'old_primary_id': old_id, 'old_primary': old_primary})
            fault_started = time.monotonic()
            fault_deadline = fault_started + 30
            phase_evidence['fault_started_monotonic'] = fault_started
            phase_evidence['timestamps'] = {'rto_budget_started': utc_milestone()}
            signal(old_primary['pod'], 'STOP', phase_evidence['timestamps'])
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
            recovery_seconds, sustained = dense_gateway_recovery(fault_started, unchanged_paused_clients, outage_samples, phase_evidence)
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
            phase_evidence['timestamps']['quota_verified'] = utc_milestone()
            stop_sampler()
            assert not any('unexpected' in sample for sample in outage_samples)
            record('redis-promotion', timestamps=phase_evidence['timestamps'], old_primary_id=old_id, new_primary_id=new_id, target_still_paused=old_primary['pod'] in paused,
                   final_quota_seconds=phase_evidence['final_quota_seconds'], recovery_seconds=recovery_seconds, identity_promotion_seconds=seconds, sustained_gateway=sustained, dense_gateway_batches=phase_evidence['dense_gateway_batches'], task_state=task_state(old_primary['pod']), continuous_outage_samples=outage_samples, load_key_owned_failed_shard=True, bucket_key=key, raw_before=before, raw_after_promotion=after, raw_exhausted=exhausted,
                   script_provenance=script_provenance, script_exists_old_primary_before_flush=True, cache_absent_before_promotion=True, cache_loaded_after_real_request=True, authz_processes_unchanged=authz_before, rpo='Observed counter preserved; asynchronous replication is not zero-loss consensus')
            fault_deadline = None
            signal(old_primary['pod'], 'CONT')
            full_restore(time.monotonic(), preserve_promoted_roles=args.preserve_promoted_roles)
        if args.phase in ('all', 'mongo'):
            failure_phase = 'mongo-election-publication'
            phase_evidence = {}
            outage_samples = []
            if args.prepared_publisher:
                reuse_prepared_publisher(baseline)
            else:
                prepare_publisher()
            old_primary = mongo_primary()
            fault_started = time.monotonic()
            phase_evidence['timestamps'] = {'rto_budget_started': utc_milestone()}
            signal(old_primary, 'STOP', phase_evidence['timestamps'])
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
            phase_evidence['timestamps']['publication_verified'] = utc_milestone()
            recovery_seconds, sustained = dense_gateway_recovery(fault_started, lambda: task_state(old_primary) == 'PAUSED', outage_samples, phase_evidence)
            if args.phase == 'all':
                request(fixture['quota_path'], {429})
            stop_sampler()
            assert not any('unexpected' in sample for sample in outage_samples)
            assert time.monotonic() - fault_started <= 30, 'Mongo publication and traffic proof exceeded original 30s fault budget'
            phase_evidence['timestamps']['policy_and_traffic_verified'] = utc_milestone()
            record('mongo-election-publication', timestamps=phase_evidence['timestamps'], old_primary=old_primary, new_primary=new_primary,
                   target_still_paused=task_state(old_primary) == 'PAUSED', recovery_seconds=recovery_seconds, sustained_gateway=sustained, dense_gateway_batches=phase_evidence['dense_gateway_batches'], continuous_outage_samples=outage_samples, publication=published, policy=current)
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
            full_restore(time.monotonic())
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
            full_restore(cleanup_started, preserve_promoted_roles=args.preserve_promoted_roles)
        except Exception:
            cleanup_failures.append('complete fixture restoration failed within 180s')
        try:
            cleaned = run_publisher_preparation(['python3', str(args.publisher_hook), '--fixture', str(fixture_file), '--cleanup'], env, timeout=90)
            assert cleaned.returncode == 0, 'Publication probe cleanup failed'
            cleanup_result = json.loads(cleaned.stdout)
            validate_publisher_cleanup(cleanup_result)
            record('publication-probe-cleanup', **cleanup_result)
            (proof / 'publisher-cleanup.json').write_text(json.dumps(cleanup_result, indent=2) + '\n')
        except Exception as error:
            (proof / 'publisher-cleanup.json').write_text(json.dumps({'passed': False, 'exception_class': type(error).__name__}) + '\n')
            cleanup_failures.append('publication probe cleanup failed')
        for kind, name in [('pod', probe), ('secret', probe_secret), ('networkpolicy', probe_policy)]:
            try:
                remove_owned_probe(kind, name)
            except Exception:
                cleanup_failures.append('probe cleanup failed: ' + kind)
        if cleanup_failures:
            raise RuntimeError('; '.join(cleanup_failures))
    result['phase_complete'] = True
    result['complete'] = args.phase == 'all'
    (proof / 'ha.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'result': 'pass', 'proof': str(proof / 'ha.json'), 'checks': len(checks)}, indent=2))


def assert_cluster_down(info):
    assert 'cluster_state:fail' in info
    return True


if __name__ == '__main__':
    main()
