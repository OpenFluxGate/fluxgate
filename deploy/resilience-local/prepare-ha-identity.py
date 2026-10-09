#!/usr/bin/env python3
"""Append a fresh isolated HA identity without changing policies or existing counters."""
import argparse
import binascii
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    spec = importlib.util.spec_from_file_location('credential_proof', Path(__file__).with_name('verify-credentials.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    proof = module.Proof(args.fixture)
    proof.validate()
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
        identity = 'resilience-ha-rerun-' + secrets.token_hex(8)
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
        previous_identity = proof.f.get('ha_api_key_id')
        proof.f.update(ha_api_key_file=str(key_file), ha_api_key_id=identity)
        module.private_write(fixture, json.dumps(proof.f, indent=2) + '\n')
        proof.backups.clear()
        result = {'passed': True, 'previous_identity': previous_identity, 'identity': identity,
                  'quota_slot': candidate_slot, 'load_slot': load_slot,
                  'existing_mapping_entries_preserved': len(existing),
                  'policy_pointer_unchanged': True, 'existing_counters_deleted_or_reset': False}
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    except Exception:
        module.private_write(fixture, original_fixture)
        module.private_write(mapping, original_mapping)
        proof.cleanup_failed()
        key_file.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    main()
