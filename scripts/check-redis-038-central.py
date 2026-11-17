#!/usr/bin/env python3
"""Fail closed before publication, verify immutable public bytes afterward."""
import hashlib
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
BASE = 'https://repo.maven.apache.org/maven2/io/github/openfluxgate/fluxgate-redis-ratelimiter/0.3.8/'
PREFIX = 'fluxgate-redis-ratelimiter-0.3.8'
if '--published' not in sys.argv:
    try:
        urllib.request.urlopen(BASE + PREFIX + '.pom', timeout=30).close()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    else:
        raise SystemExit('0.3.8 already exists; do not redeploy.')
    print('Central coordinate absent (404).')
else:
    target = Path('fluxgate-redis-ratelimiter/target')
    for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar'):
        local = target / (PREFIX + suffix)
        if suffix == '.pom' and not local.exists():
            local = Path('fluxgate-redis-ratelimiter/pom.xml')
        for extra in ('', '.asc'):
            expected = local if not extra else target / (PREFIX + suffix + extra)
            for attempt in range(30):
                try:
                    with urllib.request.urlopen(BASE + PREFIX + suffix + extra, timeout=30) as response:
                        actual = response.read()
                    break
                except urllib.error.HTTPError as error:
                    if error.code != 404 or attempt == 29:
                        raise
                    time.sleep(20)
            assert hashlib.sha256(actual).digest() == hashlib.sha256(expected.read_bytes()).digest(), 'Published bytes differ: ' + suffix + extra
    print('All four published artifacts and signatures match this build.')
