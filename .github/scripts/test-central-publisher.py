#!/usr/bin/env python3
"""Synthetic plugin regression: registry downloads only, no publication/credentials."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--maven', default='mvn')
parser.add_argument('--settings')
parser.add_argument('--repository')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
parent = ET.parse(root / 'pom.xml')
plugin = parent.find(".//m:plugin[m:artifactId='central-publishing-maven-plugin']", ns)
assert plugin.findtext('m:version', namespaces=ns) == '0.12.0'
assert plugin.findtext('m:configuration/m:waitUntil', namespaces=ns) == 'published'
fixture = root / '.github/tests/central-publisher'
env = dict(os.environ)
for key in ('MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD', 'MAVEN_GPG_PASSPHRASE',
            'GPG_PRIVATE_KEY', 'GPG_PASSPHRASE'):
    env.pop(key, None)
with tempfile.TemporaryDirectory(prefix='central-plugin-proof-') as directory:
    work = Path(directory)
    shutil.copy(fixture / 'pom.xml', work / 'pom.xml')
    command = [args.maven, '-B', '-ntp', '-f', str(work / 'pom.xml')]
    if args.settings:
        command += ['-s', args.settings]
    if args.repository:
        command += ['-Dmaven.repo.local=' + args.repository]
    command += ['-DskipUpload=true', '-DignorePublishedComponents=false',
                '-DcentralBaseUrl=http://127.0.0.1:1']
    subprocess.run(command + ['deploy',
        'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath',
        '-Dmdep.outputFile=' + str(work / 'classpath')], check=True, env=env)
    with zipfile.ZipFile(work / 'target/central-publishing/central-bundle.zip') as archive:
        files = [name for name in archive.namelist() if not name.endswith('/')]
        prefix = 'invalid/local/fluxgate/central-offline-proof/0.0.0-proof/central-offline-proof-0.0.0-proof.pom'
        assert prefix in files
        assert all(name == prefix or name.startswith(prefix + '.') for name in files), files
        assert archive.read(prefix) == (work / 'pom.xml').read_bytes()
    subprocess.run(['java', '-cp', (work / 'classpath').read_text().strip(),
                    str(fixture / 'StatusResponseRegression.java')], check=True, env=env)
print('PASS: skipUpload synthetic bundle; actual status parser tested without HTTP or real credentials')
