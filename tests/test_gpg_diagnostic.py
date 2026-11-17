"""Offline regression tests. Generates only disposable synthetic keys. Requires PyYAML and GnuPG."""
import os
from pathlib import Path
import subprocess
import tempfile
import yaml

workflow = Path(__file__).resolve().parents[1] / '.github/workflows/gpg-diagnostic.yml'
if not workflow.exists():
    workflow = Path(__file__).with_name('gpg-diagnostic.yml')
data = yaml.load(workflow.read_text(), Loader=yaml.BaseLoader)
assert set(data['on']) == {'workflow_dispatch'}
assert data['permissions'] == {}
job = data['jobs']['signing-diagnostic']
assert job['environment'] == 'release'
assert "github.ref == 'refs/heads/main'" in job['if']
assert "github.repository == 'OpenFluxGate/fluxgate'" in job['if']
assert len(job['steps']) == 1
step = job['steps'][0]
assert 'uses' not in step
assert set(step['env']) == {'GPG_PRIVATE_KEY', 'GPG_PASSPHRASE'}
script = step['run']
subprocess.run(['bash', '-n'], input=script, text=True, check=True)

with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as probes:
    env = dict(os.environ, GNUPGHOME=home)
    def gpg(*args):
        return subprocess.run(['gpg', '--no-options', '--batch', '--pinentry-mode', 'loopback', *args],
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
    try:
        password = 'synthetic-regression-only'
        gpg('--passphrase', password, '--quick-generate-key', 'Disposable regression <test@example.invalid>', 'rsa2048', 'sign', '1d')
        key = gpg('--passphrase', password, '--armor', '--export-secret-keys').decode()
        public = gpg('--armor', '--export').decode()
        cases = [
            ('valid', key, password, True, 'passed'),
            ('wrong-passphrase', key, 'incorrect', False, 'signing failed'),
            ('malformed-key', 'not-a-key', password, False, 'import failed'),
            ('missing-key', '', password, False, 'key is missing'),
            ('missing-passphrase', key, '', False, 'passphrase is missing'),
            ('public-only', public, password, False, 'signing failed'),
        ]
        for name, candidate, phrase, success, expected in cases:
            result = subprocess.run(['bash', '-c', script], text=True, capture_output=True,
                env=dict(os.environ, GPG_PRIVATE_KEY=candidate, GPG_PASSPHRASE=phrase, TMPDIR=probes), timeout=30)
            output = result.stdout + result.stderr
            assert (result.returncode == 0) == success, name
            assert expected in output, name
            assert password not in output and 'BEGIN PGP' not in output and 'Disposable' not in output, name
            assert not list(Path(probes).iterdir()), name + ': temp keyring not cleaned'
            print(name + ': PASS')
    finally:
        subprocess.run(['gpgconf', '--homedir', home, '--kill', 'all'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print('Workflow guards, shell syntax, six synthetic-key cases, and cleanup: PASS')
