"""Local credential lifecycle using synthetic bytes and the same paths as the real consumers."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import runpy
import stat
import tempfile
from threading import Barrier
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
setup = runpy.run_path(str(ROOT / 'setup.py'))
proof = runpy.run_path(str(ROOT / 'verify-credentials.py'))


class PrivateFixtureFiles(unittest.TestCase):
    def test_concurrent_initialization_preserves_the_first_complete_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'api-key'
            barrier = Barrier(8)
            def initialize(index):
                barrier.wait()
                return setup['private'](path, str(index) * 8192).read_text()
            with ThreadPoolExecutor(max_workers=8) as pool:
                observed = list(pool.map(initialize, range(8)))
            self.assertEqual(len(set(observed)), 1)
            self.assertIn(observed[0], [str(i) * 8192 for i in range(8)])
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(list(root.iterdir()), [path])

    def test_public_mapping_and_private_rotation_rollback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = setup['private'](root / 'api-key', 'synthetic-initial')
            mapping = setup['api_verifier']('resilience-key', path)
            self.assertEqual(mapping, {
                'api-key-id': 'resilience-key', 'user-id': 'resilience-user',
                'attributes': {'tenant': 'resilience'},
                'sha256': hashlib.sha256(b'synthetic-initial').hexdigest(),
            })
            original = path.read_bytes()
            with patch('os.replace', side_effect=OSError('synthetic failure')):
                with self.assertRaises(OSError):
                    proof['private_write'](path, 'incomplete')
            self.assertEqual(path.read_bytes(), original)
            proof['private_write'](path, 'synthetic-rotation')
            self.assertEqual(setup['private'](path, 'ignored').read_text(), 'synthetic-rotation')
            proof['private_write'](path, original)
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(list(root.iterdir()), [path])

    def test_existing_unsafe_file_is_neither_read_nor_modified(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / 'unrelated'
            target.write_text('untouched')
            target.chmod(0o644)
            alias = root / 'api-key'
            alias.symlink_to(target)
            for path in (alias, target):
                with self.assertRaises(ValueError):
                    setup['private'](path)
            self.assertTrue(alias.is_symlink())
            self.assertEqual(target.read_text(), 'untouched')
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o644)
