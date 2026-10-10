"""Progress publication cannot own the arrival lock or become terminal acceptance."""
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from sampler_test_support import MODULE,load_sampler,local_server


class SamplerProgress(unittest.TestCase):
    def start(self, writer):
        temporary = tempfile.TemporaryDirectory(prefix='sampler-progress-test-')
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        stop, progress = directory / 'stop', directory / 'progress'
        count, snapshots, outcome = [], [], {}
        enough = threading.Event()
        namespace = load_sampler().__dict__
        original = namespace['private_write']
        def capture(path, contents):
            if Path(path).name.startswith('progress'):
                snapshot = json.loads(contents)
                snapshots.append(snapshot)
                writer(snapshot)
            original(path, contents)
        namespace['private_write'] = capture
        port,count,enough=local_server(self)
        def run():
            try:
                outcome['report'] = namespace['sampler_worker']({'service':'127.0.0.1','port':port,
                    'host':'offline','api_key':'offline','path':'/load','body':'backend',
                    'progress_path':str(progress),'positive_path':str(directory/'positive')},str(stop))
            except BaseException as error:
                outcome['error'] = error
        thread = threading.Thread(target=run,daemon=True)
        thread.start()
        def cleanup():
            stop.touch()
            thread.join(8)
        self.addCleanup(cleanup)
        return stop, progress, count, snapshots, outcome, enough, thread

    def finish(self, stop, outcome, thread):
        stop.touch()
        thread.join(8)
        self.assertFalse(thread.is_alive())
        self.assertNotIn('error',outcome)
        return outcome['report']

    def test_blocked_progress_writer_keeps_arrivals_and_partial_payload_bounded(self):
        entered, release = threading.Event(), threading.Event()
        def blocked(snapshot):
            if not entered.is_set():
                entered.set()
                release.wait(4)
        stop, _, count, snapshots, outcome, enough, thread = self.start(blocked)
        self.addCleanup(release.set)
        try:
            self.assertTrue(entered.wait(2))
            self.assertTrue(enough.wait(.8),'progress I/O blocked scheduler')
            time.sleep(1.1)  # Multiple progress posts must coalesce while the one writer is blocked.
            self.assertEqual(len(snapshots),1)
        finally:
            release.set()
        report=self.finish(stop,outcome,thread)
        partials=[s for s in snapshots if s.get('partial')]
        self.assertTrue(partials)
        for snapshot in partials:
            self.assertNotIn('samples',snapshot)
            self.assertNotIn('omissions',snapshot)
            self.assertLess(len(json.dumps(snapshot)),2048)
            self.assertEqual(snapshot['scheduled'],snapshot['completed']+snapshot['pending']+snapshot['omitted_schedules'])
            self.assertLessEqual(snapshot['pending'],24)
            self.assertTrue(snapshot['partial'])
            self.assertFalse(snapshot['complete'])
        self.assertEqual(report['omitted_schedules'],0)
        self.assertEqual(len(report['samples']),len(count))
        self.assertTrue(report['progress_writer_drained'])
        self.assertEqual(report['progress_writer_failures'],0)

    def test_progress_write_error_is_explicit_and_terminal_summary_rejects(self):
        def fail(snapshot):
            if snapshot.get('partial',True):
                raise OSError('offline progress write failure')
        stop, _, _, _, outcome, enough, thread=self.start(fail)
        self.assertTrue(enough.wait(2))
        report=self.finish(stop,outcome,thread)
        self.assertGreater(report['progress_writer_failures'],0)
        with self.assertRaises(MODULE['ProofError']):
            MODULE['sampler_summary'](report)

    def test_stuck_reporter_cannot_overwrite_complete_final_report(self):
        entered, release, returned=threading.Event(),threading.Event(),threading.Event()
        def blocked(snapshot):
            if not entered.is_set():
                entered.set()
                release.wait(5)
                returned.set()
        stop, progress, _, _, outcome, enough, thread=self.start(blocked)
        self.addCleanup(release.set)
        try:
            self.assertTrue(entered.wait(2))
            self.assertTrue(enough.wait(.8),'blocked reporter blocked arrivals')
            report=self.finish(stop,outcome,thread)
            self.assertFalse(report['progress_writer_drained'])
            self.assertTrue(json.loads(progress.read_text())['complete'])
            release.set()
            self.assertTrue(returned.wait(2))
            time.sleep(.1)
            self.assertTrue(json.loads(progress.read_text())['complete'],'stale partial replaced terminal report')
            with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](report)
        finally:
            release.set()

    def test_failed_final_report_is_retained_privately_before_rejection_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            proof=MODULE['Proof'].__new__(MODULE['Proof'])
            proof.work,proof.api_key,proof.ns=Path(directory),'offline-secret-key','offline'
            proof.f={'gateway_service':'offline','gateway_namespace':'offline',
                     'kubeconfig':'offline','context':'offline','gateway_host':'offline','load_path':'/load'}
            proof.results={}
            proof.get=lambda *a,**kw:{'spec':{'ports':[{'port':80,'targetPort':8080}],
                                              'selector':{'app':'offline'}}}
            proof.kube=lambda *a,**kw:SimpleNamespace(returncode=0,stdout=b'{"metadata":{"uid":"offline"}}')
            cleanup=[]
            proof.cleanup_sampler_resources=lambda *a:cleanup.append('attempted') or {}
            proof.preserve_sampler_progress=lambda *a:None
            failed={'partial':False,'complete':True,'progress_writer_failures':1,
                'progress_writer_drained':True,'scheduled':1,'omitted_schedules':0,'pending':0,
                'drain_complete':True,'worker_failures':0,
                'samples':[{'sequence':0,'status':200,'body_valid':True}]}
            class Process:
                def __init__(self,*a,**kw):self.stdin,self.returncode=io.BytesIO(),None
                def poll(self):return self.returncode
                def communicate(self,**kw):
                    self.returncode=0
                    return json.dumps(failed).encode(),b''
            with patch.object(MODULE['subprocess'],'Popen',Process):
                with self.assertRaises(MODULE['ProofError']):
                    with proof.traffic_sampler('unit'):pass
            saved=(proof.work/'unit-traffic.json')
            self.assertTrue(saved.exists(),'complete failed stdout report was lost')
            self.assertEqual(json.loads(saved.read_text()),failed)
            self.assertEqual(saved.stat().st_mode & 0o777,0o600)
            self.assertNotIn(proof.api_key,saved.read_text())
            self.assertEqual(cleanup,['attempted'])
            self.assertEqual(proof.results,{})
            # Existing screening must precede even the newly retained raw evidence.
            saved.unlink()
            failed['transport']=proof.api_key
            with patch.object(MODULE['subprocess'],'Popen',Process):
                with self.assertRaisesRegex(MODULE['ProofError'],'leaked secret'):
                    with proof.traffic_sampler('unit'):pass
            self.assertFalse(saved.exists(),'secret-bearing raw report reached disk')
            self.assertEqual(cleanup,['attempted','attempted'])
            self.assertEqual(proof.results,{})

    def test_partial_schema_is_diagnostic_only_and_validated_separately(self):
        partial={'partial':True,'complete':False,'progress_schema':1,'interval_ms':100,
            'scheduled':5,'completed':3,'pending':1,'pending_sequences':[4],'omitted_schedules':1,
            'status_counts':{'200':3},'worker_failures':0,'progress_writer_failures':0,
            'captured_utc_ns':1,'captured_monotonic_ns':1}
        validator=MODULE.get('validate_sampler_partial')
        self.assertIsNotNone(validator,'missing separate partial schema validation')
        self.assertEqual(validator(partial),partial)
        for malformed in (dict(partial,scheduled=6),dict(partial,pending=25),dict(partial,complete=True),
                          dict(partial,status_counts={'200':2}),dict(partial,pending_sequences=[4,4])):
            with self.assertRaises(MODULE['ProofError']):validator(malformed)
        with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](partial)
        with tempfile.TemporaryDirectory() as directory:
            proof=MODULE['Proof'].__new__(MODULE['Proof'])
            proof.work,proof.api_key=Path(directory),'offline-private-key'
            proof.kube=lambda *a,**kw:SimpleNamespace(returncode=0,stdout=json.dumps(partial).encode())
            proof.preserve_sampler_progress('offline','unit')
            saved=json.loads((proof.work/'unit-traffic-partial.json').read_text())
            self.assertTrue(saved['partial_failed_phase'])
            self.assertTrue(saved['partial'])
            self.assertFalse(saved['complete'])


if __name__=='__main__':unittest.main()
