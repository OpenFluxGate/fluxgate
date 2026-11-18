"""Fixture manifest refreshes must preserve private file references and previous good data."""
import json
from pathlib import Path
import runpy
import stat
import tempfile
import unittest


setup = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'setup.py'))


class FixtureManifestTest(unittest.TestCase):
    def test_refresh_keeps_credentials_out_of_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # An existing fixture has private bytes in these paths and may be reused on a rerun.
            for path in setup['credential_files'](root).values():
                path.write_text('synthetic-fixture-material')
            for operation in ('before', 'after'):
                setup['write_fixture_metadata'](root, {'bootstrap_operation_id': operation})
            manifest = root / 'fixture.json'
            raw = manifest.read_text()
            result = json.loads(raw)
            self.assertNotIn('synthetic-fixture-material', raw)
            self.assertEqual(str(root / 'redis-password'), result['redis_password_file'])
            self.assertEqual(str(root / 'ha-api-key'), result['ha_api_key_file'])
            self.assertEqual('after', result['bootstrap_operation_id'])
            self.assertEqual(0o600, stat.S_IMODE(manifest.stat().st_mode))

    def test_failed_refresh_preserves_previous_complete_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'fixture.json'
            manifest.write_text('{"previous": true}\n')
            with self.assertRaises(TypeError):
                setup['write_fixture_metadata'](root, {'unsupported': object()})
            self.assertEqual({'previous': True}, json.loads(manifest.read_text()))
            self.assertEqual([manifest], list(root.iterdir()))
