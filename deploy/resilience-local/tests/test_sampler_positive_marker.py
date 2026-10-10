"""Exercise the actual threaded sampler with controlled positive-marker I/O."""
import json
from pathlib import Path
import runpy
import tempfile
import threading
import unittest
from unittest.mock import patch

from sampler_test_support import MODULE,load_sampler,local_server
SAMPLER = load_sampler().sampler_worker


class PositiveMarker(unittest.TestCase):
    def run_sampler(self, writer):
        directory = tempfile.TemporaryDirectory(prefix='sampler-positive-unit-')
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        stop, positive, progress = root / 'stop', root / 'positive', root / 'progress'
        requests, enough, outcome = [], threading.Event(), {'progress_snapshots': []}
        port,requests,enough=local_server(self,body=b'backend\n')
        original_write = MODULE['private_write']
        def write(path, data):
            if Path(path) == positive:
                writer(path, data)
            else:
                outcome['progress_snapshots'].append(json.loads(data))
                original_write(path, data)

        def run():
            try:
                outcome['report'] = SAMPLER({'service': '127.0.0.1', 'port': port, 'path': '/load',
                    'host': 'offline', 'api_key': 'offline', 'body': 'backend',
                    'positive_path': str(positive), 'progress_path': str(progress)}, str(stop))
            except BaseException as error:
                outcome['error'] = error

        patcher = patch.dict(SAMPLER.__globals__, private_write=write)
        patcher.start()
        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        def cleanup():
            stop.touch()
            thread.join(7)
            patcher.stop()
        self.addCleanup(cleanup)
        return stop, progress, requests, enough, outcome, thread

    def finish(self, stop, thread, outcome):
        stop.touch()
        thread.join(7)
        self.assertFalse(thread.is_alive(), 'sampler failed to drain')
        self.assertNotIn('error', outcome)
        report = outcome['report']
        self.assertEqual(report['scheduled'], len(report['samples']) + report['omitted_schedules'])
        self.assertEqual(report['pending'], 0)
        self.assertTrue(report['drain_complete'])
        self.assertLessEqual(report['peak_inflight'], 24)
        return report

    def test_marker_is_published_once_for_many_fresh_successful_connections(self):
        writes = []
        stop, _, requests, enough, outcome, thread = self.run_sampler(lambda p, d: writes.append((p, d)))
        self.assertTrue(enough.wait(2), 'sampler did not reach five arrivals')
        report = self.finish(stop, thread, outcome)
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0][1], 'yes')
        self.assertGreaterEqual(len(requests), 5)
        self.assertEqual(len({connection.address for connection in requests}), len(requests))
        self.assertTrue(all(connection.closed for connection in requests))
        self.assertEqual(report['worker_failures'], 0)

    def test_blocked_marker_does_not_hold_scheduler_and_stop_drains_its_worker(self):
        entered, release = threading.Event(), threading.Event()
        writes = []
        def blocked(path, data):
            writes.append((path, data))
            entered.set()
            if not release.wait(3):
                raise RuntimeError('test marker was not released')
        # Release before the sampler cleanup joins, even when an assertion fails.
        stop, progress, _, enough, outcome, thread = self.run_sampler(blocked)
        self.addCleanup(release.set)
        self.assertTrue(entered.wait(2))
        try:
            self.assertTrue(enough.wait(.8), 'blocked marker prevented scheduled arrivals')
            snapshots = list(outcome['progress_snapshots'])
            self.assertTrue(snapshots)
            self.assertTrue(any(0 in snapshot['pending_sequences'] for snapshot in snapshots))
            for snapshot in snapshots:
                self.assertEqual(snapshot['scheduled'], (snapshot['completed'] if snapshot.get('partial')
                                 else len(snapshot['samples'])) +
                                 snapshot['pending'] + snapshot['omitted_schedules'])
                self.assertTrue(set(snapshot['pending_sequences']).isdisjoint(
                    sample['sequence'] for sample in snapshot.get('samples', [])))
            stop.touch()
            thread.join(.2)
            self.assertTrue(thread.is_alive(), 'stop discarded the blocked marker worker')
        finally:
            release.set()
        report = self.finish(stop, thread, outcome)
        self.assertEqual(len(writes), 1)
        self.assertEqual(report['omitted_schedules'], 0)
        self.assertEqual([sample['sequence'] for sample in report['samples']], list(range(report['scheduled'])))
        self.assertTrue(all(sample['status'] == 200 for sample in report['samples']))
        self.assertEqual(json.loads(progress.read_text())['pending'], 0)

    def test_unfinished_marker_is_explicit_pending_when_bounded_drain_expires(self):
        entered, release = threading.Event(), threading.Event()
        def blocked(path, data):
            entered.set()
            release.wait(10)
        stop, _, _, enough, outcome, thread = self.run_sampler(blocked)
        self.addCleanup(release.set)
        try:
            self.assertTrue(entered.wait(2))
            self.assertTrue(enough.wait(2))
            stop.touch()
            thread.join(7)
            self.assertFalse(thread.is_alive(), 'bounded drain never returned')
            self.assertNotIn('error', outcome)
            report = outcome['report']
            self.assertFalse(report['drain_complete'])
            self.assertEqual(report['pending_sequences'], [0])
            self.assertEqual(report['scheduled'], len(report['samples']) +
                             report['pending'] + report['omitted_schedules'])
            self.assertNotIn(0, [sample['sequence'] for sample in report['samples']])
            with self.assertRaisesRegex(MODULE['ProofError'], 'did not drain cleanly'):
                MODULE['sampler_summary'](report)
        finally:
            release.set()

    def test_marker_write_failure_is_not_retried_and_rejects_sampler_summary(self):
        writes = []
        def failing(path, data):
            writes.append((path, data))
            raise OSError('injected marker failure')
        stop, _, _, enough, outcome, thread = self.run_sampler(failing)
        self.assertTrue(enough.wait(2))
        report = self.finish(stop, thread, outcome)
        self.assertEqual(len(writes), 1)
        self.assertEqual(report['worker_failures'], 1)
        with self.assertRaisesRegex(MODULE['ProofError'], 'did not drain cleanly'):
            MODULE['sampler_summary'](report)


if __name__ == '__main__':
    unittest.main()
