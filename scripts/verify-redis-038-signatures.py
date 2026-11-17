#!/usr/bin/env python3
"""Verify all module signatures against an explicitly configured primary key."""
import os
from pathlib import Path
import re
import subprocess

fingerprint = os.environ.get('SIGNING_FINGERPRINT', '').upper()
assert re.fullmatch(r'[0-9A-F]{40}', fingerprint), 'Missing approved signing fingerprint'
target = Path('fluxgate-redis-ratelimiter/target')
prefix = 'fluxgate-redis-ratelimiter-0.3.8'
for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom'):
    artifact = target / (prefix + suffix)
    # maven-gpg-plugin signs the project POM from the module source directory.
    signature = target / (prefix + suffix + '.asc')
    if suffix == '.pom' and not artifact.exists():
        artifact = Path('fluxgate-redis-ratelimiter/pom.xml')
    assert artifact.is_file() and signature.is_file(), f'Missing signed artifact: {suffix}'
    result = subprocess.run(['gpg', '--batch', '--status-fd=1', '--verify', str(signature), str(artifact)], capture_output=True, text=True)
    assert result.returncode == 0, f'Invalid signature: {suffix}'
    valid = [line.split() for line in result.stdout.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
    assert len(valid) == 1 and fingerprint in (valid[0][2].upper(), valid[0][-1].upper()), f'Unexpected signing key: {suffix}'
print('All four Redis 0.3.8 artifact signatures verified against approved fingerprint')
