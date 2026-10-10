"""Owned HTTP child uses blocking idle waits and preserves bounded shutdown evidence."""
import json
from pathlib import Path
import threading
import time
from types import SimpleNamespace
from unittest.mock import patch
import unittest
from sampler_test_support import MODULE,SamplerCase,load_sampler,local_server

class BlockingWorkers(SamplerCase):
    def setUp(self):self.sampler=load_sampler()
    def test_idle_workers_do_not_poll_while_scheduler_is_held_before_first_arrival(self):
        root=self.directory();stop=root/'stop';audit=root/'audit'
        entered,release=threading.Event(),threading.Event();outcome={}
        source=self.sampler.CHILD_SOURCE.replace('job=jobs.get()',
            "with open("+repr(str(audit))+",'a') as audit:audit.write('get\\n')\n        job=jobs.get()")
        class Condition(threading.Condition):
            def __enter__(self):
                acquired=super().__enter__()
                if threading.current_thread().name=='idle-scheduler' and not entered.is_set():
                    entered.set();release.wait(4)
                return acquired
        fake=SimpleNamespace(Condition=Condition,BoundedSemaphore=threading.BoundedSemaphore,
            Event=threading.Event,Thread=threading.Thread,Lock=threading.Lock)
        with patch.object(self.sampler,'CHILD_SOURCE',source),patch.object(self.sampler,'threading',fake):
            thread=threading.Thread(target=lambda:outcome.update(report=self.sampler.sampler_worker(self.config(1,root),stop)),name='idle-scheduler')
            thread.start()
            try:
                self.assertTrue(entered.wait(2));time.sleep(.35)
                self.assertEqual(audit.read_text().splitlines(),['get']*24)
                stop.touch();release.set();thread.join(3)
                self.assertFalse(thread.is_alive());report=outcome['report']
                self.assertEqual(report['scheduled'],0);self.assertTrue(report['drain_complete'])
                self.assertTrue(report['http_child_terminal'])
            finally:stop.touch();release.set();thread.join(8)
    def test_full_queue_shutdown_does_not_block_or_lose_undrained_arrivals(self):
        root=self.directory();stop=root/'stop';clock=[time.monotonic()];admitted=[]
        source="import json,sys,time\njson.loads(sys.stdin.readline())\nprint('{\"kind\":\"ready\"}',flush=True)\ntime.sleep(30)\n"
        original=self.sampler.admission
        def admit(process,index,start):
            original(process,index,start);admitted.append(index)
            if index==23:stop.touch()
        def sleep(seconds):clock[0]+=seconds
        class Condition(threading.Condition):
            def wait(self,timeout=None):clock[0]+=timeout or 0;return False
        fake_time=SimpleNamespace(monotonic=lambda:clock[0],monotonic_ns=lambda:int(clock[0]*1e9),time_ns=time.time_ns,sleep=sleep)
        fake_thread=SimpleNamespace(Condition=Condition,BoundedSemaphore=threading.BoundedSemaphore,
            Event=threading.Event,Thread=threading.Thread,Lock=threading.Lock)
        with patch.object(self.sampler,'CHILD_SOURCE',source),patch.object(self.sampler,'time',fake_time),patch.object(self.sampler,'threading',fake_thread),patch.object(self.sampler,'admission',admit):
            report=self.sampler.sampler_worker(self.config(1,root),stop)
        self.assertEqual(admitted,list(range(24)));self.assertEqual(report['pending_sequences'],list(range(24)))
        self.assertEqual(report['samples'],[]);self.assertFalse(report['drain_complete']);self.assertTrue(report['http_child_terminal'])
        self.assert_accounting(report)
        with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](report)
    def test_inflight_completion_drains_and_idle_workers_exit_without_sentinel_samples(self):
        root=self.directory();stop=root/'stop';entered=threading.Event();result=[]
        port,records,_=local_server(self,body=b'expected',delay=.5)
        original=self.sampler.admission
        def admit(process,index,start):original(process,index,start);entered.set()
        with patch.object(self.sampler,'admission',admit):
            thread=threading.Thread(target=lambda:result.append(self.sampler.sampler_worker(self.config(port,root),stop)))
            thread.start()
            try:
                self.assertTrue(entered.wait(2));stop.touch();thread.join(.15);self.assertTrue(thread.is_alive())
                thread.join(3);self.assertFalse(thread.is_alive())
            finally:stop.touch();thread.join(8)
        report=result[0];self.assertEqual(report['scheduled'],1);self.assertEqual(len(records),1)
        self.assertEqual(report['pending'],0);self.assertTrue(report['drain_complete']);self.assert_accounting(report)
    def test_stuck_child_remains_explicit_pending_after_bounded_shutdown(self):
        root=self.directory();stop=root/'stop';source="import json,sys,time\njson.loads(sys.stdin.readline())\nprint('{\"kind\":\"ready\"}',flush=True)\ntime.sleep(30)\n"
        original=self.sampler.admission
        def admit(process,index,start):original(process,index,start);stop.touch()
        with patch.object(self.sampler,'CHILD_SOURCE',source),patch.object(self.sampler,'admission',admit):
            report=self.sampler.sampler_worker(self.config(1,root),stop)
        self.assertEqual(report['pending_sequences'],[0]);self.assertEqual(report['samples'],[])
        self.assertFalse(report['drain_complete']);self.assertTrue(report['http_child_terminal']);self.assert_accounting(report)
        with self.assertRaises(MODULE['ProofError']):MODULE['sampler_summary'](report)

if __name__=='__main__':unittest.main()
