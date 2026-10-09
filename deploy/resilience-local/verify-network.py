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


def require_same_identity(before, after):
    if before != after:
        raise ValueError('captured target identity/address changed; negative proof is inconclusive')


def controls_pass(required, controls):
    return (bool(required) and len(controls) == len(required)
            and {c['id'] for c in controls} == set(required)
            and all(c['passed'] for c in controls))


def phase_passes(required, before, negative, after, gateway_after):
    return (controls_pass(required, before) and negative['passed']
            and controls_pass(required, after) and gateway_after['passed'])


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
                      and (bool(cases) or config.get('gateway_only', False))
                      and all(c['passed'] for c in cases)}


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
    service_targets, dns_hosts, service_records, discovered_services = [], [], [], {}
    for app, service_name, port in [('echo', 'echo', 5678), ('fluxgate-authz', fixture['authz_service'], 8443),
                                   ('mongo', 'mongo', 27017), ('redis', 'redis', 6379)]:
        service = get('service', service_name)
        if service['spec'].get('selector', {}).get('app') != app:
            raise ValueError('protected Service identity mismatch')
        if not any(p['port'] == port for p in service['spec']['ports']):
            raise ValueError('protected Service listener mismatch')
        dns_hosts.append(service_name + '.' + NS + '.svc.cluster.local')
        service_records.append((NS, service_name))
        discovered_services['service:' + NS + '/' + service_name] = {
            'uid': service['metadata']['uid'], 'ip': service['spec']['clusterIP'],
            'selector': service['spec'].get('selector'), 'ports': service['spec']['ports']}
        cluster_ip = service['spec']['clusterIP']
        addresses = ([cluster_ip] if cluster_ip != 'None' else [
            p['status']['podIP'] for p in pods[app] if all(
                p['metadata']['labels'].get(k) == v for k, v in service['spec']['selector'].items())])
        if not addresses:
            raise ValueError('captured headless Service has no endpoints')
        for address in addresses:
            service_targets.append({'name': app + '-service:' + address, 'app': app,
                                    'host': str(ipaddress.ip_address(address)), 'port': port,
                                    'kind': 'service' if cluster_ip != 'None' else 'headless-service-endpoint'})
    pod_targets = []
    for app, listeners in [('echo', [5678]), ('fluxgate-authz', [8443, 8081]),
                           ('mongo', [27017]), ('redis', [6379])]:
        for pod in pods[app]:
            for port in listeners:
                pod_targets.append({'name': pod['metadata']['name'] + ':' + str(port), 'app': app,
                                    'host': pod['status']['podIP'], 'port': port, 'kind': 'pod'})
    service_records.append((fixture['gateway_namespace'], fixture['gateway_service']))
    discovered_services['service:' + fixture['gateway_namespace'] + '/' + fixture['gateway_service']] = {
        'uid': gateway_service['metadata']['uid'], 'ip': gateway_service['spec']['clusterIP'],
        'selector': gateway_service['spec'].get('selector'), 'ports': gateway_service['spec']['ports']}

    def identities():
        captured = {}
        groups = [(NS, 'app=' + app) for app in pods]
        groups.append((fixture['gateway_namespace'], ','.join(k + '=' + v for k, v in selector.items())))
        for source_namespace, source_selector in groups:
            for pod in get('pods', namespace=source_namespace, selector=source_selector)['items']:
                if not any(c['type'] == 'Ready' and c['status'] == 'True'
                           for c in pod['status'].get('conditions', [])):
                    raise ValueError('captured workload not Ready; negatives are inconclusive')
                captured['pod:' + source_namespace + '/' + pod['metadata']['name']] = {
                    'uid': pod['metadata']['uid'], 'ip': pod['status'].get('podIP')}
        for service_namespace, service_name in service_records:
            service = get('service', service_name, service_namespace)
            captured['service:' + service_namespace + '/' + service_name] = {
                'uid': service['metadata']['uid'], 'ip': service['spec']['clusterIP'],
                'selector': service['spec'].get('selector'), 'ports': service['spec']['ports']}
        return captured

    baseline_identity = identities()
    for identity_name, discovered in discovered_services.items():
        require_same_identity(discovered, baseline_identity[identity_name])
    # Bind all negative addresses to the original discovery, not a later replacement.
    for app_pods in list(pods.values()) + [gateway_pods]:
        for pod in app_pods:
            identity = baseline_identity['pod:' + pod['metadata']['namespace'] + '/' + pod['metadata']['name']]
            require_same_identity({'uid': pod['metadata']['uid'], 'ip': pod['status']['podIP']}, identity)
    name = 'fluxgate-network-' + uuid.uuid4().hex[:12]
    control_name = name + '-control'
    control_namespace = fixture['gateway_namespace']
    control = probe_pod(control_name, args.image)
    control['metadata']['namespace'] = control_namespace
    control['metadata']['labels']['app'] = 'fluxgate-network-policy-control'
    control['metadata']['labels'].update(OWNER)
    if all(control['metadata']['labels'].get(k) == v for k, v in selector.items()):
        raise ValueError('control Pod would be selected by the actual Gateway Service')
    control_policy = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
        'metadata': {'name': control_name, 'namespace': control_namespace, 'labels': {RUN_LABEL: control_name}},
        'spec': {'podSelector': {'matchLabels': {RUN_LABEL: control_name}},
                 'policyTypes': ['Ingress', 'Egress'], 'ingress': [], 'egress': [
                     {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': NS}},
                              'podSelector': {'matchLabels': {'app': app}}}],
                      'ports': [{'protocol': 'TCP', 'port': port}]}
                     for app, port in [('echo', 5678), ('fluxgate-authz', 8443)]]}}
    jobs = []
    for source in pods['fluxgate-authz']:
        targets = [t for t in service_targets + pod_targets if t['app'] in ('mongo', 'redis')]
        targets += [{'name': 'local-authz-listener:' + str(p), 'host': '127.0.0.1', 'port': p}
                    for p in (8443, 8081)]
        for target in targets:
            jobs.append({'id': source['metadata']['name'] + '->' + target['name'],
                         'source': source['metadata']['name'], 'namespace': NS,
                         'host': target['host'], 'port': target['port'], 'tool': 'bash'})
    for target in service_targets + pod_targets:
        if target['app'] in ('echo', 'fluxgate-authz') and target['port'] != 8081:
            jobs.append({'id': control_name + '->' + target['name'], 'source': control_name,
                         'namespace': control_namespace, 'host': target['host'],
                         'port': target['port'], 'tool': 'python'})
    required = [job['id'] for job in jobs]

    def positive_controls():
        samples = []
        for job in jobs:
            if job['tool'] == 'bash':
                result = subprocess.run(base + ['exec', '-n', job['namespace'], job['source'], '--',
                    'timeout', '3', 'bash', '-c', 'exec 3<>/dev/tcp/$1/$2', 'tcp-control',
                    job['host'], str(job['port'])], capture_output=True, text=True, timeout=8)
                samples.append(dict(job, passed=result.returncode == 0))
        python_jobs = [job for job in jobs if job['tool'] == 'python']
        source = "import json,socket,sys; j=json.load(sys.stdin); out=[]\nfor x in j:\n try:\n  c=socket.create_connection((x['host'],x['port']),2); c.close(); x['passed']=True\n except OSError as e: x['passed']=False; x['error']=type(e).__name__\n out.append(x)\nprint(json.dumps(out))"
        result = kube(['exec', '-i', '-n', control_namespace, control_name, '--',
                       'python3', '-c', source], json.dumps(python_jobs), timeout=40)
        samples.extend(json.loads(result))
        return samples

    owned = [(NS, name, 'pod'), (NS, name, 'networkpolicy'),
             (control_namespace, control_name, 'pod'), (control_namespace, control_name, 'networkpolicy')]

    def runtime_policy_stamp():
        return {source_namespace + '/' + p['metadata']['name']: p['metadata']['resourceVersion']
                for source_namespace in (NS, control_namespace)
                for p in get('networkpolicy', namespace=source_namespace)['items']
                if (source_namespace, p['metadata']['name'], 'networkpolicy') not in owned}

    before_stamp = runtime_policy_stamp()
    report = {'context': CONTEXT, 'namespace': NS, 'probe_pod': name,
              'gateway_policy_identity_control_pod': control_name,
              'control_not_selected_by_gateway_service': True,
              'control_scope': 'Operator-created Gateway-namespace policy-identity TCP control; not an actual Envoy client. Requires operator namespace access; does not prove an untrusted caller can create Pods there.',
              'captured_identity': baseline_identity, 'expected_positive_control_ids': required,
              'gateway_target_port': ports[0]['targetPort'], 'backend_listener_port': 5678,
              'phases': [], 'runtime_policies_unchanged': False}
    try:
        for policy in (egress_policy(name, selector, ports[0]['targetPort']), control_policy):
            kube(['create', '-f', '-'], json.dumps(policy))
        for pod in (probe_pod(name, args.image), control):
            kube(['create', '-f', '-'], json.dumps(pod))
            source_namespace = pod['metadata']['namespace']
            kube(['wait', '-n', source_namespace, '--for=condition=Ready',
                  'pod/' + pod['metadata']['name'], '--timeout=180s'], timeout=190)
            observed = get('pod', pod['metadata']['name'], source_namespace)
            if (observed['spec'].get('automountServiceAccountToken') is not False
                    or any(v.get('projected') for v in observed['spec'].get('volumes', []))):
                raise RuntimeError('probe unexpectedly has projected credentials')
        config = {'gateway_ip': gateway_ip, 'gateway_port': 80, 'host': fixture['gateway_host'],
                  'path': fixture['load_path'], 'api_key': api_key,
                  'dns_hosts': dns_hosts + [fixture['gateway_service'] + '.' + fixture['gateway_namespace']
                                             + '.svc.cluster.local']}

        def run_worker(payload):
            result = kube(['exec', '-i', '-n', NS, name, '--', 'python3', '-c',
                           Path(__file__).read_text(), '--worker'], json.dumps(payload), timeout=180)
            if api_key in result:
                raise RuntimeError('credential in probe output; evidence suppressed')
            return json.loads(result)

        for phase, targets in [('default-deny-egress', service_targets + [
                t for t in pod_targets if t['port'] == 8081]),
                ('destination-ingress', service_targets + pod_targets)]:
            record = {'phase': phase, 'passed': False}
            report['phases'].append(record)
            try:
                require_same_identity(baseline_identity, identities())
                record['identity_verified_before'] = True
                record['positive_controls_before'] = positive_controls()
                if not controls_pass(required, record['positive_controls_before']):
                    record['inconclusive_reason'] = 'target positive control failed before negatives'
                    break
                if phase == 'destination-ingress':
                    replacement = egress_policy(name, selector, ports[0]['targetPort'], True)
                    replacement['metadata']['resourceVersion'] = get('networkpolicy', name)['metadata']['resourceVersion']
                    kube(['replace', '-f', '-'], json.dumps(replacement))
                record['negative_probe'] = run_worker(dict(config, phase=phase, targets=targets))
                record['positive_controls_after'] = positive_controls()
                require_same_identity(baseline_identity, identities())
                record['identity_verified_after'] = True
                record['gateway_after'] = run_worker(dict(config, phase=phase + '-gateway-after',
                                                         targets=[], gateway_only=True))
                require_same_identity(baseline_identity, identities())
                record['identity_verified_at_phase_end'] = True
                record['runtime_policies_unchanged'] = before_stamp == runtime_policy_stamp()
                record['passed'] = (record['runtime_policies_unchanged'] and phase_passes(
                    required, record['positive_controls_before'], record['negative_probe'],
                    record['positive_controls_after'], record['gateway_after']))
            except ValueError:
                record['inconclusive_reason'] = 'captured workload readiness, UID, IP or Service identity changed'
                break
        report['runtime_policies_unchanged'] = before_stamp == runtime_policy_stamp()
        report['passed'] = (len(report['phases']) == 2 and all(p['passed'] for p in report['phases'])
                            and report['runtime_policies_unchanged'])
        return report
    finally:
        failures = []
        for source_namespace, owned_name, kind in owned:
            try:
                raw = kube(['get', kind, owned_name, '-n', source_namespace,
                            '--ignore-not-found=true', '-o', 'json'])
                if not raw.strip():
                    continue
                if json.loads(raw)['metadata'].get('labels', {}).get(RUN_LABEL) != owned_name:
                    raise RuntimeError('cleanup ownership mismatch')
                kube(['delete', kind, owned_name, '-n', source_namespace, '--ignore-not-found=true',
                      '--wait=true', '--timeout=60s'], timeout=70)
                if kube(['get', kind, owned_name, '-n', source_namespace,
                         '--ignore-not-found=true', '-o', 'json']).strip():
                    raise RuntimeError('cleanup did not remove owned resource')
            except Exception:
                failures.append(kind)
        if failures:
            raise RuntimeError('probe-owned cleanup failed: ' + ','.join(failures))
        report['cleanup_verified'] = True


def self_check():
    good = [{'id': 'source->target', 'passed': True}]
    assert not phase_passes(['source->target'], good, {'passed': True},
                            [{'id': 'source->target', 'passed': False}], {'passed': True})
    assert not phase_passes(['source->target'], good, {'passed': True}, [], {'passed': True})
    try:
        require_same_identity({'uid': 'old', 'ip': '10.0.0.1'}, {'uid': 'new', 'ip': '10.0.0.1'})
    except ValueError:
        pass
    else:
        raise AssertionError('replacement target accepted')
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
