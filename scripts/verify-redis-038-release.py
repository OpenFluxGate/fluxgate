#!/usr/bin/env python3
"""Fail closed on mixed-version or non-Redis 0.3.8 release preparation."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

BASE = '528338c1d2c053e9e829f31af337e4c759c5affc'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
MODULE = Path('fluxgate-redis-ratelimiter')

def verify_no_redis_keys(jar_path):
    """Inspect shipped implementation, not the compatible public method name keys()."""
    with zipfile.ZipFile(jar_path) as jar:
        classes = [n[:-6].replace('/', '.') for n in jar.namelist() if n.endswith('.class')]
        assert classes, 'No implementation classes'
        for name in jar.namelist():
            if name.endswith('.lua'):
                script = jar.read(name).decode('utf-8')
                assert not re.search(r"redis\.(?:call|pcall)\s*\(\s*['\"]KEYS['\"]", script, re.I), \
                    'Blocking Redis KEYS in Lua: ' + name
    bytecode = subprocess.check_output(['javap', '-c', '-p', '-classpath', str(jar_path), *classes], text=True)
    assert not re.search(r'//.*io/lettuce/.*\.keys:', bytecode), 'Blocking Lettuce KEYS invocation'
    assert not re.search(r'//.*io/lettuce/.*CommandType\.KEYS:', bytecode), 'Raw Redis KEYS dispatch'
    for command in ('io/lettuce/core/api/sync/RedisCommands.scan:',
                    'io/lettuce/core/cluster/api/sync/RedisAdvancedClusterCommands.scan:'):
        assert command in bytecode, 'Missing built-in SCAN path: ' + command
    print(f'JAR gate: {len(classes)} classes checked; no Lettuce/Lua Redis KEYS; both built-in SCAN paths present.')

def check_pom(path):
    root = ET.parse(path).getroot()
    def value(p):
        return root.findtext(p, namespaces=NS)
    assert value('m:artifactId') == 'fluxgate-redis-ratelimiter'
    assert value('m:version') == '0.3.8'
    assert value('m:parent/m:groupId') == 'io.github.openfluxgate'
    assert value('m:parent/m:artifactId') == 'fluxgate'
    assert value('m:parent/m:version') == '0.3.7'
    relative = root.find('m:parent/m:relativePath', NS)
    assert relative is not None and not relative.text
    core = root.find("m:dependencies/m:dependency[m:artifactId='fluxgate-core']", NS)
    assert core is not None and core.findtext('m:version', namespaces=NS) == '0.3.7'
    for dependency in root.findall('m:dependencies/m:dependency', NS):
        if dependency.findtext('m:groupId', namespaces=NS) == 'io.github.openfluxgate':
            assert dependency.findtext('m:version', namespaces=NS) == '0.3.7'

check_pom(MODULE / 'pom.xml')
PATCH = 'c0993cd5bbd4b410f3e94006d5c9ee7b4c88bb92'
subprocess.run(['git', 'merge-base', '--is-ancestor', PATCH, 'HEAD'], check=True)
subprocess.run(['git', 'diff', '--exit-code', PATCH, 'HEAD', '--', str(MODULE)], check=True, stdout=subprocess.DEVNULL)
assert subprocess.check_output(['git', 'rev-parse', 'v0.3.7^{}'], text=True).strip() == BASE
subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], check=True)
changed = subprocess.check_output(['git', 'diff', '--name-only', BASE], text=True).splitlines()
allowed = {'scripts/check-redis-038-central.py', 'scripts/verify-redis-038-signatures.py', 'scripts/verify-redis-038-release.py', '.github/workflows/redis-038-prepare.yml', 'docs/redis-038-release.md'}
for path in changed:
    assert path.startswith('fluxgate-redis-ratelimiter/') or path in allowed, f'Out-of-scope change: {path}'
if '--artifacts' in sys.argv:
    target = MODULE / 'target'
    prefix = 'fluxgate-redis-ratelimiter-0.3.8'
    expected = {prefix + suffix for suffix in ('.jar', '-sources.jar', '-javadoc.jar')}
    assert {p.name for p in target.glob('*.jar')} == expected
    verify_no_redis_keys(target / (prefix + '.jar'))
    for name in expected:
        with zipfile.ZipFile(target / name) as jar:
            assert jar.testzip() is None
            if name == prefix + '.jar':
                metadata = jar.read('META-INF/maven/io.github.openfluxgate/fluxgate-redis-ratelimiter/pom.xml')
                import io
                check_pom(io.BytesIO(metadata))
                classes = [n for n in jar.namelist() if n.endswith('.class')]
                assert classes
                for n in classes:
                    assert int.from_bytes(jar.read(n)[6:8], 'big') <= 55, f'Not Java 11: {n}'
print('Redis-only 0.3.8 metadata, baseline and scope verified' + ('; artifacts verified' if '--artifacts' in sys.argv else ''))

if '--reports' in sys.argv:
    reports = list((MODULE / 'target/surefire-reports').glob('TEST-*.xml'))
    assert reports, 'Missing test reports'
    cluster = False
    for path in reports:
        suite = ET.parse(path).getroot()
        assert all(int(suite.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')), 'Incomplete test suite: ' + path.name
        if 'ClusterConnectionIntegrationTest' in path.name:
            cluster = int(suite.get('tests', '0')) > 0
    assert cluster, 'Missing real cluster integration tests'
    print('Test reports have no failures, errors or skipped cases; cluster tests executed.')
