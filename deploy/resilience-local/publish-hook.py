#!/usr/bin/env python3
"""Exercise the built MongoPolicyRepository over the fixture's real replica set.

Prepare while healthy before each experiment. Invoke after verified primary loss
or loss of write quorum. Credentials go through kubectl stdin, never argv/output.
This helper changes only policy metadata and preserves the counter epoch.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import uuid
import zipfile


# Use the actual packaged repository and its runtime dependencies. Uploading the
# entire Spring application classpath during a measured fault run changes the
# experiment's workload and can leave a blocked kubectl upload behind.
PROBE_LIBRARIES = ('fluxgate-core-', 'fluxgate-mongo-adapter-',
                   'bucket4j_jdk11-core-', 'slf4j-api-', 'mongodb-driver-sync-',
                   'mongodb-driver-core-', 'bson-', 'bson-record-codec-')


def extract_probe_libraries(jar_path, directory):
    selected = []
    with zipfile.ZipFile(jar_path) as jar:
        names = [name for name in jar.namelist()
                 if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
        for prefix in PROBE_LIBRARIES:
            matches = [name for name in names if Path(name).name.startswith(prefix)
                       and (prefix != 'bson-' or not Path(name).name.startswith('bson-record-codec-'))]
            if len(matches) != 1:
                raise RuntimeError('missing or ambiguous packaged probe dependency: ' + prefix)
            name = matches[0]
            (directory / Path(name).name).write_bytes(jar.read(name))
            selected.append(Path(name).name)
    return selected


SOURCE = r'''
import com.mongodb.MongoException;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import org.bson.Document;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;

public final class PublicationProbe {
  static Document attemptEvidence(int attempt, long wallStart, long nanoStart, MongoException failure) {
    long wallEnd = System.currentTimeMillis();
    var frames = new ArrayList<Document>();
    if (failure != null) {
      for (StackTraceElement frame : failure.getStackTrace()) {
        // Exact packaged repository class, including its generated inner classes only.
        String name = frame.getClassName();
        if (name.equals("org.fluxgate.adapter.mongo.policy.MongoPolicyRepository")
            || name.startsWith("org.fluxgate.adapter.mongo.policy.MongoPolicyRepository$")) {
          frames.add(new Document("class", name).append("method", frame.getMethodName())
              .append("line", frame.getLineNumber()));
        }
      }
    }
    return new Document("attempt", attempt)
        .append("outcome", failure == null ? "success" : "mongo-failure")
        .append("startedAtUtc", Instant.ofEpochMilli(wallStart).toString())
        .append("startedAtUnixMs", wallStart)
        .append("finishedAtUtc", Instant.ofEpochMilli(wallEnd).toString())
        .append("finishedAtUnixMs", wallEnd)
        .append("elapsedSeconds", (System.nanoTime() - nanoStart) / 1e9)
        .append("fluxGateFrames", frames);
  }

  public static void main(String[] args) throws Exception {
    Document input = Document.parse(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
    String id = input.getString("ruleSetId");
    try (var client = MongoClients.create(input.getString("uri"))) {
      var repository = new MongoPolicyRepository(client.getDatabase("fluxgate"), "rate_limit_rules");
      if (input.getBoolean("prepare", false)) {
        System.out.println("PROOF:" + repository.findActive(id).orElseThrow().toJson());
        return;
      }
      Document baseline = input.get("baseline", Document.class);
      long expected = ((Number) baseline.get("revision")).longValue();
      String epoch = baseline.getString("counterEpoch");
      String operation = input.getString("operationId");
      if (input.getBoolean("resolveRejected", false)) {
        // A last-committed read alone can precede an ambiguous write's commit.
        // Majority-ack a later, harmless oplog entry before inspecting the pointer.
        var database = client.getDatabase("fluxgate");
        var barriers = database.getCollection("fluxgate_resilience_commit_barriers")
            .withWriteConcern(database.getWriteConcern().withW("majority"));
        barriers.replaceOne(com.mongodb.client.model.Filters.eq("_id", operation),
            new Document("_id", operation).append("actor", "local-resilience-proof"),
            new com.mongodb.client.model.ReplaceOptions().upsert(true));
        Document active = repository.findActive(id).orElseThrow();
        if (!active.getString("snapshotId").equals(baseline.getString("snapshotId"))
            || ((Number) active.get("revision")).longValue() != expected
            || !active.getString("counterEpoch").equals(epoch)
            || !active.getString("checksum").equals(baseline.getString("checksum")))
          throw new IllegalStateException("ambiguous attempt changed the majority active policy");
        System.out.println("PROOF:" + new Document("passed", true).append("published", false)
            .append("operationId", operation).append("revision", expected)
            .append("counterEpoch", epoch).append("snapshotId", active.getString("snapshotId"))
            .append("commitStatus", "not-published-after-majority-recovery")
            .append("majorityCommitBarrier", true)
            .append("majorityReadVerified", true).toJson());
        return;
      }
      var rules = baseline.getList("rules", Document.class);
      rules.get(0).put("name", "Resilience metadata proof " + operation);
      var failures = new ArrayList<String>();
      var attemptTimings = new ArrayList<Document>();
      long start = System.nanoTime();
      long deadline = start + input.getInteger("deadline") * 1_000_000_000L;
      while (true) {
        long attemptWallStart = System.currentTimeMillis();
        long attemptNanoStart = System.nanoTime();
        try {
          Document result = repository.publish(id, expected, rules,
              baseline.get("accessControl", Document.class), false, null, operation,
              "local-resilience-proof");
          if (input.getString("expect").equals("rejected"))
            throw new IllegalStateException("publication accepted without required quorum");
          Document active = repository.findActive(id).orElseThrow();
          Document replay = repository.publish(id, expected, rules,
              baseline.get("accessControl", Document.class), false, null, operation,
              "local-resilience-proof");
          if (!epoch.equals(result.getString("counterEpoch"))
              || ((Number) result.get("revision")).longValue() != expected + 1
              || !active.getString("snapshotId").equals(result.getString("snapshotId"))
              || !active.getString("checksum").equals(result.getString("checksum"))
              || !replay.getString("snapshotId").equals(result.getString("snapshotId")))
            throw new IllegalStateException("publication coherence or idempotency failed");
          if (System.nanoTime() > deadline)
            throw new IllegalStateException("successful publication exceeded recovery deadline");
          attemptTimings.add(attemptEvidence(attemptTimings.size() + 1,
              attemptWallStart, attemptNanoStart, null));
          Document evidence = new Document("passed", true).append("published", true)
              .append("operationId", operation)
              .append("beforeRevision", expected).append("revision", result.get("revision"))
              .append("counterEpoch", epoch).append("snapshotId", result.get("snapshotId"))
              .append("checksum", result.get("checksum")).append("epochPreserved", true)
              .append("majorityReadVerified", true).append("idempotentReplay", true)
              .append("elapsedSeconds", (System.nanoTime() - start) / 1e9)
              .append("transientFailureClasses", failures)
              .append("attemptTimings", attemptTimings);
          System.out.println("PROOF:" + evidence.toJson());
          return;
        } catch (MongoException failure) {
          if (failure instanceof com.mongodb.MongoSecurityException
              || failure.getCode() == 13 || failure.getCode() == 18) throw failure;
          // Class only: messages can include connection details or credentials.
          failures.add(failure.getClass().getSimpleName());
          attemptTimings.add(attemptEvidence(attemptTimings.size() + 1,
              attemptWallStart, attemptNanoStart, failure));
          if (System.nanoTime() >= deadline) {
            if (!input.getString("expect").equals("rejected")) throw failure;
            System.out.println("PROOF:" + new Document("passed", true)
                .append("operationId", operation).append("beforeRevision", expected)
                .append("counterEpoch", epoch)
                .append("published", null).append("commitStatus", "unknown")
                .append("requiresRecoveryResolution", true).append("attempts", failures.size())
                .append("availabilityFailureClasses", failures)
                .append("attemptTimings", attemptTimings)
                .append("elapsedSeconds", (System.nanoTime() - start) / 1e9).toJson());
            return;
          }
          Thread.sleep(250);
        }
      }
    }
  }
}
'''


def checked(args, data=None, timeout=120):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(Path(args[0]).name + " failed (private diagnostics suppressed)")
    return result.stdout


def save_state(path, state):
    path.write_text(json.dumps(state))
    path.chmod(0o600)


def cleanup_probe(kube, state_path):
    if not state_path.exists():
        return {'passed': True, 'pod_absent': True, 'network_policy_absent': True, 'no_op': True}
    if state_path.stat().st_mode & 0o077:
        raise RuntimeError('publication state permissions must be private')
    state = json.loads(state_path.read_text())
    # Legacy state names an application Pod. Never delete a service Pod.
    if not state.get('probe_owned'):
        return {'passed': True, 'pod_absent': True, 'network_policy_absent': True, 'no_op': True}
    name = state['pod']
    if not name.startswith('fluxgate-publication-') or len(name) != len('fluxgate-publication-') + 32:
        raise RuntimeError('invalid owned publication probe identity')
    failures, cleaned = [], []
    for resource in state.get('owned_resources', []):
        kind, resource_name = resource['kind'], resource['name']
        try:
            if (kind not in ('pod', 'networkpolicy') or
                    resource_name not in (name, name + '-egress', name + '-ingress')):
                raise RuntimeError('unexpected publication resource')
            data = checked(kube + ['get', kind, resource_name, '--ignore-not-found=true', '-o', 'json',
                                  '--request-timeout=5s'], timeout=5)
            uid = resource.get('uid')
            if data.strip():
                current = json.loads(data)
                if (current['metadata'].get('labels', {}).get('fluxgate.io/publication-probe') != name
                        or (uid and current['metadata']['uid'] != uid)):
                    raise RuntimeError('publication cleanup ownership or UID mismatch')
                uid = current['metadata']['uid']
                grace = ['--grace-period=3'] if kind == 'pod' else []
                checked(kube + ['delete', kind, resource_name, '--wait=true', '--timeout=10s',
                                '--request-timeout=10s'] + grace, timeout=15)
            remaining = checked(kube + ['get', kind, resource_name, '--ignore-not-found=true', '-o', 'json',
                                       '--request-timeout=5s'], timeout=5)
            if remaining.strip():
                raise RuntimeError('publication resource remains after deletion')
            cleaned.append({'kind': kind, 'name': resource_name, 'uid': uid, 'absent': True})
        except Exception:
            failures.append(kind)
    state['cleanup_resources'] = cleaned
    state['cleanup_complete'] = not failures
    save_state(state_path, state)
    if failures:
        raise RuntimeError('publication cleanup failed: ' + ','.join(failures))
    return {'passed': True, 'pod_absent': True, 'network_policy_absent': True,
            'resources': cleaned}


def create_probe(kube, fixture, state, state_path):
    name, labels = state['pod'], {'fluxgate.io/publication-probe': state['pod']}
    resources = [
        {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
         'metadata': {'name': name + '-egress', 'labels': labels},
         'spec': {'podSelector': {'matchLabels': labels}, 'policyTypes': ['Ingress', 'Egress'],
                  'ingress': [], 'egress': [
                      {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                               'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
                       'ports': [{'protocol': p, 'port': 53} for p in ('UDP', 'TCP')]},
                      {'to': [{'podSelector': {'matchLabels': {'app': 'mongo'}}}],
                       'ports': [{'protocol': 'TCP', 'port': 27017}]}]}},
        {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
         'metadata': {'name': name + '-ingress', 'labels': labels},
         'spec': {'podSelector': {'matchLabels': {'app': 'mongo'}}, 'policyTypes': ['Ingress'],
                  'ingress': [{'from': [{'podSelector': {'matchLabels': labels}}],
                               'ports': [{'protocol': 'TCP', 'port': 27017}]}]}},
        {'apiVersion': 'v1', 'kind': 'Pod',
         'metadata': {'name': name, 'labels': {**labels, 'app': 'fluxgate-publication-probe'}},
         'spec': {'automountServiceAccountToken': False, 'restartPolicy': 'Never', 'terminationGracePeriodSeconds': 3,
                  'nodeName': 'fluxgate-resilience-control-plane',
                  'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001},
                  'containers': [{'name': 'publisher', 'image': fixture['image'], 'imagePullPolicy': 'Never',
                                  'command': ['/bin/sh', '-c', 'while :; do sleep 300; done'],
                                  'env': [{'name': 'JAVA_TOOL_OPTIONS',
                                           'value': '-Xmx64m -XX:ActiveProcessorCount=1'}],
                                  'resources': {'requests': {'cpu': '100m', 'memory': '96Mi'},
                                                'limits': {'cpu': '1', 'memory': '192Mi'}},
                                  'securityContext': {'allowPrivilegeEscalation': False,
                                                      'capabilities': {'drop': ['ALL']}}}]}}
    ]
    for resource in resources:
        entry = {'kind': resource['kind'].lower(), 'name': resource['metadata']['name'], 'uid': None}
        state['owned_resources'].append(entry)
        save_state(state_path, state)  # Even an ambiguous create has an owned name/label for cleanup.
        result = json.loads(checked(kube + ['create', '-f', '-', '-o', 'json'], json.dumps(resource).encode()))
        entry['uid'] = result['metadata']['uid']
        save_state(state_path, state)
    checked(kube + ['wait', '--for=condition=Ready', 'pod/' + name, '--timeout=60s'], timeout=70)
    state['pod_uid'] = state['owned_resources'][-1]['uid']
    deployed_sha = checked(kube + ['exec', name, '--', 'sha256sum', '/app/app.jar']).decode().split()[0]
    if deployed_sha != state['jar_sha256']:
        raise RuntimeError('publication probe artifact differs from deployed service')
    # The service selector must never route authorization requests to this tool.
    for service in json.loads(checked(kube + ['get', 'services', '-o', 'json']))['items']:
        selector = service['spec'].get('selector', {})
        if selector and all(resources[-1]['metadata']['labels'].get(k) == v for k, v in selector.items()):
            raise RuntimeError('publication probe selected by a service')
    save_state(state_path, state)


def check_packaged_diagnostics(jar_path):
    """Compile the deployed probe and exercise its evidence without a Mongo connection."""
    test_source = r'''
import com.mongodb.MongoException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.bson.Document;

final class PublicationDiagnosticsSelfCheck {
  public static void main(String[] args) {
    String privateMarker = "SECRET_URI_PASSWORD_CERT_ARGS";
    var failure = new MongoException(privateMarker);
    failure.initCause(new IllegalStateException(privateMarker));
    failure.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("com.mongodb.driver.Socket", "connect", privateMarker, 900),
        new StackTraceElement("org.fluxgate.adapter.mongo.policy.MongoPolicyRepository",
            "ensurePublicationIndexes", privateMarker, 55),
        new StackTraceElement("org.fluxgate.adapter.mongo.policy.MongoPolicyRepository$Nested",
            "publish", privateMarker, 144),
        new StackTraceElement("org.fluxgate.adapter.mongo.policy.Untrusted", privateMarker,
            privateMarker, 7)
    });
    long wallStart = System.currentTimeMillis();
    long nanoStart = System.nanoTime() - 1_000_000_000L;
    Document failed = PublicationProbe.attemptEvidence(1, wallStart, nanoStart, failure);
    Document success = PublicationProbe.attemptEvidence(2, wallStart, nanoStart, null);
    Set<String> schema = Set.of("attempt", "outcome", "startedAtUtc", "startedAtUnixMs",
        "finishedAtUtc", "finishedAtUnixMs", "elapsedSeconds", "fluxGateFrames");
    for (Document evidence : List.of(failed, success)) {
      if (!evidence.keySet().equals(schema)
          || evidence.getLong("startedAtUnixMs") != wallStart
          || Instant.parse(evidence.getString("startedAtUtc")).toEpochMilli() != wallStart
          || Instant.parse(evidence.getString("finishedAtUtc")).toEpochMilli()
              != evidence.getLong("finishedAtUnixMs")
          || evidence.getDouble("elapsedSeconds") < 1.0
          || evidence.toJson().contains(privateMarker))
        throw new AssertionError("attempt timing schema or privacy failure");
    }
    var frames = failed.getList("fluxGateFrames", Document.class);
    if (failed.getInteger("attempt") != 1 || !failed.getString("outcome").equals("mongo-failure")
        || frames.size() != 2
        || !frames.get(0).equals(new Document("class",
            "org.fluxgate.adapter.mongo.policy.MongoPolicyRepository")
            .append("method", "ensurePublicationIndexes").append("line", 55))
        || !frames.get(1).getString("class").endsWith("$Nested")
        || frames.stream().anyMatch(frame -> !frame.keySet().equals(Set.of("class", "method", "line")))
        || success.getInteger("attempt") != 2 || !success.getString("outcome").equals("success")
        || !success.getList("fluxGateFrames", Document.class).isEmpty())
      throw new AssertionError("stack whitelist or successful attempt evidence failure");
    failure.setStackTrace(new StackTraceElement[0]);
    if (!PublicationProbe.attemptEvidence(3, wallStart, nanoStart, failure)
        .getList("fluxGateFrames", Document.class).isEmpty())
      throw new AssertionError("empty stack must remain empty");
    System.out.println("PASS");
  }
}
'''
    with tempfile.TemporaryDirectory(prefix='publication-diagnostics-unit-') as tmp:
        directory = Path(tmp)
        extract_probe_libraries(jar_path, directory)
        probe = directory / 'PublicationProbe.java'
        test = directory / 'PublicationDiagnosticsSelfCheck.java'
        probe.write_text(SOURCE)
        test.write_text(test_source)
        java_bin = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
        compiler = str(java_bin / 'javac') if java_bin else 'javac'
        runtime = str(java_bin / 'java') if java_bin else 'java'
        checked([compiler, '--release', '17', '-cp', str(directory / '*'),
                 '-d', str(directory), str(probe), str(test)])
        output = checked([runtime, '-cp', str(directory) + os.pathsep + str(directory / '*'),
                          'PublicationDiagnosticsSelfCheck'])
        if output.strip() != b'PASS':
            raise RuntimeError('packaged diagnostic self-check failed')


def self_check(jar_path=None):
    from unittest.mock import patch
    checks = 0
    with tempfile.TemporaryDirectory(prefix='publication-probe-unit-') as tmp:
        directory = Path(tmp)
        for fault in (None, 'missing', 'duplicate'):
            jar = directory / 'fixture.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for index, prefix in enumerate(PROBE_LIBRARIES):
                    if fault == 'missing' and index == 0:
                        continue
                    archive.writestr('BOOT-INF/lib/' + prefix + '1.jar', b'fixture')
                    if fault == 'duplicate' and index == 0:
                        archive.writestr('BOOT-INF/lib/' + prefix + '2.jar', b'fixture')
                archive.writestr('BOOT-INF/lib/unneeded-1.jar', b'not selected')
            target = directory / ('extract-' + str(fault))
            target.mkdir()
            try:
                libraries = extract_probe_libraries(jar, target)
                assert fault is None and len(libraries) == 8 and not (target / 'unneeded-1.jar').exists()
            except RuntimeError:
                assert fault is not None
            checks += 1
        for fault in (None, 'uid', 'delete', 'legacy'):
            name = 'fluxgate-publication-' + 'a' * 32
            state = {'probe_owned': fault != 'legacy', 'pod': name,
                     'owned_resources': [{'kind': 'pod', 'name': name, 'uid': 'owned'},
                                         {'kind': 'networkpolicy', 'name': name + '-egress', 'uid': 'owned'}]}
            path = directory / 'state.json'
            save_state(path, state)
            deleted = []
            def fake_checked(args, *unused, **keywords):
                action, kind, resource_name = args[:3]
                if action == 'delete':
                    deleted.append(kind)
                    if fault == 'delete' and kind == 'pod':
                        raise RuntimeError('offline deletion failure')
                    return b''
                if kind in deleted:
                    return b''
                return json.dumps({'metadata': {'uid': 'foreign' if fault == 'uid' and kind == 'pod' else 'owned',
                                    'labels': {'fluxgate.io/publication-probe': name}}}).encode()
            with patch.dict(globals(), checked=fake_checked):
                try:
                    result = cleanup_probe([], path)
                    assert fault in (None, 'legacy') and result['passed']
                except RuntimeError:
                    assert fault in ('uid', 'delete')
            if fault in ('uid', 'delete'):
                assert 'networkpolicy' in deleted, 'one failed resource must not skip other cleanup'
            if fault in ('uid', 'legacy'):
                assert 'pod' not in deleted, 'never delete a foreign or application Pod'
            checks += 1
    if jar_path is not None and jar_path.is_file():
        check_packaged_diagnostics(jar_path)
        checks += 1
    print(json.dumps({'result': 'PASS', 'self_checks': checks,
                      'packaged_diagnostics_checked': jar_path is not None and jar_path.is_file()}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--self-check', action='store_true')
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--cleanup', action='store_true', help='remove only owned probe resources and verify absence')
    parser.add_argument('--resolve-rejected', action='store_true',
                        help='after recovery, prove the same ambiguous operation never became active')
    parser.add_argument('--expect', choices=('success', 'rejected'), default='success')
    parser.add_argument('--deadline', type=int, default=30)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--jar', type=Path, default=Path(__file__).resolve().parents[2] /
                        'fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.4.0-SNAPSHOT.jar')
    args = parser.parse_args()
    if args.self_check:
        if not __debug__:
            parser.error('Python optimization disables proof assertions')
        self_check(args.jar)
        return
    if args.fixture is None:
        parser.error('--fixture required for live publication proof')
    if args.prepare and args.resolve_rejected:
        parser.error('--prepare and --resolve-rejected are mutually exclusive')
    if not 1 <= args.deadline <= 30:
        parser.error('--deadline must be 1..30 seconds')
    if not args.prepare and not args.cleanup and args.output is None:
        parser.error('--output required before any publication attempt')
    os.umask(0o077)
    fixture_path = args.fixture / 'fixture.json' if args.fixture.is_dir() else args.fixture
    fixture = json.loads(fixture_path.read_text())
    if (fixture['context'] != 'kind-fluxgate-resilience' or
            fixture['namespace'] != 'fluxgate-resilience' or
            fixture_path.stat().st_mode & 0o077):
        raise RuntimeError('private isolated fixture required')
    kube = ['kubectl', '--kubeconfig', fixture['kubeconfig'], '--context', fixture['context'],
            '-n', fixture['namespace']]
    namespace = json.loads(checked(kube + ['get', 'namespace/' + fixture['namespace'], '-o', 'json']))
    if namespace['metadata']['labels'].get('fluxgate.io/environment') != 'local-ephemeral':
        raise RuntimeError('isolated namespace guard missing')
    state_path = fixture_path.parent / 'publication-hook-state.json'
    if args.cleanup:
        if args.prepare or args.resolve_rejected:
            parser.error('--cleanup cannot prepare or publish')
        print(json.dumps(cleanup_probe(kube, state_path)))
        return
    if args.prepare:
        cleanup_probe(kube, state_path)
        deployment = json.loads(checked(kube + ['get', 'deployment/' + fixture.get(
            'authz_deployment', 'fluxgate-authz'), '-o', 'json']))
        selector = ','.join(k + '=' + v for k, v in deployment['spec']['selector']['matchLabels'].items())
        pods = json.loads(checked(kube + ['get', 'pods', '-l', selector, '-o', 'json']))['items']
        selected_pod = next(p for p in pods if not p['metadata'].get('deletionTimestamp') and
                            any(c['type'] == 'Ready' and c['status'] == 'True'
                                for c in p.get('status', {}).get('conditions', [])))
        source_pod = selected_pod['metadata']['name']
        jar_sha = hashlib.sha256(args.jar.read_bytes()).hexdigest()
        deployed_sha = checked(kube + ['exec', source_pod, '--', 'sha256sum', '/app/app.jar']).decode().split()[0]
        if deployed_sha != jar_sha:
            raise RuntimeError('publication helper artifact differs from deployed application')
        pod = 'fluxgate-publication-' + uuid.uuid4().hex
        state = {'pod': pod, 'probe_owned': True, 'owned_resources': [],
                 'source_authz_pod': source_pod, 'source_authz_pod_uid': selected_pod['metadata']['uid'],
                 'jar_sha256': jar_sha, 'ruleSetId': fixture.get('rule_set_id', 'resilience-limits'),
                 'operationId': 'resilience-' + uuid.uuid4().hex}
        save_state(state_path, state)
        create_probe(kube, fixture, state, state_path)
        remote = '/tmp/publication-proof-' + uuid.uuid4().hex
        with tempfile.TemporaryDirectory(prefix='publication-compiler-', dir=fixture_path.parent) as tmp:
            directory = Path(tmp)
            libraries = extract_probe_libraries(args.jar, directory)
            (directory / 'PublicationProbe.java').write_text(SOURCE)
            compiler = str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
            checked([compiler, '--release', '17', '-cp', str(directory / '*'),
                     '-d', str(directory), str(directory / 'PublicationProbe.java')])
            archive = io.BytesIO()
            with tarfile.open(fileobj=archive, mode='w:gz') as tar:
                for path in directory.iterdir():
                    if path.suffix in ('.jar', '.class'):
                        tar.add(path, arcname=path.name)
            checked(kube + ['exec', pod, '--', 'mkdir', '-p', remote])
            checked(kube + ['exec', '-i', pod, '--', 'tar', 'xz', '-C', remote], archive.getvalue())
        state.update(remote=remote, probe_libraries=libraries, archive_bytes=len(archive.getvalue()),
                     archive_sha256=hashlib.sha256(archive.getvalue()).hexdigest())
        save_state(state_path, state)
    else:
        state = json.loads(state_path.read_text())
    uri_path = Path(fixture['mongo_uri_file'])
    if uri_path.stat().st_mode & 0o077:
        raise RuntimeError('private Mongo URI file required')
    payload = dict(uri=uri_path.read_text().strip(), prepare=args.prepare,
                   resolveRejected=args.resolve_rejected,
                   ruleSetId=fixture.get('rule_set_id', 'resilience-limits'),
                   expect=args.expect, deadline=args.deadline,
                   operationId=state['operationId'])
    if not args.prepare:
        payload['baseline'] = state['baseline']
    output = checked(kube + ['exec', '-i', state['pod'], '--', 'java', '-cp',
                            state['remote'] + ':' + state['remote'] + '/*', 'PublicationProbe'],
                     json.dumps(payload).encode(), timeout=args.deadline + 45)
    records = [json.loads(line.removeprefix('PROOF:')) for line in output.decode().splitlines()
               if line.startswith('PROOF:')]
    if len(records) != 1:
        raise RuntimeError('missing unique Java publication proof')
    if args.prepare:
        current_pod = json.loads(checked(kube + ['get', 'pod/' + state['pod'], '-o', 'json']))
        if (current_pod['metadata']['uid'] != state['pod_uid']
                or current_pod['metadata'].get('deletionTimestamp')
                or not any(c['type'] == 'Ready' and c['status'] == 'True'
                           for c in current_pod.get('status', {}).get('conditions', []))):
            raise RuntimeError('publication helper Pod changed or became unready during preparation')
        state['baseline'] = records[0]
        save_state(state_path, state)
        print('Publication helper prepared in its own bounded Pod; artifact and epoch preserved')
    else:
        args.output.write_text(json.dumps(records[0], indent=2) + '\n')
        args.output.chmod(0o600)
        print('Publication proof:', json.dumps(records[0]))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Publication proof failed:', type(error).__name__)
        raise SystemExit(1)
