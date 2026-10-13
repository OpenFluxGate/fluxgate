#!/usr/bin/env python3
"""Append a fresh isolated HA or quota identity without changing policies or existing counters."""
import argparse
import binascii
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--purpose', choices=('ha', 'quota'), default='ha')
    parser.add_argument('--self-check', action='store_true')
    args = parser.parse_args(argv)
    if not args.self_check and not (args.fixture and args.output):
        parser.error('--fixture and --output are required')
    return args


def prepare(args, proof, module):
    fixture = proof.fixture_path
    mapping = Path(proof.f['api_key_mapping_file'])
    original_fixture, original_mapping = fixture.read_bytes(), mapping.read_bytes()
    before = proof.policy_stamp()
    existing = json.loads(original_mapping)['fluxgate']['envoy']['api-keys']
    load_identity = proof.f.get('api_key_id', 'resilience-key')
    def slot(identity, rule):
        # These are the exact Redis hash tags used by the fixture's engine.
        tag = 'resilience-limits:' + rule + ':key:' + identity
        return binascii.crc_hqx(tag.encode(), 0) & 16383
    load_slot = slot(load_identity, 'load-rule')
    while True:
        identity = 'resilience-' + args.purpose + '-rerun-' + secrets.token_hex(8)
        candidate_slot = slot(identity, 'quota-rule')
        # Fixed setup slot ranges: 0-5460, 5461-10922, 10923-16383.
        if ((load_slot <= 5460 and candidate_slot <= 5460)
                or (5461 <= load_slot <= 10922 and 5461 <= candidate_slot <= 10922)
                or (10923 <= load_slot and 10923 <= candidate_slot)):
            break
    key = secrets.token_urlsafe(40)
    key_file = fixture.parent / (identity + '.key')
    try:
        addition = {'sha256': hashlib.sha256(key.encode()).hexdigest(), 'user-id': identity,
                    'api-key-id': identity, 'attributes': {'tenant': 'resilience'}}
        content = proof.api_mapping(existing + [addition])
        module.require(proof.policy_stamp() == before, 'fresh identity changed published policy')
        module.private_write(key_file, key + '\n')
        module.private_write(mapping, content)
        identity_field = args.purpose + '_api_key_id'
        previous_identity = proof.f.get(identity_field)
        proof.f.update({args.purpose + '_api_key_file': str(key_file), identity_field: identity})
        module.private_write(fixture, json.dumps(proof.f, indent=2) + '\n')
        result = {'passed': True, 'purpose': args.purpose, 'previous_identity': previous_identity, 'identity': identity,
                  'quota_slot': candidate_slot, 'load_slot': load_slot,
                  'existing_mapping_entries_preserved': len(existing),
                  'policy_pointer_unchanged': True, 'existing_counters_deleted_or_reset': False}
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        proof.backups.clear()
        return result
    except Exception:
        # A failed local restore must not prevent the independent remote Secret rollback.
        rollback_failures = []
        for action in (lambda: module.private_write(fixture, original_fixture),
                       lambda: module.private_write(mapping, original_mapping),
                       proof.cleanup_failed,
                       lambda: key_file.unlink(missing_ok=True)):
            try:
                action()
            except Exception:
                rollback_failures.append(True)
        if rollback_failures:
            raise RuntimeError('Preparation failed and at least one independent rollback failed') from None
        raise


def self_check():
    import tempfile
    from types import SimpleNamespace
    assert parse_args(['--fixture', 'private/fixture.json', '--output', 'proof.json']).purpose == 'ha'
    assert parse_args(['--fixture', 'private/fixture.json', '--output', 'proof.json', '--purpose', 'quota']).purpose == 'quota'
    checks = 2
    for purpose, fail_output in [('ha', False), ('quota', False), ('ha', True), ('quota', True)]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, mapping = root / 'fixture.json', root / 'mapping.json'
            original_entries = [{'sha256': 'old-hash', 'api-key-id': 'old-id'}]
            original_f = {'api_key_mapping_file': str(mapping), 'api_key_id': 'load-id',
                          'ha_api_key_file': 'old-ha.key', 'ha_api_key_id': 'old-ha',
                          'quota_api_key_file': 'old-quota.key', 'quota_api_key_id': 'old-quota'}
            fixture.write_text(json.dumps(original_f))
            mapping.write_text(json.dumps({'fluxgate': {'envoy': {'api-keys': original_entries}}}))
            originals = (fixture.read_bytes(), mapping.read_bytes())
            class FakeProof:
                fixture_path = fixture
                f = dict(original_f)
                backups = {}
                restored = False
                remote_entries = list(original_entries)
                counters = {'existing': 2}
                def policy_stamp(self):
                    return ('snapshot', 3, 'epoch')
                def api_mapping(self, entries):
                    self.backups['secret'] = list(self.remote_entries)
                    self.remote_entries = entries
                    return json.dumps({'fluxgate': {'envoy': {'api-keys': entries}}})
                def cleanup_failed(self):
                    self.remote_entries = self.backups['secret']
                    self.restored = True
            def private_write(path, value):
                Path(path).write_bytes(value if isinstance(value, bytes) else value.encode())
                Path(path).chmod(0o600)
            def require(value, message):
                if not value:
                    raise ValueError(message)
            proof = FakeProof()
            output = root / 'output.json'
            if fail_output:
                output.mkdir()
            args = SimpleNamespace(purpose=purpose, output=output)
            try:
                result = prepare(args, proof, SimpleNamespace(private_write=private_write, require=require))
            except IsADirectoryError:
                assert fail_output
                assert (fixture.read_bytes(), mapping.read_bytes()) == originals
                assert proof.restored and proof.remote_entries == original_entries
                assert not list(root.glob('resilience-*-rerun-*.key'))
            else:
                assert not fail_output
                current = json.loads(fixture.read_text())
                other = 'quota' if purpose == 'ha' else 'ha'
                assert current[other + '_api_key_file'] == original_f[other + '_api_key_file']
                assert current[other + '_api_key_id'] == original_f[other + '_api_key_id']
                assert result['purpose'] == purpose and result['previous_identity'] == original_f[purpose + '_api_key_id']
                assert result['identity'].startswith('resilience-' + purpose + '-rerun-')
                assert current[purpose + '_api_key_id'] == result['identity']
                assert Path(current[purpose + '_api_key_file']).stat().st_mode & 0o077 == 0
                entries = json.loads(mapping.read_text())['fluxgate']['envoy']['api-keys']
                assert entries[:-1] == original_entries and len(entries) == 2
                assert json.loads(output.read_text()) == result and not proof.backups
            assert proof.counters == {'existing': 2} and proof.policy_stamp() == ('snapshot', 3, 'epoch')
            checks += 1
    print(json.dumps({'result': 'PASS', 'self_checks': checks}))


def main():
    args = parse_args()
    os.umask(0o077)
    if args.self_check:
        self_check()
        return
    spec = importlib.util.spec_from_file_location('credential_proof', Path(__file__).with_name('verify-credentials.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    proof = module.Proof(args.fixture)
    proof.validate()
    print(json.dumps(prepare(args, proof, module)))


if __name__ == '__main__':
    main()
