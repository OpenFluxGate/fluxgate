#!/usr/bin/env python3
"""Exercise the built MongoPolicyRepository over the fixture's real replica set.

Prepare while healthy before each experiment. Invoke after verified primary loss
or loss of write quorum. Credentials go through kubectl stdin, never argv/output.
This helper changes only policy metadata and preserves the counter epoch.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import uuid
import zipfile


SOURCE = r'''
import com.mongodb.MongoException;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.bson.Document;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;

public final class PublicationProbe {
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
      var rules = baseline.getList("rules", Document.class);
      rules.get(0).put("name", "Resilience metadata proof " + operation);
      var failures = new ArrayList<String>();
      long start = System.nanoTime();
      long deadline = start + input.getInteger("deadline") * 1_000_000_000L;
      while (true) {
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
          Document evidence = new Document("passed", true).append("published", true)
              .append("operationId", operation)
              .append("beforeRevision", expected).append("revision", result.get("revision"))
              .append("counterEpoch", epoch).append("snapshotId", result.get("snapshotId"))
              .append("checksum", result.get("checksum")).append("epochPreserved", true)
              .append("majorityReadVerified", true).append("idempotentReplay", true)
              .append("elapsedSeconds", (System.nanoTime() - start) / 1e9)
              .append("transientFailureClasses", failures);
          System.out.println("PROOF:" + evidence.toJson());
          return;
        } catch (MongoException failure) {
          if (failure instanceof com.mongodb.MongoSecurityException
              || failure.getCode() == 13 || failure.getCode() == 18) throw failure;
          // Class only: messages can include connection details or credentials.
          failures.add(failure.getClass().getSimpleName());
          if (System.nanoTime() >= deadline) {
            if (!input.getString("expect").equals("rejected")) throw failure;
            System.out.println("PROOF:" + new Document("passed", true)
                .append("operationId", operation).append("beforeRevision", expected)
                .append("counterEpoch", epoch)
                .append("published", false).append("attempts", failures.size())
                .append("availabilityFailureClasses", failures)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--expect', choices=('success', 'rejected'), default='success')
    parser.add_argument('--deadline', type=int, default=30)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--jar', type=Path, default=Path(__file__).resolve().parents[2] /
                        'fluxgate-envoy-extauth/target/fluxgate-envoy-extauth-0.3.7.jar')
    args = parser.parse_args()
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
    if args.prepare:
        deployment = json.loads(checked(kube + ['get', 'deployment/' + fixture.get(
            'authz_deployment', 'fluxgate-authz'), '-o', 'json']))
        selector = ','.join(k + '=' + v for k, v in deployment['spec']['selector']['matchLabels'].items())
        pods = json.loads(checked(kube + ['get', 'pods', '-l', selector, '-o', 'json']))['items']
        pod = next(p['metadata']['name'] for p in pods if not p['metadata'].get('deletionTimestamp') and
                   any(c['type'] == 'Ready' and c['status'] == 'True'
                       for c in p.get('status', {}).get('conditions', [])))
        remote = '/tmp/publication-proof-' + uuid.uuid4().hex
        with tempfile.TemporaryDirectory(prefix='publication-compiler-', dir=fixture_path.parent) as tmp:
            directory = Path(tmp)
            with zipfile.ZipFile(args.jar) as jar:
                for name in jar.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        (directory / Path(name).name).write_bytes(jar.read(name))
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
        state = {'pod': pod, 'remote': remote}
    else:
        state = json.loads(state_path.read_text())
    uri_path = Path(fixture['mongo_uri_file'])
    if uri_path.stat().st_mode & 0o077:
        raise RuntimeError('private Mongo URI file required')
    payload = dict(uri=uri_path.read_text().strip(), prepare=args.prepare,
                   ruleSetId=fixture.get('rule_set_id', 'resilience-limits'),
                   expect=args.expect, deadline=args.deadline,
                   operationId='resilience-' + uuid.uuid4().hex)
    if not args.prepare:
        payload['baseline'] = state['baseline']
    output = checked(kube + ['exec', '-i', state['pod'], '--', 'java', '-cp',
                            state['remote'] + '/*', 'PublicationProbe'],
                     json.dumps(payload).encode(), timeout=args.deadline + 45)
    records = [json.loads(line.removeprefix('PROOF:')) for line in output.decode().splitlines()
               if line.startswith('PROOF:')]
    if len(records) != 1:
        raise RuntimeError('missing unique Java publication proof')
    if args.prepare:
        state['baseline'] = records[0]
        state_path.write_text(json.dumps(state))
        state_path.chmod(0o600)
        print('Publication helper prepared on an existing authz process; epoch preserved')
    else:
        if args.output is None:
            raise RuntimeError('--output required')
        args.output.write_text(json.dumps(records[0], indent=2) + '\n')
        args.output.chmod(0o600)
        print('Publication proof:', json.dumps(records[0]))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Publication proof failed:', type(error).__name__)
        raise SystemExit(1)
