"""Offline regression tests. Generates only disposable synthetic keys. Requires PyYAML and GnuPG."""
import copy
import os
from pathlib import Path
import subprocess
import tempfile
import yaml

workflow = Path(__file__).resolve().parents[1] / '.github/workflows/gpg-diagnostic.yml'
if not workflow.exists():
    workflow = Path(__file__).with_name('gpg-diagnostic.yml')
data = yaml.load(workflow.read_text(), Loader=yaml.BaseLoader)
def validate_guards(config):
    assert set(config) == {'name', 'on', 'permissions', 'jobs'}
    assert config['on'] == {'workflow_dispatch': ''}
    assert config['permissions'] == {}
    assert set(config['jobs']) == {'signing-diagnostic'}
    job = config['jobs']['signing-diagnostic']
    assert set(job) == {'if', 'runs-on', 'environment', 'timeout-minutes', 'steps'}
    assert job['environment'] == 'release'
    assert job['runs-on'] == 'ubuntu-24.04'
    assert job['timeout-minutes'] == '5'
    expected_guard = (
        "github.event_name == 'workflow_dispatch' && "
        "github.ref == 'refs/heads/main' && "
        "github.repository == 'OpenFluxGate/fluxgate'"
    )
    assert job['if'] == expected_guard
    assert len(job['steps']) == 1
    step = job['steps'][0]
    assert set(step) == {'name', 'shell', 'env', 'run'}
    assert step['shell'] == 'bash'
    assert step['env'] == {
        'GPG_PRIVATE_KEY': '${{ secrets.GPG_PRIVATE_KEY }}',
        'GPG_PASSPHRASE': '${{ secrets.GPG_PASSPHRASE }}',
    }
    return step

step = validate_guards(data)
# These checks guard against accidental broadening, not malicious PR authors
# who can also edit this test. Trusted review/environment protections remain required.
mutations = [
    lambda d: d['jobs']['signing-diagnostic'].__setitem__('if',
        d['jobs']['signing-diagnostic']['if'] + ' || true'),
    lambda d: d['on'].__setitem__('pull_request_target', ''),
    lambda d: d['on'].__setitem__('workflow_dispatch', {'inputs': {'ref': {}}}),
    lambda d: d['jobs'].__setitem__('extra-job', {}),
    lambda d: d['jobs']['signing-diagnostic'].__setitem__('permissions', {'contents': 'write'}),
    lambda d: d['jobs']['signing-diagnostic'].__setitem__('environment', 'unprotected'),
    lambda d: d['jobs']['signing-diagnostic']['steps'].append({'uses': 'actions/checkout@main'}),
    lambda d: d['jobs']['signing-diagnostic']['steps'][0]['env'].__setitem__('CENTRAL_TOKEN', 'unused'),
]
for mutate in mutations:
    changed = copy.deepcopy(data)
    mutate(changed)
    try:
        validate_guards(changed)
    except AssertionError:
        continue
    raise AssertionError('Unsafe workflow mutation was accepted')
print('Eight unsafe workflow mutations rejected: PASS')
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
        gpg('--faked-system-time', '20200101T000000', '--passphrase', password,
            '--quick-generate-key', 'Expired regression <expired@example.invalid>', 'rsa2048', 'sign', '1d')
        expired_key = gpg('--passphrase', password, '--armor', '--export-secret-keys', 'expired@example.invalid').decode()
        cases = [
            ('valid', key, password, True, 'passed'),
            ('wrong-passphrase', key, 'incorrect', False, 'PASSPHRASE_REJECTED'),
            ('malformed-key', 'not-a-key', password, False, 'import failed'),
            ('missing-key', '', password, False, 'key is missing'),
            ('missing-passphrase', key, '', False, 'passphrase is missing'),
            ('public-only', public, password, False, 'NO_USABLE_SECRET_SIGNING_KEY'),
            ('expired-key', expired_key, password, False, 'NO_USABLE_SECRET_SIGNING_KEY'),
        ]
        for name, candidate, phrase, success, expected in cases:
            result = subprocess.run(['bash', '-c', script], text=True, capture_output=True,
                env=dict(os.environ, GPG_PRIVATE_KEY=candidate, GPG_PASSPHRASE=phrase, TMPDIR=probes), timeout=30)
            output = result.stdout + result.stderr
            assert (result.returncode == 0) == success, name
            assert expected in output, name
            assert password not in output and 'BEGIN PGP' not in output and 'Disposable' not in output, name
            allowed = {
                'GPG signing and verification passed. No publication or Central authentication was attempted.',
                '::error::GPG signing failed: PASSPHRASE_REJECTED',
                '::error::GPG signing failed: NO_USABLE_SECRET_SIGNING_KEY',
                '::error::GPG key import failed.',
                '::error::GPG private key is missing.',
                '::error::GPG passphrase is missing.',
            }
            assert output.strip() in allowed, name + ': unexpected output (redacted)'
            assert not list(Path(probes).iterdir()), name + ': temp keyring not cleaned'
            print(name + ': PASS')
    finally:
        subprocess.run(['gpgconf', '--homedir', home, '--kill', 'all'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print('Workflow guards, shell syntax, seven synthetic-key cases, and cleanup: PASS')


# Extract the exact inline classifier: tests do not duplicate its implementation.
classifier = script.split('classify_signing_failure() {', 1)[1].split('\ngpg_args=', 1)[0]
classifier = 'classify_signing_failure() {' + classifier
status_cases = [
    ('bad-passphrase', '[GNUPG:] BAD_PASSPHRASE SECRET_KEY_ID\n', 'PASSPHRASE_REJECTED'),
    ('encoded-bad-passphrase', '[GNUPG:] FAILURE sign 67108875\n', 'PASSPHRASE_REJECTED'),
    ('no-secret-key', '[GNUPG:] NO_SECKEY SECRET_KEY_ID\n', 'NO_USABLE_SECRET_SIGNING_KEY'),
    ('signer-expired', '[GNUPG:] INV_SGNR 5 SECRET_UID\n', 'SIGNER_EXPIRED'),
    ('signer-revoked', '[GNUPG:] INV_SGNR 4 SECRET_UID\n', 'SIGNER_REVOKED'),
    ('wrong-usage', '[GNUPG:] INV_SGNR 3 SECRET_UID\n', 'SIGNER_WRONG_USAGE'),
    ('unrelated-expiry', '[GNUPG:] KEYEXPIRED 1234\n', 'SIGNING_FAILED_UNCLASSIFIED'),
    ('unknown', '[GNUPG:] UNKNOWN SECRET_FINGERPRINT\n', 'SIGNING_FAILED_UNCLASSIFIED'),
    ('malformed-error', '[GNUPG:] FAILURE sign $(echo SECRET_KEY_ID)\n', 'SIGNING_FAILED_UNCLASSIFIED'),
    ('oversized-error', '[GNUPG:] FAILURE sign 999999999999999999999999999999\n', 'SIGNING_FAILED_UNCLASSIFIED'),
]
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / 'status'
    for name, status, expected in status_cases:
        path.write_text(status)
        result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + classifier +
                                 '\nclassify_signing_failure "$1"', 'classifier', str(path)],
                                capture_output=True, text=True, timeout=5)
        assert result.returncode == 0, name
        assert result.stdout == expected + '\n' and result.stderr == '', name + ': non-allowlisted output'
        print('status-' + name + ': PASS')
print('Ten status-code classification and non-disclosure cases: PASS')
