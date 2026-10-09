#!/usr/bin/env python3
"""Build only the isolated three-node local HA fixture; never inject faults."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
NS = 'fluxgate-resilience'
CONTEXT = 'kind-fluxgate-resilience'
NODE_IMAGE = 'kindest/node:v1.35.0@sha256:452d707d4862f52530247495d180205e029056831160e22870e37e3f6c1ac31f'
MONGO_PRESET = {'electionTimeoutMillis': 5000, 'catchUpTimeoutMillis': 2000}


def preset_config(config):
    expected_hosts = {f'mongo-{i}.mongo.{NS}.svc.cluster.local:27017' for i in range(3)}
    members = config.get('members', [])
    if (config.get('_id') != 'rs0' or len(members) != 3
            or {m.get('host') for m in members} != expected_hosts
            or {m.get('_id') for m in members} != {0, 1, 2}
            or any(m.get('arbiterOnly', False) or m.get('votes', 1) != 1 for m in members)):
        raise ValueError('preset requires the exact owned three-data-member rs0')
    updated = copy.deepcopy(config)
    updated.setdefault('settings', {}).update(MONGO_PRESET)
    if updated != config:
        updated['version'] = config['version'] + 1
    return updated


def apply_mongo_preset(kube, mongo, wait, fixture, run):
    if fixture.get('context') != CONTEXT or fixture.get('namespace') != NS:
        raise ValueError('preset requires exact local fixture context and namespace')
    namespace = json.loads(kube(['get', 'namespace', NS, '-o', 'json']))
    if namespace['metadata'].get('labels', {}).get('fluxgate.io/environment') != 'local-ephemeral':
        raise ValueError('preset requires local-ephemeral ownership label')
    nodes = json.loads(kube(['get', 'nodes', '-o', 'json']))['items']
    names = {node['metadata']['name'] for node in nodes}
    if (len(nodes) != 3 or len(names) != 3 or names != set(fixture['node_container_names'])
            or any(not name.startswith(NS + '-') for name in names)):
        raise ValueError('preset requires exact owned three-node fixture')
    for name in sorted(names):
        labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', name]))
        if labels.get('io.x-k8s.kind.cluster') != NS:
            raise ValueError('preset node Docker cluster ownership mismatch')
    pods = json.loads(kube(['-n', NS, 'get', 'pods', '-l', 'app=mongo', '-o', 'json']))['items']
    if ({p['metadata']['name'] for p in pods} != {'mongo-0', 'mongo-1', 'mongo-2'}
            or len(pods) != 3 or any(p['spec'].get('nodeName') not in names for p in pods)):
        raise ValueError('preset requires exactly three owned Mongo Pods')

    def read(pod, command):
        # Extended JSON preserves replicaSetId and other BSON fields on reconfig.
        encoded = json.dumps(json.dumps(command))
        return json.loads(mongo(pod, 'print(EJSON.stringify(admin.runCommand(EJSON.parse(' + encoded + '))));'))

    def primary_status():
        status = read('mongo-0', {'replSetGetStatus': 1})
        if status.get('ok') != 1 or status.get('set') != 'rs0' or len(status.get('members', [])) != 3:
            raise ValueError('preset replica set status mismatch')
        if any(m.get('health') != 1 or m.get('state') not in (1, 2) for m in status['members']):
            raise ValueError('preset requires healthy primary and two secondaries')
        primary = [m for m in status['members'] if m['state'] == 1]
        if len(primary) != 1:
            raise ValueError('preset requires exactly one confirmed primary')
        return primary[0]['name'].split('.')[0]

    primary = wait('Mongo preset healthy primary', primary_status)
    current = read(primary, {'replSetGetConfig': 1, 'commitmentStatus': True})
    if current.get('ok') != 1 or current.get('commitmentStatus') is not True:
        raise ValueError('previous Mongo configuration must be majority committed')
    config = current['config']
    updated = preset_config(config)
    if updated != config:
        # Ordinary primary reconfiguration preserves all fields; never force it.
        result = read(primary, {'replSetReconfig': updated, 'maxTimeMS': 10000})
        if result.get('ok') != 1:
            raise ValueError('ordinary primary Mongo reconfiguration failed')

    def committed_everywhere():
        primary_now = primary_status()
        committed = read(primary_now, {'replSetGetConfig': 1, 'commitmentStatus': True})
        if committed.get('ok') != 1 or committed.get('commitmentStatus') is not True:
            raise ValueError('Mongo preset configuration not majority committed')
        authoritative = committed['config']
        if authoritative != updated:
            raise ValueError('Mongo configuration changed during preset application')
        for pod in ('mongo-0', 'mongo-1', 'mongo-2'):
            observed = read(pod, {'replSetGetConfig': 1})
            status = read(pod, {'replSetGetStatus': 1})
            if (observed.get('ok') != 1 or observed.get('config') != authoritative
                    or status.get('ok') != 1 or status.get('set') != 'rs0'
                    or status.get('myState') not in (1, 2)
                    or len(status.get('members', [])) != 3
                    or any(m.get('health') != 1 or m.get('state') not in (1, 2)
                           for m in status['members'])):
                raise ValueError('all three members must confirm the committed preset')
        return True
    wait('Mongo preset committed on all three members', committed_everywhere, seconds=60)
    print('Local Mongo timing preset majority committed and confirmed on all three members', flush=True)


def preset_self_check():
    config = {'_id': 'rs0', 'version': 7, 'term': 2,
              'members': [{'_id': i, 'host': f'mongo-{i}.mongo.{NS}.svc.cluster.local:27017',
                           'priority': 1, 'votes': 1} for i in range(3)],
              'writeConcernMajorityJournalDefault': True,
              'settings': {'electionTimeoutMillis': 10000, 'catchUpTimeoutMillis': -1,
                           'getLastErrorDefaults': {'w': 1, 'wtimeout': 0},
                           'replicaSetId': {'$oid': '012345678901234567890123'}}}
    updated = preset_config(config)
    assert config['version'] == 7 and updated['version'] == 8
    assert updated['members'] == config['members'] and updated['term'] == config['term']
    assert updated['writeConcernMajorityJournalDefault'] is True
    assert updated['settings']['getLastErrorDefaults'] == config['settings']['getLastErrorDefaults']
    assert updated['settings']['replicaSetId'] == config['settings']['replicaSetId']
    assert all(updated['settings'][k] == v for k, v in MONGO_PRESET.items())
    assert preset_config(updated) == updated
    for invalid in (dict(config, _id='production'), dict(config, members=config['members'][:2]),
                    dict(config, members=[dict(m, host='foreign:27017') for m in config['members']])):
        try:
            preset_config(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError('foreign replica configuration accepted')
    node_names = [NS + '-control-plane', NS + '-worker', NS + '-worker2']
    fixture = {'context': CONTEXT, 'namespace': NS, 'node_container_names': node_names}
    state, calls = [copy.deepcopy(config)], []
    def kube(command):
        if 'namespace' in command:
            return json.dumps({'metadata': {'labels': {'fluxgate.io/environment': 'local-ephemeral'}}})
        if 'nodes' in command:
            return json.dumps({'items': [{'metadata': {'name': n}} for n in node_names]})
        return json.dumps({'items': [{'metadata': {'name': f'mongo-{i}'},
                                     'spec': {'nodeName': node_names[i]}} for i in range(3)]})
    def mongo(pod, source):
        command = json.loads(json.loads(source.split('EJSON.parse(', 1)[1].rsplit('))));', 1)[0]))
        calls.append((pod, command))
        if 'replSetReconfig' in command:
            assert pod == 'mongo-1' and 'force' not in command
            state[0] = command['replSetReconfig']
            return json.dumps({'ok': 1})
        if 'replSetGetConfig' in command:
            return json.dumps({'ok': 1, 'config': state[0], 'commitmentStatus': True})
        return json.dumps({'ok': 1, 'set': 'rs0', 'myState': 1 if pod == 'mongo-1' else 2,
                           'members': [{'name': m['host'], 'state': 1 if i == 1 else 2, 'health': 1}
                                       for i, m in enumerate(config['members'])]})
    def run(command):
        return json.dumps({'io.x-k8s.kind.cluster': NS})
    def wait(label, action, seconds=180):
        return action()
    apply_mongo_preset(kube, mongo, wait, fixture, run)
    assert state[0] == updated
    assert {pod for pod, command in calls if command == {'replSetGetConfig': 1}} == {'mongo-0', 'mongo-1', 'mongo-2'}
    assert {pod for pod, command in calls if 'replSetGetStatus' in command} == {'mongo-0', 'mongo-1', 'mongo-2'}
    before = len([command for _, command in calls if 'replSetReconfig' in command])
    apply_mongo_preset(kube, mongo, wait, fixture, run)
    assert len([command for _, command in calls if 'replSetReconfig' in command]) == before
    try:
        apply_mongo_preset(kube, mongo, wait, dict(fixture, context='production'), run)
    except ValueError:
        pass
    else:
        raise AssertionError('foreign fixture accepted')
    for bad_kube, bad_run, bad_mongo in (
            (lambda command: json.dumps({'metadata': {'labels': {}}}), run, mongo),
            (kube, lambda command: json.dumps({'io.x-k8s.kind.cluster': 'foreign'}), mongo),
            (kube, run, lambda pod, source: json.dumps({'ok': 1, 'config': state[0],
                                                        'commitmentStatus': False})
             if 'replSetGetConfig' in source else mongo(pod, source))):
        try:
            apply_mongo_preset(bad_kube, bad_mongo, wait, fixture, bad_run)
        except ValueError:
            pass
        else:
            raise AssertionError('ownership or uncommitted configuration accepted')
    assert len([command for _, command in calls if 'replSetReconfig' in command]) == before
    print('Mongo preset offline contract checks passed; no cluster accessed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, help='Private directory; existing files are reused')
    parser.add_argument('--image', default='fluxgate-envoy-extauth:resilience')
    parser.add_argument('--stores-only', action='store_true', help='Leave authz scaled to zero until final image is accepted')
    parser.add_argument('--apply-mongo-preset', action='store_true', help='Only apply and verify timing settings on an existing owned local fixture')
    parser.add_argument('--self-check', action='store_true', help='Run offline Mongo preset contract checks')
    args = parser.parse_args()
    if args.self_check:
        preset_self_check()
        return
    os.umask(0o077)
    if args.apply_mongo_preset:
        if args.fixture is None:
            parser.error('--apply-mongo-preset requires the original --fixture directory')
        fixture_path = args.fixture.resolve() / 'fixture.json'
        if fixture_path.stat().st_mode & 0o077:
            raise ValueError('existing fixture must be private')
        preset_fixture = json.loads(fixture_path.read_text())
        expected_kubeconfig = args.fixture.resolve() / 'kubeconfig'
        if (Path(preset_fixture['kubeconfig']).resolve() != expected_kubeconfig
                or expected_kubeconfig.stat().st_mode & 0o077):
            raise ValueError('preset requires original private fixture kubeconfig')
    directory = (args.fixture or Path(tempfile.mkdtemp(prefix='fluxgate-resilience-'))).resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    env = dict(os.environ, KUBECONFIG=str(directory / 'kubeconfig'))
    base = ['kubectl', '--context', CONTEXT]

    def run(command, stdin=None, sensitive=False, timeout=600):
        result = subprocess.run(command, input=stdin, env=env, text=True, capture_output=True, timeout=timeout)
        if result.returncode:
            detail = '' if sensitive else result.stderr[-1500:]
            raise RuntimeError(f'{command[0]} failed with exit {result.returncode}. {detail}')
        return result.stdout

    def kube(command, **kwargs):
        return run(base + command, **kwargs)

    def apply(resource, sensitive=False):
        return kube(['apply', '-f', '-'], stdin=json.dumps(resource), sensitive=sensitive)

    def private(name, value=None):
        path = directory / name
        if not path.exists():
            path.write_text(value if value is not None else secrets.token_hex(24))
        path.chmod(0o600)
        return path

    def secret(name, files):
        import base64
        data = {key: base64.b64encode(path.read_bytes()).decode() for key, path in files.items()}
        apply({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': NS},
               'type': 'Opaque', 'data': data}, sensitive=True)

    def mongo(pod, source):
        preamble = '''const fs=require('fs');const password=fs.readFileSync('/admin/mongo-admin-password','utf8').trim();
const conn=new Mongo('mongodb://admin:'+encodeURIComponent(password)+'@127.0.0.1:27017/admin');const admin=conn.getDB('admin');
'''
        return kube(['-n', NS, 'exec', '-i', pod, '--', 'mongosh', '--quiet', '--nodb', '--file', '/dev/stdin'],
                    stdin=preamble + source + '\n', sensitive=True, timeout=30)

    def redis(pod, command):
        script = 'export REDISCLI_AUTH="$(cat /credentials/redis-password)"; exec redis-cli "$@"'
        return kube(['-n', NS, 'exec', pod, '--', 'sh', '-c', script, 'probe'] + command, sensitive=True, timeout=40)

    def wait(label, action, seconds=180):
        end = time.monotonic() + seconds
        while True:
            try:
                return action()
            except (RuntimeError, AssertionError, ValueError, subprocess.TimeoutExpired):
                if time.monotonic() >= end:
                    raise RuntimeError(f'{label} did not become ready within {seconds}s') from None
                time.sleep(2)

    if args.apply_mongo_preset:
        apply_mongo_preset(kube, mongo, wait, preset_fixture, run)
        return

    print('Creating isolated kind/Calico cluster', flush=True)
    clusters = run(['kind', 'get', 'clusters']).splitlines()
    if NS in clusters and args.fixture is None:
        raise RuntimeError('Existing resilience cluster requires its original --fixture directory')
    if NS not in clusters:
        run(['kind', 'create', 'cluster', '--name', NS, '--kubeconfig', env['KUBECONFIG'],
             '--config', str(HERE / 'kind.yaml'), '--image', NODE_IMAGE], timeout=900)
    else:
        run(['kind', 'export', 'kubeconfig', '--name', NS, '--kubeconfig', env['KUBECONFIG']])
    Path(env['KUBECONFIG']).chmod(0o600)
    existing_calico = subprocess.run(base + ['-n', 'calico-system', 'get', 'daemonset', 'calico-node', '-o', 'json'], env=env, capture_output=True, text=True)
    if existing_calico.returncode == 0:
        installed_image = json.loads(existing_calico.stdout)['spec']['template']['spec']['containers'][0]['image']
        assert installed_image.endswith(':v3.33.0'), 'Existing fixture has an unexpected Calico version'
    else:
        for name in ['v3_projectcalico_org-v1beta1.yaml', 'tigera-operator.yaml']:
            destination = directory / name
            run(['curl', '-fsSL', f'https://raw.githubusercontent.com/projectcalico/calico/v3.33.0/manifests/{name}', '-o', str(destination)])
            kube(['apply', '--server-side', '--field-manager=fluxgate-resilience-lab', '-f', str(destination)])
    kube(['wait', '--for=condition=Established', 'crd/installations.operator.tigera.io', '--timeout=120s'])
    apply({'apiVersion': 'operator.tigera.io/v1', 'kind': 'Installation', 'metadata': {'name': 'default'},
           'spec': {'variant': 'Calico', 'calicoNetwork': {'bgp': 'Disabled', 'linuxDataplane': 'Iptables',
           'ipPools': [{'blockSize': 26, 'cidr': '10.245.0.0/16', 'encapsulation': 'VXLAN',
                        'natOutgoing': 'Enabled', 'nodeSelector': 'all()'}]}}})
    kube(['wait', '--for=condition=Ready', 'node', '--all', '--timeout=300s'])
    kube(['-n', 'calico-system', 'rollout', 'status', 'daemonset/calico-node', '--timeout=300s'])
    kube(['-n', 'kube-system', 'rollout', 'status', 'deployment/coredns', '--timeout=180s'])
    run(['helm', '--kube-context', CONTEXT, 'upgrade', '--install', 'eg',
         'oci://docker.io/envoyproxy/gateway-helm', '--version', 'v1.9.2', '-n', 'envoy-gateway-system',
         '--create-namespace', '--timeout', '300s', '--set', 'deployment.replicas=2',
         '--set-json', 'deployment.pod.affinity=' + json.dumps({'podAntiAffinity': {'requiredDuringSchedulingIgnoredDuringExecution': [
             {'labelSelector': {'matchLabels': {'control-plane': 'envoy-gateway'}}, 'topologyKey': 'kubernetes.io/hostname'}]}})])
    # Two worker slots cannot host a third controller during a surge rollout.
    kube(['-n', 'envoy-gateway-system', 'patch', 'deployment', 'envoy-gateway', '--type=merge', '-p',
          json.dumps({'spec': {'strategy': {'rollingUpdate': {'maxUnavailable': 1, 'maxSurge': 0}}}})])
    kube(['-n', 'envoy-gateway-system', 'rollout', 'status', 'deployment/envoy-gateway', '--timeout=180s'])
    apply({'apiVersion': 'gateway.networking.k8s.io/v1', 'kind': 'GatewayClass',
           'metadata': {'name': 'eg'}, 'spec': {'controllerName': 'gateway.envoyproxy.io/gatewayclass-controller'}})
    nodes = [node['metadata']['name'] for node in json.loads(kube(['get', 'nodes', '-o', 'json']))['items']]
    assert len(nodes) == 3 and all(name.startswith(NS + '-') for name in nodes)
    # Store replicas use the control-plane too, with explicit tolerations in stack.yaml.
    print('Generating private TLS and authenticated store credentials', flush=True)
    tls = directory / 'tls'
    tls.mkdir(mode=0o700, exist_ok=True)
    server_name = f'fluxgate-authz.{NS}.svc.cluster.local'
    if not (tls / 'client.crt').exists():
        for role in ('server', 'client'):
            run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-sha256',
                 '-keyout', str(tls / f'{role}-ca.key'), '-out', str(tls / f'{role}-ca.crt'),
                 '-subj', f'/CN=fluxgate-resilience-{role}-ca'], sensitive=True)
            subject = server_name if role == 'server' else 'fluxgate-resilience-gateway'
            run(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(tls / f'{role}.key'),
                 '-out', str(tls / f'{role}.csr'), '-subj', f'/CN={subject}'], sensitive=True)
            extension = f'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage={role}Auth\n'
            if role == 'server':
                extension += f'subjectAltName=DNS:{server_name}\n'
            (tls / f'{role}.ext').write_text(extension)
            run(['openssl', 'x509', '-req', '-in', str(tls / f'{role}.csr'), '-CA', str(tls / f'{role}-ca.crt'),
                 '-CAkey', str(tls / f'{role}-ca.key'), '-CAcreateserial', '-days', '2', '-sha256',
                 '-extfile', str(tls / f'{role}.ext'), '-out', str(tls / f'{role}.crt')], sensitive=True)
    for file in tls.iterdir():
        file.chmod(0o600)
    mongo_admin = private('mongo-admin-password')
    mongo_app = private('mongo-app-password')
    redis_password = private('redis-password')
    mongo_key = private('mongo-keyfile', __import__('base64').b64encode(secrets.token_bytes(512)).decode())
    api_keys = [(private('api-key'), 'resilience-key'), (private('ha-api-key'), 'resilience-ha-key'),
                (private('quota-api-key'), 'resilience-quota-key')]
    mappings = {'fluxgate': {'envoy': {'api-keys': [
        {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'user-id': 'resilience-user',
         'api-key-id': identity, 'attributes': {'tenant': 'resilience'}} for path, identity in api_keys]}}}
    api_mapping = private('application-credentials.yml', json.dumps(mappings))
    mongo_hosts = ','.join(f'mongo-{i}.mongo.{NS}.svc.cluster.local:27017' for i in range(3))
    options = '?replicaSet=rs0&authSource=admin&w=majority&wtimeoutMS=2000&connectTimeoutMS=2000&serverSelectionTimeoutMS=2000&socketTimeoutMS=2000&heartbeatFrequencyMS=2000&waitQueueTimeoutMS=2000&minPoolSize=4&maxConnecting=8'
    mongo_uri = private('mongo-uri', f'mongodb://fluxgate:{quote(mongo_app.read_text(), safe="")}@{mongo_hosts}/fluxgate' + options.replace('authSource=admin', 'authSource=fluxgate'))
    mongo_admin_uri = private('mongo-admin-uri', f'mongodb://admin:{quote(mongo_admin.read_text(), safe="")}@{mongo_hosts}/admin' + options)
    redis_uri = private('redis-uri', ','.join(f'redis://:{quote(redis_password.read_text(), safe="")}@redis-{i}-0.redis.{NS}.svc.cluster.local:6379' for i in range(3)))
    apply({'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': NS, 'labels': {'fluxgate.io/environment': 'local-ephemeral'}}})
    secret('fluxgate-mongo-admin', {'mongo-admin-password': mongo_admin, 'mongo-keyfile': mongo_key})
    secret('fluxgate-store-credentials', {'mongo-app-password': mongo_app, 'redis-password': redis_password,
           'fluxgate.mongo.uri': mongo_uri, 'fluxgate.redis.uri': redis_uri})
    secret('fluxgate-api-key-config', {'application-credentials.yml': api_mapping})
    secret('fluxgate-authz-server-tls', {'tls.crt': tls / 'server.crt', 'tls.key': tls / 'server.key', 'client-ca.crt': tls / 'client-ca.crt'})
    secret('fluxgate-envoy-client-tls', {'tls.crt': tls / 'client.crt', 'tls.key': tls / 'client.key'})
    apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'fluxgate-authz-server-ca', 'namespace': NS},
           'data': {'ca.crt': (tls / 'server-ca.crt').read_text()}})
    configuration = {'server': {'port': 8443, 'ssl': {'enabled': True, 'certificate': '/tls/tls.crt',
        'certificate-private-key': '/tls/tls.key', 'trust-certificate': '/tls/client-ca.crt', 'client-auth': 'need'}},
        'spring': {'config': {'import': 'optional:configtree:/credentials/stores/,file:/credentials/api/application-credentials.yml'}},
        'fluxgate': {'envoy': {'allow-insecure': False, 'health-port': 8081, 'published-policies': True,
        'gateway-certificate-subjects': ['CN=fluxgate-resilience-gateway'], 'trusted-proxies': ['10.245.0.0/16'],
        'header-allowlist': ['host', 'content-type'], 'bootstrap-file': '/bootstrap/policy.json',
        'routes': [{'id': 'resilience-api', 'path-prefix': '/api', 'rule-set-id': 'resilience-limits', 'permits': 1}]},
        'mongo': {'enabled': True, 'database': 'fluxgate', 'ddl-auto': 'create', 'rule-collection': 'rate_limit_rules', 'event-collection': 'rate_limit_events'},
        'redis': {'enabled': True, 'timeout-ms': 2000}, 'reload': {'enabled': False},
        'resilience': {'circuit-breaker': {'enabled': True, 'wait-duration-in-open-state': '5s'}},
        'ratelimit': {'mode': 'REDIS', 'filter-enabled': False, 'missing-rule-behavior': 'DENY',
                     'missing-key-behavior': 'REJECT', 'failure-behavior': 'DENY', 'fallback': {'mode': 'NONE'}}}}
    apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'fluxgate-resilience-config', 'namespace': NS},
           'data': {'application-resilience.yml': json.dumps(configuration)}})
    rules = []
    for rule_id, path, capacity, window in [('load-rule', '/api/load', 10000000, 3600), ('quota-rule', '/api/quota', 5, 86400)]:
        rules.append({'id': rule_id, 'name': rule_id, 'enabled': True, 'scope': 'PER_API_KEY', 'keyStrategyId': 'api-key',
                      'onLimitExceedPolicy': 'REJECT_REQUEST', 'ruleSetId': 'resilience-limits', 'priority': 10,
                      'pathPatterns': [path], 'bands': [{'windowSeconds': window, 'capacity': capacity, 'algorithm': 'FIXED_WINDOW' if rule_id == 'load-rule' else 'TOKEN_BUCKET', 'label': 'hourly' if window == 3600 else 'daily'}]})
    operation = private('bootstrap-operation-id', str(uuid.uuid4()))
    payload = {'ruleSetId': 'resilience-limits', 'operationId': operation.read_text(), 'rules': rules, 'accessControl': {}}
    apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'fluxgate-policy-bootstrap', 'namespace': NS},
           'data': {'policy.json': json.dumps(payload)}})
    print('Loading application image and starting persistent replicated stores', flush=True)
    run(['kind', 'load', 'docker-image', args.image, '--name', NS])
    stack = (HERE / 'stack.yaml').read_text().replace('fluxgate-envoy-extauth:enterprise', args.image)
    # kubectl parses the YAML template; Python only adjusts explicit Redis ordinals.
    import copy
    raw = kube(['create', '--dry-run=client', '--validate=strict', '-f', '-', '-o', 'json'], stdin=stack)
    items, decoder = [], json.JSONDecoder()
    while raw.strip():
        raw = raw.lstrip()
        resource, position = decoder.raw_decode(raw)
        items.append(resource)
        raw = raw[position:]
    rendered = {'apiVersion': 'v1', 'kind': 'List', 'items': items}
    template = next(r for r in items if r['kind'] == 'StatefulSet' and r['metadata']['name'] == 'redis')
    items.remove(template)
    for ordinal in range(9):
        resource = copy.deepcopy(template)
        resource['metadata']['name'] = f'redis-{ordinal}'
        resource['spec']['selector']['matchLabels']['redis-instance'] = str(ordinal)
        resource['spec']['template']['metadata']['labels']['redis-instance'] = str(ordinal)
        shard, generation = ordinal % 3, ordinal // 3
        resource['spec']['template']['spec'].pop('topologySpreadConstraints', None)
        resource['spec']['template']['spec']['nodeSelector'] = {'kubernetes.io/hostname': nodes[(shard + generation) % 3]}
        items.append(resource)
    rendered = json.dumps(rendered)
    kube(['apply', '--dry-run=server', '--validate=strict', '-f', '-'], stdin=rendered)
    kube(['apply', '-f', '-'], stdin=rendered)
    # Do not start app bootstrap before the authenticated replica-set quorum exists.
    kube(['-n', NS, 'scale', 'deployment/fluxgate-authz', '--replicas=0'])
    for store in ['mongo'] + [f'redis-{i}' for i in range(9)]:
        kube(['-n', NS, 'rollout', 'status', f'statefulset/{store}', '--timeout=600s'], timeout=650)
    members = [{'id': i, 'host': f'mongo-{i}.mongo.{NS}.svc.cluster.local:27017'} for i in range(3)]
    initiate = f'''let status;try {{status=admin.runCommand({{replSetGetStatus:1}});}} catch(e) {{if(e.code!==94)throw e;status={{ok:0}};}}
if(status.ok!==1) {{const r=admin.runCommand({{replSetInitiate:{{_id:'rs0',members:{json.dumps(members).replace('"id":', '"_id":')},settings:{json.dumps(MONGO_PRESET)}}}}});if(r.ok!==1)quit(2);}}
print('REPLICA_SET_INITIALIZED');'''
    wait('Mongo authentication/initiation', lambda: mongo('mongo-0', initiate))
    def mongo_ready():
        status = json.loads(mongo('mongo-0', "print(JSON.stringify(admin.runCommand({replSetGetStatus:1})));"))
        assert status.get('ok') == 1 and len(status.get('members', [])) == 3
        assert sum(m.get('state') == 1 for m in status['members']) == 1 and all(m.get('health') == 1 for m in status['members'])
        return status
    mongo_status = wait('Mongo three-member quorum', mongo_ready)
    apply_mongo_preset(kube, mongo, wait,
                       {'context': CONTEXT, 'namespace': NS, 'node_container_names': nodes}, run)
    primary = next(m['name'].split('.')[0] for m in mongo_status['members'] if m['state'] == 1)
    mongo(primary, '''const target=conn.getDB('fluxgate');if(!target.getUser('fluxgate'))target.createUser({user:'fluxgate',pwd:fs.readFileSync('/app-credentials/mongo-app-password','utf8').trim(),roles:[{role:'readWrite',db:'fluxgate'}],writeConcern:{w:'majority',wtimeout:2000}});print('APP_USER_READY');''')
    redis_pods = json.loads(kube(['-n', NS, 'get', 'pods', '-l', 'app=redis', '-o', 'json']))['items']
    by_name = {p['metadata']['name']: p for p in redis_pods}
    masters = [by_name[f'redis-{i}-0'] for i in range(3)]
    replicas = [by_name[f'redis-{i}-0'] for i in range(3, 9)]
    assert len(redis_pods) == 9 and len({p['spec']['nodeName'] for p in masters}) == 3
    if 'cluster_state:ok' not in redis('redis-0-0', ['CLUSTER', 'INFO']):
        redis(masters[0]['metadata']['name'], ['--cluster', 'create'] + [p['status']['podIP'] + ':6379' for p in masters] + ['--cluster-replicas', '0', '--cluster-yes'])
        for index, replica in enumerate(replicas):
            master = masters[index % 3]
            assert master['spec']['nodeName'] != replica['spec']['nodeName']
            target = master['status']['podIP']
            redis(replica['metadata']['name'], ['CLUSTER', 'MEET', target, '6379'])
            master_id = redis(master['metadata']['name'], ['CLUSTER', 'MYID']).strip()
            wait('Redis gossip before replica assignment', lambda: assert_member(redis(replica['metadata']['name'], ['CLUSTER', 'NODES']), master_id))
            redis(replica['metadata']['name'], ['CLUSTER', 'REPLICATE', master_id])
    def redis_ready():
        for pod in redis_pods:
            info = redis(pod['metadata']['name'], ['CLUSTER', 'INFO'])
            assert 'cluster_state:ok' in info and 'cluster_known_nodes:9' in info and 'cluster_slots_assigned:16384' in info
        return True
    wait('Redis all slots and nine members', redis_ready)
    def replicas_ready():
        for master in masters:
            assert 'connected_slaves:2' in redis(master['metadata']['name'], ['INFO', 'replication'])
        for replica in replicas:
            assert 'master_link_status:up' in redis(replica['metadata']['name'], ['INFO', 'replication'])
        return True
    wait('Redis two healthy replicas per primary', replicas_ready)
    if not args.stores_only:
        kube(['-n', NS, 'scale', 'deployment/fluxgate-authz', '--replicas=2'])
    for deployment in (['echo'] if args.stores_only else ['fluxgate-authz', 'echo']):
        kube(['-n', NS, 'rollout', 'status', 'deployment/' + deployment, '--timeout=300s'])
    owner = f'gateway.envoyproxy.io/owning-gateway-namespace={NS},gateway.envoyproxy.io/owning-gateway-name={NS}-gateway'
    def gateway_ready():
        resources = json.loads(kube(['-n', 'envoy-gateway-system', 'get', 'pods', '-l', owner, '-o', 'json']))['items']
        assert len(resources) == 2 and all(all(c.get('ready') for c in p['status'].get('containerStatuses', [])) and p['status'].get('containerStatuses') for p in resources)
        assert len({p['spec']['nodeName'] for p in resources}) == 2
        return resources
    wait('Two independently placed Envoy replicas', gateway_ready)
    def current_conditions(resource, conditions, required):
        return all(any(c['type'] == name and c['status'] == 'True' and c.get('observedGeneration') == resource['metadata']['generation'] for c in conditions) for name in required)
    def policies_ready():
        for kind, name, field, required in [('httproute', 'resilience-api', 'parents', ['Accepted', 'ResolvedRefs']),
                ('securitypolicy', 'resilience-ext-auth', 'ancestors', ['Accepted']),
                ('backendtlspolicy', 'authz-backend-tls', 'ancestors', ['Accepted', 'ResolvedRefs'])]:
            resource = json.loads(kube(['-n', NS, 'get', kind, name, '-o', 'json']))
            assert resource.get('status', {}).get(field)
            assert all(current_conditions(resource, entry['conditions'], required) for entry in resource['status'][field])
        gateway = json.loads(kube(['-n', NS, 'get', 'gateway', NS + '-gateway', '-o', 'json']))
        assert current_conditions(gateway, gateway['status']['conditions'], ['Accepted'])
        # Kind has no external LoadBalancer address; listener Programmed is the valid xDS condition.
        assert gateway['status'].get('listeners') and all(current_conditions(gateway, l['conditions'], ['Accepted', 'ResolvedRefs', 'Programmed']) for l in gateway['status']['listeners'])
        controllers = json.loads(kube(['-n', 'envoy-gateway-system', 'get', 'pods', '-l', 'control-plane=envoy-gateway', '-o', 'json']))['items']
        assert len(controllers) == 2 and len({p['spec']['nodeName'] for p in controllers}) == 2
        assert all(any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status']['conditions']) for p in controllers)
        lease = json.loads(kube(['-n', 'envoy-gateway-system', 'get', 'lease', '5b9825d2.gateway.envoyproxy.io', '-o', 'json']))
        assert lease['spec']['holderIdentity'].split('_', 1)[0] in {p['metadata']['name'] for p in controllers}
        return True
    wait('Current policy generations and two controller replicas', policies_ready)
    service = json.loads(kube(['-n', 'envoy-gateway-system', 'get', 'services', '-l', owner, '-o', 'json']))['items'][0]['metadata']['name']
    metadata = {'context': CONTEXT, 'namespace': NS, 'kubeconfig': env['KUBECONFIG'], 'gateway_service': service,
        'gateway_namespace': 'envoy-gateway-system', 'gateway_host': 'resilience.local', 'load_path': '/api/load', 'quota_path': '/api/quota',
        'api_key_file': str(api_keys[0][0]), 'ha_api_key_file': str(api_keys[1][0]), 'ha_api_key_id': 'resilience-ha-key', 'api_key_id': 'resilience-key', 'quota_api_key_file': str(api_keys[2][0]),
        'api_key_mapping_file': str(api_mapping), 'mongo_uri_file': str(mongo_uri), 'mongo_admin_uri_file': str(mongo_admin_uri),
        'mongo_admin_password_file': str(mongo_admin), 'mongo_app_password_file': str(mongo_app),
        'redis_password_file': str(redis_password), 'redis_uri_file': str(redis_uri), 'tls_dir': str(tls), 'server_name': server_name,
        'node_container_names': nodes, 'rule_set_id': 'resilience-limits', 'load_rule_id': 'load-rule', 'quota_rule_id': 'quota-rule',
        'authz_deployment': 'fluxgate-authz', 'authz_service': 'fluxgate-authz', 'authz_tls_secret': 'fluxgate-authz-server-tls',
        'envoy_client_tls_secret': 'fluxgate-envoy-client-tls', 'server_ca_configmap': 'fluxgate-authz-server-ca',
        'credentials_secret': 'fluxgate-store-credentials', 'admin_credentials_secret': 'fluxgate-mongo-admin',
        'api_keys_secret': 'fluxgate-api-key-config', 'api_keys_filename': 'application-credentials.yml',
        'application_configmap': 'fluxgate-resilience-config', 'application_filename': 'application-resilience.yml',
        'mongo_statefulset': 'mongo', 'redis_statefulsets': ['redis-' + str(i) for i in range(9)], 'mongo_app_user': 'fluxgate', 'mongo_admin_user': 'admin',
        'mongo_pods': ['mongo-' + str(i) for i in range(3)], 'redis_pods': ['redis-' + str(i) + '-0' for i in range(9)],
        'mongo_selector': 'app=mongo', 'redis_selector': 'app=redis', 'backend_body': 'fluxgate-resilience-ok',
        'application_jar_file': str(HERE.parents[2] / 'fluxgate-envoy-gateway/fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.3.7.jar'), 'image': args.image, 'bootstrap_operation_id': operation.read_text()}
    private('fixture.json', json.dumps(metadata, indent=2) + '\n')
    # Refresh metadata only: credentials, bootstrap operation and counters stay unchanged on rerun.
    (directory / 'fixture.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (directory / 'placement.json').write_text(json.dumps({'mongo': mongo_status['members'], 'redis': [
        {'master': m['metadata']['name'], 'master_node': m['spec']['nodeName'], 'replica': r['metadata']['name'], 'replica_node': r['spec']['nodeName']}
        for i, r in enumerate(replicas) for m in [masters[i % 3]]]}, indent=2) + '\n')
    print(json.dumps({'result': 'stores-ready' if args.stores_only else 'ready', 'fixture': str(directory / 'fixture.json'), 'context': CONTEXT, 'namespace': NS}, indent=2), flush=True)


def assert_member(nodes, member_id):
    assert member_id in nodes
    return True


if __name__ == '__main__':
    main()
