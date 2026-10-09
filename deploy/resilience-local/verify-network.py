#!/usr/bin/env python3
"""Opt-in local NetworkPolicy proof with an unprivileged, untrusted probe.

Baseline egress permits DNS and only the exact Gateway workload/port. A second
phase grants only this probe egress to protected destination workloads/ports,
so their ingress restrictions are tested independently. Runtime policies are
never edited. TCP refusal is inconclusive, not evidence of a policy block.
Credentials enter the worker through kubectl stdin, never command arguments.
Run --self-check without a cluster; serialize live --fixture with other proofs.
"""
import argparse
import http.client
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid

NS = 'fluxgate-resilience'
CONTEXT = 'kind-fluxgate-resilience'
RUN_LABEL = 'fluxgate.io/network-run'
OWNER = {'gateway.envoyproxy.io/owning-gateway-name': NS + '-gateway',
         'gateway.envoyproxy.io/owning-gateway-namespace': NS}
TIMEOUT = 2


def validate_fixture(fixture):
    if (fixture.get('context') != CONTEXT or fixture.get('namespace') != NS
            or fixture.get('gateway_namespace') != 'envoy-gateway-system'):
        raise ValueError('requires the exact isolated resilience fixture')


def blocked(sample):
    return sample.get('connected') is False and sample.get('error') == 'TimeoutError'


def backend_body_matches(body):
    # http-echo 1.0.0 uses fmt.Fprintln: the one trailing newline is part of the body.
    return body == b'fluxgate-resilience-ok\n'


def probe_pod(name, image):
    return {'apiVersion': 'v1', 'kind': 'Pod',
            'metadata': {'name': name, 'namespace': NS, 'labels': {
                'app': 'fluxgate-untrusted-network-probe', RUN_LABEL: name,
                'fluxgate.io/environment': 'local-ephemeral'}},
            'spec': {'restartPolicy': 'Never', 'automountServiceAccountToken': False,
                     'terminationGracePeriodSeconds': 1,
                     'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001,
                                         'runAsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
                     'containers': [{'name': 'probe', 'image': image,
                                     'command': ['python3', '-c', 'import time; time.sleep(1800)'],
                                     'securityContext': {'allowPrivilegeEscalation': False,
                                                         'readOnlyRootFilesystem': True,
                                                         'capabilities': {'drop': ['ALL']}},
                                     'resources': {'requests': {'cpu': '10m', 'memory': '32Mi'},
                                                   'limits': {'cpu': '500m', 'memory': '128Mi'}}}]}}


def egress_policy(name, gateway_selector, gateway_port, destination_ingress=False):
    rules = [{'to': [{'namespaceSelector': {'matchLabels': {
        'kubernetes.io/metadata.name': 'kube-system'}},
        'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
        'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]},
        {'to': [{'namespaceSelector': {'matchLabels': {
            'kubernetes.io/metadata.name': 'envoy-gateway-system'}},
            'podSelector': {'matchLabels': gateway_selector}}],
         'ports': [{'protocol': 'TCP', 'port': gateway_port}]}]
    if destination_ingress:
        for app, ports in [('echo', [5678]), ('fluxgate-authz', [8443, 8081]),
                           ('mongo', [27017]), ('redis', [6379])]:
            rules.append({'to': [{'podSelector': {'matchLabels': {'app': app}}}],
                          'ports': [{'protocol': 'TCP', 'port': p} for p in ports]})
    return {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
            'metadata': {'name': name, 'namespace': NS, 'labels': {RUN_LABEL: name}},
            'spec': {'podSelector': {'matchLabels': {RUN_LABEL: name}},
                     'policyTypes': ['Egress'], 'egress': rules}}


def tcp(host, port):
    start = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=TIMEOUT):
            sample = {'connected': True, 'error': None}
    except OSError as error:
        sample = {'connected': False, 'error': type(error).__name__}
    return dict(sample, elapsed_seconds=round(time.monotonic() - start, 3))


def worker(config):
    # Only structured evidence leaves this process; exception messages can echo headers.
    dns = []
    for host in config['dns_hosts']:
        try:
            addresses = sorted({a[4][0] for a in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)})
            dns.append({'host': host, 'resolved': bool(addresses), 'addresses': addresses})
        except OSError as error:
            dns.append({'host': host, 'resolved': False, 'error': type(error).__name__})
    gateway = {'passed': False}
    connection = http.client.HTTPConnection(config['gateway_ip'], config['gateway_port'], timeout=5)
    try:
        connection.request('GET', config['path'], headers={
            'Host': config['host'], 'X-API-Key': config['api_key']})
        response = connection.getresponse()
        body = response.read(4096)
        gateway = {'status': response.status, 'exact_backend_body': backend_body_matches(body),
                   'passed': response.status == 200 and backend_body_matches(body)}
    except (OSError, http.client.HTTPException) as error:
        gateway = {'passed': False, 'error': type(error).__name__}
    finally:
        connection.close()
    cases = []
    for target in config['targets']:
        result = tcp(target['host'], target['port'])
        cases.append(dict(target, **result, passed=blocked(result)))
    return {'phase': config['phase'], 'dns': dns, 'gateway': gateway, 'cases': cases,
            'passed': all(d['resolved'] for d in dns) and gateway['passed']
                      and bool(cases) and all(c['passed'] for c in cases)}


def live(args):
    fixture_path = args.fixture.resolve()
    if fixture_path.is_dir():
        fixture_path /= 'fixture.json'
    if fixture_path.stat().st_mode & 0o077:
        raise ValueError('fixture must be private')
    fixture = json.loads(fixture_path.read_text())
    validate_fixture(fixture)
    if Path(fixture['kubeconfig']).stat().st_mode & 0o077:
        raise ValueError('kubeconfig must be private')
    key_path = Path(fixture['api_key_file']).resolve()
    if key_path.stat().st_mode & 0o077:
        raise ValueError('API key file must be private')
    api_key = key_path.read_text().strip()
    if not api_key or any(c in api_key for c in '\r\n'):
        raise ValueError('invalid private API key')
    base = ['kubectl', '--kubeconfig', fixture['kubeconfig'], '--context', CONTEXT]

    def kube(arguments, stdin=None, timeout=30):
        result = subprocess.run(base + arguments, input=stdin, text=True, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('kubectl operation failed (private diagnostics suppressed)')
        return result.stdout

    def get(kind, name=None, namespace=NS, selector=None):
        arguments = ['get', kind] + ([name] if name else []) + ['-n', namespace, '-o', 'json']
        if selector:
            arguments += ['-l', selector]
        return json.loads(kube(arguments))

    namespace = get('namespace', NS)
    if namespace['metadata'].get('labels', {}).get('fluxgate.io/environment') != 'local-ephemeral':
        raise ValueError('isolated namespace label missing')
    nodes = json.loads(kube(['get', 'nodes', '-o', 'json']))['items']
    if (len(nodes) != 3 or any(not n['metadata']['name'].startswith(NS + '-') for n in nodes)
            or set(fixture['node_container_names']) != {n['metadata']['name'] for n in nodes}):
        raise ValueError('requires exact three-node local fixture')
    policy = get('networkpolicy', 'default-deny')['spec']
    if policy.get('podSelector') != {} or set(policy.get('policyTypes', [])) != {'Ingress', 'Egress'}:
        raise ValueError('namespace default deny missing')
    if policy.get('ingress') or policy.get('egress'):
        raise ValueError('namespace default deny is not empty')
    before_policies = get('networkpolicy')['items']
    before_stamp = {p['metadata']['name']: p['metadata']['resourceVersion'] for p in before_policies}
    gateway_service = get('service', fixture['gateway_service'], fixture['gateway_namespace'])
    selector = gateway_service['spec'].get('selector', {})
    if any(selector.get(k) != v for k, v in OWNER.items()):
        raise ValueError('Gateway workload identity mismatch')
    ports = [p for p in gateway_service['spec']['ports'] if p['port'] == 80 and p.get('protocol', 'TCP') == 'TCP']
    if len(ports) != 1 or not isinstance(ports[0].get('targetPort'), int):
        raise ValueError('requires Gateway HTTP listener with numeric target port')
    gateway_ip = str(ipaddress.ip_address(gateway_service['spec']['clusterIP']))
    gateway_pods = get('pods', namespace=fixture['gateway_namespace'],
                       selector=','.join(k + '=' + v for k, v in selector.items()))['items']
    if not gateway_pods or not all(p['status'].get('podIP') for p in gateway_pods):
        raise ValueError('Gateway endpoints missing')
    pods = {}
    for app in ('echo', 'fluxgate-authz', 'mongo', 'redis'):
        pods[app] = get('pods', selector='app=' + app)['items']
        if not pods[app] or not all(p['status'].get('podIP') and any(
                c['type'] == 'Ready' and c['status'] == 'True'
                for c in p['status'].get('conditions', [])) for p in pods[app]):
            raise ValueError('protected workloads must be ready before testing')
    if len(pods['mongo']) != 3 or len(pods['redis']) != 9:
        raise ValueError('requires all three Mongo and nine Redis members')
    service_targets, dns_hosts = [], []
    for app, name, port in [('echo', 'echo', 5678), ('fluxgate-authz', fixture['authz_service'], 8443),
                            ('mongo', 'mongo', 27017), ('redis', 'redis', 6379)]:
        service = get('service', name)
        if service['spec'].get('selector', {}).get('app') != app:
            raise ValueError('protected Service identity mismatch')
        if not any(p['port'] == port for p in service['spec']['ports']):
            raise ValueError('protected Service listener mismatch')
        host = name + '.' + NS + '.svc.cluster.local'
        dns_hosts.append(host)
        service_targets.append({'name': app + '-service', 'host': host, 'port': port, 'kind': 'service'})
    pod_targets = []
    for app, listeners in [('echo', [5678]), ('fluxgate-authz', [8443, 8081]),
                           ('mongo', [27017]), ('redis', [6379])]:
        for pod in pods[app]:
            for port in listeners:
                pod_targets.append({'name': pod['metadata']['name'] + ':' + str(port),
                                    'host': pod['status']['podIP'], 'port': port, 'kind': 'pod'})
    positive = []
    # Use the actual authorized source Pods, not a new Pod impersonating their labels.
    for pod in pods['fluxgate-authz']:
        targets = [dict(t) for t in pod_targets if t['port'] in (27017, 6379)]
        targets += [{'name': 'local-authz-listener:' + str(p), 'host': '127.0.0.1', 'port': p}
                    for p in (8443, 8081)]
        for target in targets:
            result = subprocess.run(base + ['exec', '-n', NS, pod['metadata']['name'], '--',
                'timeout', '3', 'bash', '-c', 'exec 3<>/dev/tcp/$1/$2', 'tcp-control',
                target['host'], str(target['port'])], capture_output=True, text=True, timeout=8)
            positive.append({'source': pod['metadata']['name'], 'target': target['name'],
                             'port': target['port'], 'passed': result.returncode == 0})
    name = 'fluxgate-network-' + uuid.uuid4().hex[:12]
    report = {'context': CONTEXT, 'namespace': NS, 'probe_pod': name,
              'positive_controls': positive, 'gateway_target_port': ports[0]['targetPort'],
              'backend_listener_port': 5678, 'phases': [], 'runtime_policies_unchanged': False}
    if not all(c['passed'] for c in positive):
        return dict(report, passed=False, inconclusive=True,
                    reason='authorized TCP/listener positive control failed; negatives not attempted',
                    cleanup_verified=True, probe_resources_created=False)
    try:
        kube(['create', '-f', '-'], json.dumps(egress_policy(name, selector, ports[0]['targetPort'])))
        kube(['create', '-f', '-'], json.dumps(probe_pod(name, args.image)))
        kube(['wait', '-n', NS, '--for=condition=Ready', 'pod/' + name, '--timeout=180s'], timeout=190)
        observed = get('pod', name)
        if (observed['spec'].get('automountServiceAccountToken') is not False
                or any(v.get('projected') for v in observed['spec'].get('volumes', []))):
            raise RuntimeError('probe unexpectedly has projected credentials')
        config = {'gateway_ip': gateway_ip, 'gateway_port': 80, 'host': fixture['gateway_host'],
                  'path': fixture['load_path'], 'api_key': api_key,
                  'dns_hosts': dns_hosts + [fixture['gateway_service'] + '.' + fixture['gateway_namespace']
                                             + '.svc.cluster.local']}
        for phase, targets in [('default-deny-egress', service_targets + [
                t for t in pod_targets if t['port'] == 8081]),
                                ('destination-ingress', service_targets + pod_targets)]:
            if phase == 'destination-ingress':
                replacement = egress_policy(name, selector, ports[0]['targetPort'], True)
                replacement['metadata']['resourceVersion'] = get('networkpolicy', name)['metadata']['resourceVersion']
                kube(['replace', '-f', '-'], json.dumps(replacement))
            # A successful Gateway request is also the end-to-end mTLS authz/backend positive control.
            result = kube(['exec', '-i', '-n', NS, name, '--', 'python3', '-c',
                           Path(__file__).read_text(), '--worker'],
                          json.dumps(dict(config, phase=phase, targets=targets)), timeout=180)
            if api_key in result:
                raise RuntimeError('credential in probe output; evidence suppressed')
            report['phases'].append(json.loads(result))
        after_stamp = {p['metadata']['name']: p['metadata']['resourceVersion']
                       for p in get('networkpolicy')['items'] if p['metadata']['name'] != name}
        report['runtime_policies_unchanged'] = before_stamp == after_stamp
        report['passed'] = (all(p['passed'] for p in report['phases'])
                            and report['runtime_policies_unchanged'])
        return report
    finally:
        failures = []
        for kind in ('pod', 'networkpolicy'):
            try:
                raw = kube(['get', kind, name, '-n', NS, '--ignore-not-found=true', '-o', 'json'])
                if not raw.strip():
                    continue
                if json.loads(raw)['metadata'].get('labels', {}).get(RUN_LABEL) != name:
                    raise RuntimeError('cleanup ownership mismatch')
                kube(['delete', kind, name, '-n', NS, '--ignore-not-found=true',
                      '--wait=true', '--timeout=60s'], timeout=70)
            except Exception:
                failures.append(kind)
        if failures:
            raise RuntimeError('probe-owned cleanup failed: ' + ','.join(failures))
        report['cleanup_verified'] = True


def self_check():
    assert backend_body_matches(b'fluxgate-resilience-ok\n')
    assert not backend_body_matches(b'fluxgate-resilience-ok')
    assert not backend_body_matches(b'fluxgate-resilience-ok\n\n')
    assert blocked({'connected': False, 'error': 'TimeoutError'})
    assert not blocked({'connected': False, 'error': 'ConnectionRefusedError'})
    assert not blocked({'connected': True, 'error': None})
    pod = probe_pod('network-self-check', 'python:3.12-alpine')
    assert not pod['spec']['automountServiceAccountToken']
    assert pod['spec']['securityContext']['runAsNonRoot']
    policy = egress_policy('network-self-check', OWNER, 10080)
    assert {p['port'] for r in policy['spec']['egress'] for p in r['ports']} == {53, 10080}
    scoped = egress_policy('network-self-check', OWNER, 10080, True)
    assert scoped['spec']['podSelector'] == {'matchLabels': {RUN_LABEL: 'network-self-check'}}
    assert all(r.get('to') for r in scoped['spec']['egress'])
    try:
        validate_fixture({'context': 'production', 'namespace': NS})
    except ValueError:
        pass
    else:
        raise AssertionError('unsafe fixture accepted')
    print('Network control self-check passed; no cluster accessed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--image', default='python:3.12-alpine')
    parser.add_argument('--self-check', action='store_true')
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    os.umask(0o077)
    if args.worker:
        print(json.dumps(worker(json.load(sys.stdin))))
    elif args.self_check:
        self_check()
    else:
        if args.fixture is None or args.output is None:
            parser.error('--fixture and --output are required for live verification')
        report = live(args)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        args.output.chmod(0o600)
        print('Network proof:', 'PASS' if report['passed'] else 'FAIL')
        if not report['passed']:
            raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Network proof failed:', type(error).__name__, '(private diagnostics suppressed)', file=sys.stderr)
        raise SystemExit(1)
