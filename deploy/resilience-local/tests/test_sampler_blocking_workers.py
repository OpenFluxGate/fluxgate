"""Actual generated sampler: idle waits and bounded shutdown retain real work evidence."""
import os
from pathlib import Path
import queue
import runpy
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest

SOURCE=Path(os.environ.get('FLUXGATE_WORKER_TEST_SOURCE',str(Path(__file__).resolve().parents[1]/'verify-credentials.py')))
MODULE=runpy.run_path(str(SOURCE))


class BlockingWorkers(unittest.TestCase):
    def start(self, hold_first=False, hold_dispatch=False, stuck=False, instant_drain=False):
        directory=tempfile.TemporaryDirectory(prefix='sampler-idle-unit-')
        self.addCleanup(directory.cleanup)
        root=Path(directory.name)
        stop=root/'stop'
        scheduler_entered, scheduler_release=threading.Event(),threading.Event()
        dispatch_release, response_entered, response_release=threading.Event(),threading.Event(),threading.Event()
        queues,connections,outcome=[],[],{}
        clock=SimpleNamespace(now=0.0)
        namespace={}
        exec(MODULE['sampler_program']().split('\nconfig = ',1)[0],namespace)
        class ObservedQueue(queue.Queue):
            def __init__(self,*a,**kw):
                super().__init__(*a,**kw)
                self.get_calls=[]
                self.empty_wakeups=0
                if self.maxsize==24:queues.append(self)
            def get(self,*a,**kw):
                if self.maxsize==24:
                    self.get_calls.append((a,kw))
                    if hold_dispatch:dispatch_release.wait(4)
                try:return super().get(*a,**kw)
                except queue.Empty:
                    if self.maxsize==24:self.empty_wakeups+=1
                    raise
            def put_nowait(self,item):
                result=super().put_nowait(item)
                if self.maxsize==24 and hold_dispatch and type(item) is int and item==23:
                    stop.touch()  # Exactly 24 queued arrivals; every worker is dispatch-stalled.
                return result
        namespace['queue']=SimpleNamespace(Queue=ObservedQueue,Empty=queue.Empty,Full=queue.Full)
        class Condition(threading.Condition):
            def __enter__(self):
                acquired=super().__enter__()
                if hold_first and threading.current_thread().name=='idle-scheduler-test' and not scheduler_entered.is_set():
                    scheduler_entered.set()
                    scheduler_release.wait(4)
                return acquired
            def wait(self,timeout=None):
                if instant_drain:
                    clock.now+=timeout
                    return False
                return super().wait(timeout)
        events=[]
        class Event(threading.Event):
            def __init__(self):
                super().__init__()
                self.is_shutdown=not events
                events.append(self)
            def set(self):
                super().set()
                if self.is_shutdown:dispatch_release.set()
        namespace['threading']=SimpleNamespace(Condition=Condition,Event=Event,
            BoundedSemaphore=threading.BoundedSemaphore,Thread=threading.Thread,Lock=threading.Lock)
        if instant_drain:
            def sleep(seconds):
                clock.now+=seconds
                time.sleep(.001)
            namespace['time']=SimpleNamespace(monotonic=lambda:clock.now,
                monotonic_ns=lambda:int(clock.now*1e9),time_ns=time.time_ns,sleep=sleep)
        class Connection:
            def __init__(self,*a,**kw):connections.append(self)
            def request(self,*a,**kw):pass
            def getresponse(self):
                response_entered.set()
                if stuck:response_release.wait(8)
                return SimpleNamespace(status=200,read=lambda:b'backend')
            def close(self):pass
        namespace['http']=SimpleNamespace(client=SimpleNamespace(HTTPConnection=Connection))
        def run():
            try:
                outcome['report']=namespace['sampler_worker']({'service':'offline','port':80,
                    'host':'offline','path':'/load','api_key':'offline','body':'backend',
                    'progress_path':str(root/'progress'),'positive_path':str(root/'positive')},str(stop))
            except BaseException as error:outcome['error']=error
        runner=threading.Thread(target=run,name='idle-scheduler-test',daemon=True)
        runner.start()
        def cleanup():
            stop.touch()
            scheduler_release.set()
            response_release.set()
            dispatch_release.set()
            runner.join(8)
        self.addCleanup(cleanup)
        return SimpleNamespace(stop=stop,runner=runner,outcome=outcome,queues=queues,connections=connections,
            scheduler_entered=scheduler_entered,scheduler_release=scheduler_release,
            response_entered=response_entered,response_release=response_release)

    def report(self,state):
        state.runner.join(3)
        self.assertFalse(state.runner.is_alive(),'shutdown exceeded bounded worker join')
        self.assertNotIn('error',state.outcome)
        report=state.outcome['report']
        self.assertEqual(report['scheduled'],len(report['samples'])+report['pending']+report['omitted_schedules'])
        self.assertLessEqual(report['peak_inflight'],24)
        return report

    def test_idle_workers_do_not_poll_while_scheduler_is_held_before_first_arrival(self):
        state=self.start(hold_first=True)
        self.assertTrue(state.scheduler_entered.wait(2))
        time.sleep(.35)  # Real idle queue waits; original polling produces several Empty wakes.
        state.stop.touch()
        state.scheduler_release.set()
        report=self.report(state)
        self.assertEqual(report['scheduled'],0)
        self.assertEqual(state.connections,[])
        self.assertTrue(report['drain_complete'])
        self.assertEqual(state.queues[0].empty_wakeups,0,'idle worker polling caused Empty wakeups')
        self.assertEqual(len(state.queues[0].get_calls),24,'idle workers repeatedly requested queue work')

    def test_full_queue_shutdown_does_not_block_or_execute_undrained_arrivals(self):
        state=self.start(hold_dispatch=True,instant_drain=True)
        report=self.report(state)
        self.assertEqual(report['scheduled'],24)
        self.assertEqual(report['pending_sequences'],list(range(24)))
        self.assertEqual(report['samples'],[])
        self.assertEqual(state.connections,[],'new HTTP requests started after shutdown')
        self.assertFalse(report['drain_complete'])
        with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](report)

    def test_inflight_completion_drains_and_idle_workers_exit_without_sentinel_samples(self):
        state=self.start(stuck=True)
        self.assertTrue(state.response_entered.wait(2))
        state.stop.touch()
        time.sleep(.15)
        self.assertTrue(state.runner.is_alive(),'in-flight arrival was discarded')
        state.response_release.set()
        report=self.report(state)
        self.assertEqual(report['scheduled'],1)
        self.assertEqual([sample['sequence'] for sample in report['samples']],[0])
        self.assertEqual(report['pending'],0)
        self.assertTrue(report['drain_complete'])
        self.assertEqual(len(state.connections),1)
        self.assertTrue(MODULE['sampler_summary'](report)['uninterrupted_observed'])

    def test_stuck_http_worker_remains_explicit_pending_after_bounded_shutdown(self):
        state=self.start(stuck=True,instant_drain=True)
        self.assertTrue(state.response_entered.wait(2))
        state.stop.touch()
        report=self.report(state)
        self.assertEqual(report['scheduled'],1)
        self.assertEqual(report['pending_sequences'],[0])
        self.assertEqual(report['samples'],[])
        self.assertFalse(report['drain_complete'])
        self.assertEqual(len(state.connections),1)
        with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](report)
        state.response_release.set()


if __name__=='__main__':unittest.main()
