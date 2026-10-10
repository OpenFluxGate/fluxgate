"""Offline process sampler tests: local stdlib HTTP only; no cluster commands."""
import json
from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch,MagicMock

from sampler_test_support import MODULE,SamplerCase,load_sampler
M=load_sampler()

class ProcessSampler(SamplerCase):
    sampler=M

    def test_fresh_connections_exact_body_and_scheduled_latency_retained(self):
        root=self.directory()
        with self.server(delay=.015) as (port,records):
            report=self.run_for(self.config(port,root),root,.8)
        self.assert_accounting(report)
        self.assertGreaterEqual(len(report['samples']),1)
        self.assertTrue(report['drain_complete'])
        self.assertTrue(report['http_child_terminal'])
        self.assertTrue(all(sample['status']==200 and sample['body_valid'] for sample in report['samples']))
        self.assertEqual(len({record.address for record in records}),len(records))
        self.assertTrue(all(record.connection=='close' and record.key=='synthetic-secret' for record in records))
        for sample in report['samples']:
            self.assertGreaterEqual(sample['latency_ms'],sample['service_latency_ms'])
            self.assertAlmostEqual(sample['latency_ms'],sample['dispatch_lag_ms']+sample['service_latency_ms'],places=5)
        self.assertEqual((root/'positive').stat().st_mode&0o777,0o600)

    def test_wrong_body_preserved_as_invalid_not_success(self):
        root=self.directory()
        with self.server(body=b'wrong') as (port,_):report=self.run_for(self.config(port,root),root,.5)
        self.assertTrue(report['samples'])
        self.assertTrue(all(not sample['body_valid'] for sample in report['samples']))
        self.assertFalse((root/'positive').exists())

    def test_stalled_http_does_not_block_parent_scheduler_clock(self):
        root=self.directory();admitted=[]
        original=M.admission
        def record(process,index,start):
            admitted.append(time.monotonic())
            return original(process,index,start)
        with self.server(delay=.5) as (port,_):
            with patch.object(M,'admission',record):report=self.run_for(self.config(port,root),root,1.0)
        self.assert_accounting(report)
        self.assertTrue(report['drain_complete'])
        # Evidence is multiple admissions while earlier HTTP is still stalled.
        self.assertGreaterEqual(len(admitted),2)
        self.assertTrue(any(right-left<.5 for left,right in zip(admitted,admitted[1:])))
        self.assertGreater(report['peak_inflight'],1)

    def test_stop_arriving_during_original_sleep_dispatches_no_new_arrival(self):
        root=self.directory();stop=root/'stop';real_sleep=time.sleep;calls=[]
        def sleeping(seconds):stop.touch();real_sleep(min(seconds,.001))
        with self.server() as (port,_):
            with patch.object(M.time,'sleep',sleeping):
                with patch.object(M,'admission',side_effect=lambda *args:calls.append(args)):
                    report=M.sampler_worker(self.config(port,root),stop)
        self.assertEqual(calls,[]);self.assertEqual(report['scheduled'],0)
        self.assertTrue(report['drain_complete'])

    def test_pre_ready_failure_is_fail_closed_and_contains_no_secret(self):
        root=self.directory()
        with patch.object(M,'child_start',side_effect=RuntimeError('synthetic-secret')):
            report=M.sampler_worker(self.config(1,root),root/'stop')
        self.assertFalse(report['drain_complete']);self.assertEqual(report['worker_failures'],1)
        self.assertFalse(report['http_child_ready']);self.assertNotIn('synthetic-secret',json.dumps(report))

    def test_child_death_is_explicit_failure_pending_and_terminal_cleanup(self):
        root=self.directory();real=M.admission;holder=[]
        def dying(process,index,start):
            holder.append(process)
            real(process,index,start);process.kill()
        with self.server() as (port,_):
            with patch.object(M,'admission',dying):report=self.run_for(self.config(port,root),root,.5)
        self.assert_accounting(report)
        self.assertFalse(report['drain_complete']);self.assertGreater(report['worker_failures'],0)
        self.assertTrue(report['http_child_terminal'])
        self.assertTrue(all(process.poll() is not None for process in holder))

    def test_nonblocking_admission_has_small_frame_and_no_secret(self):
        process=MagicMock();process.stdin.fileno.return_value=9
        with patch.object(M.os,'write',side_effect=lambda fd,frame:len(frame)) as write:
            M.admission(process,23,100.0)
        frame=write.call_args.args[1]
        self.assertLessEqual(len(frame),256)
        self.assertNotIn(b'synthetic-secret',frame)
        self.assertEqual(json.loads(frame)['origin'],102.3)
        with patch.object(M.os,'write',side_effect=BlockingIOError):
            with self.assertRaises(BlockingIOError):M.admission(process,0,100)

    def test_24_global_pending_capacity_omissions_and_expired_drain_are_explicit(self):
        root=self.directory();stop=root/'stop';clock=[100.0];admitted=[]
        dead=threading.Event()
        class Output:
            def __iter__(self):return self
            def readline(self,size):dead.wait();return b''
            def close(self):pass
        class Process:
            stdin=SimpleNamespace(close=lambda:None)
            stdout=Output();stderr=SimpleNamespace(close=lambda:None)
            returncode=None
            def poll(self):return self.returncode
            def wait(self,timeout=None):
                if self.returncode is None:raise M.subprocess.TimeoutExpired('owned-fake',timeout)
                return self.returncode
            def kill(self):self.returncode=-9;dead.set()
        process=Process()
        real_condition=threading.Condition
        class Condition(real_condition):
            def wait(self,timeout=None):clock[0]+=timeout or 0
        def sleeping(seconds):
            clock[0]+=seconds
            if clock[0]>=103.0:stop.touch()
        fake_time=SimpleNamespace(monotonic=lambda:clock[0],monotonic_ns=lambda:int(clock[0]*1e9),
            time_ns=time.time_ns,sleep=sleeping)
        fake_threading=SimpleNamespace(Condition=Condition,BoundedSemaphore=threading.BoundedSemaphore,
            Event=threading.Event,Lock=threading.Lock,Thread=threading.Thread)
        with patch.object(M,'child_start',return_value=process),patch.object(M,'admission',side_effect=lambda p,i,s:admitted.append(i)):
            with patch.object(M,'time',fake_time),patch.object(M,'threading',fake_threading):
                report=M.sampler_worker(self.config(1,root),stop)
        self.assertEqual(len(admitted),24)
        self.assertEqual(report['pending'],24);self.assertEqual(report['peak_inflight'],24)
        self.assertTrue(any(row['reason']=='capacity' for row in report['omissions']))
        self.assertFalse(report['drain_complete']);self.assertTrue(report['http_child_terminal'])
        self.assert_accounting(report)

    def test_old_mac_process_local_clock_is_rejected_before_spawn(self):
        with patch.object(M.sys,'platform','darwin'),patch.object(M.sys,'version_info',(3,9,6)):
            with patch.object(M.subprocess,'Popen') as launch:
                with self.assertRaisesRegex(RuntimeError,'unsupported_process_clock'):M.child_start({})
                launch.assert_not_called()

    def test_generated_code_is_self_contained_and_no_threshold_change(self):
        program=MODULE['sampler_program']()
        compile(program,'<offline-process-generated>','exec')
        self.assertIn('time.sleep(max(0, deadline - time.monotonic()))',program)
        self.assertIn('if lag >= .1:',program)
        self.assertIn('threading.BoundedSemaphore(24)',program)
        self.assertNotIn('synthetic-secret',program)
        self.assertNotIn('import process_sampler',program)

    def test_blocked_marker_does_not_block_other_completions_or_doublecount(self):
        root=self.directory();entered=threading.Event();release=threading.Event();result=[]
        original=M.private_write
        def write(path,data):
            if str(path)==str(root/'positive'):
                entered.set();release.wait(10)
            return original(path,data)
        with self.server() as (port,records):
            with patch.object(M,'private_write',write):
                thread=threading.Thread(target=lambda:result.append(M.sampler_worker(self.config(port,root),root/'stop')))
                thread.start()
                try:
                    self.assertTrue(entered.wait(2))
                    time.sleep(3.2)
                    partial=json.loads((root/'progress').read_text())
                    self.assertGreaterEqual(partial['completed'],20)
                    self.assertIn(0,partial['pending_sequences'])
                    self.assertLessEqual(partial['pending'],24)
                    self.assertEqual(partial['scheduled'],partial['completed']+partial['pending']+partial['omitted_schedules'])
                    self.assertGreaterEqual(len(records),25)
                finally:
                    (root/'stop').touch();release.set();thread.join(8)
                self.assertFalse(thread.is_alive())
        self.assertTrue(result[0]['drain_complete'])
        self.assertFalse(any(row['reason']=='capacity' for row in result[0]['omissions']))

    def test_setup_failure_unconditionally_reaps_owned_child(self):
        root=self.directory();owned=[];original=M.child_start
        def start(config):
            process=original(config);owned.append(process);self.addCleanup(M.child_cleanup,process);return process
        with patch.object(M,'child_start',start),patch.object(M.threading.Thread,'start',side_effect=RuntimeError('setup')):
            with self.assertRaises(RuntimeError):M.sampler_worker(self.config(1,root),root/'stop')
        self.assertTrue(owned);self.assertTrue(all(process.poll() is not None for process in owned))

    def test_terminal_wait_interrupt_still_reaps_owned_child(self):
        root=self.directory();(root/'stop').touch();owned=[];original=M.child_start
        def start(config):
            process=original(config);owned.append(process);self.addCleanup(M.child_cleanup,process);wait=process.wait
            def interrupted(timeout=None):
                if timeout is not None:raise KeyboardInterrupt()
                return wait()
            process.wait=interrupted;return process
        with patch.object(M,'child_start',start):
            with self.assertRaises(KeyboardInterrupt):M.sampler_worker(self.config(1,root),root/'stop')
        self.assertTrue(all(process.poll() is not None for process in owned))

    def test_completion_rejects_missing_forged_and_nonfinite_values(self):
        valid={'sequence':0,'scheduled_elapsed_seconds':0,'elapsed_seconds':.01,
            'dispatch_lag_ms':10,'status':200,'body_valid':True,'latency_ms':20,
            'service_latency_ms':10,'completed_elapsed_seconds':.02}
        self.assertEqual(M.completion(valid,{0},1),0)
        variants=[{'sequence':0,'status':200,'body_valid':'yes'},
            dict(valid,latency_ms=float('nan')),dict(valid,body_valid=1),
            dict(valid,error='credentiallike-forged'),dict(valid,extra='credentiallike-forged'),
            dict(valid,dispatch_lag_ms=0),dict(valid,status=True)]
        for value in variants:
            with self.subTest(fields=list(value)):
                with self.assertRaises(ValueError):M.completion(value,{0},1)

    def test_marker_stuck_past_drain_is_explicit_pending_failure(self):
        root=self.directory();entered=threading.Event();release=threading.Event();result=[]
        original=M.private_write
        def write(path,data):
            if str(path)==str(root/'positive'):
                entered.set();release.wait(12)
            return original(path,data)
        with self.server() as (port,_):
            with patch.object(M,'private_write',write):
                thread=threading.Thread(target=lambda:result.append(M.sampler_worker(self.config(port,root),root/'stop')))
                thread.start()
                try:
                    self.assertTrue(entered.wait(2));(root/'stop').touch();thread.join(8)
                    self.assertFalse(thread.is_alive())
                    self.assertFalse(result[0]['drain_complete'])
                    self.assertEqual(result[0]['pending_sequences'],[0])
                    self.assertTrue(result[0]['http_child_terminal'])
                    self.assert_accounting(result[0])
                finally:release.set();thread.join(2)

    def test_generated_program_executes_self_contained_below_explicit_24k_budget(self):
        root=self.directory();stop=root/'stop';stop.touch()
        program=MODULE['sampler_program'](stop,root/'started')
        self.assertLessEqual(len(program.encode()),24*1024)
        process=M.subprocess.Popen([M.sys.executable,'-c',program],stdin=M.subprocess.PIPE,
            stdout=M.subprocess.PIPE,stderr=M.subprocess.PIPE,start_new_session=True)
        self.addCleanup(M.child_cleanup,process)
        output,error=process.communicate(json.dumps(self.config(1,root)).encode()+b'\n',timeout=10)
        self.assertEqual(process.returncode,0,error.decode())
        report=json.loads(output);self.assertTrue(report['drain_complete'])
        self.assertEqual(report['scheduled'],0)
        self.assertEqual((root/'started').stat().st_mode&0o777,0o600)



"""Offline controlled child protocol tests; frozen candidate unchanged."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from sampler_test_support import load_sampler

CONTROLLED=r'''import json,sys,time
config=json.loads(sys.stdin.buffer.readline())
print('{"kind":"ready"}',flush=True)
jobs=[]
def sample(job):
    now=time.monotonic()
    origin,start,index=job['origin'],job['start'],job['index']
    return {'sequence':index,'scheduled_elapsed_seconds':index*.1,'elapsed_seconds':now-start,
        'dispatch_lag_ms':max(0,now-origin)*1000,'status':200,'body_valid':True,
        'latency_ms':max(0,now-origin)*1000,'service_latency_ms':0,'completed_elapsed_seconds':now-start}
for line in sys.stdin.buffer:
    jobs.append(json.loads(line))
    if len(jobs)==2:
        second=sample(jobs[1]);first=sample(jobs[0])
        MODE
print('{"kind":"done"}',flush=True)
'''

class ProcessSamplerIPC(unittest.TestCase):
    def exercise(self,mode):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        root=Path(directory.name);stop=root/'stop'
        original=M.admission;admitted=[]
        def admission(process,index,start):
            original(process,index,start)
            admitted.append(index)
            if len(admitted)==2:stop.touch()
        code=CONTROLLED.replace('MODE',mode)
        config={'service':'offline','port':1,'path':'/','host':'offline','api_key':'synthetic-private',
                'body':'expected','progress_path':str(root/'progress'),'positive_path':str(root/'positive')}
        with patch.object(M,'CHILD_SOURCE',code),patch.object(M,'admission',admission):
            report=M.sampler_worker(config,stop)
        self.assertEqual(report['scheduled'],len(report['samples'])+report['pending']+report['omitted_schedules'])
        self.assertTrue(report['http_child_terminal'])
        return report
    def test_out_of_order_completions_preserve_origin_and_are_sorted_finally(self):
        mode="print(json.dumps({'kind':'sample','sample':second}),flush=True);print(json.dumps({'kind':'sample','sample':first}),flush=True)"
        report=self.exercise(mode)
        self.assertTrue(report['drain_complete'])
        self.assertEqual([sample['sequence'] for sample in report['samples']],[0,1])
        self.assertEqual(report['worker_failures'],0)
    def test_duplicate_completion_fails_closed_retaining_uncompleted_arrival(self):
        mode="print(json.dumps({'kind':'sample','sample':second}),flush=True);print(json.dumps({'kind':'sample','sample':second}),flush=True)"
        report=self.exercise(mode)
        self.assertFalse(report['drain_complete']);self.assertGreater(report['worker_failures'],0)
        self.assertEqual(report['pending'],1)
    def test_malformed_completion_fails_closed_without_losing_pending(self):
        mode="print(json.dumps({'kind':'sample','sample':{'sequence':'invalid'}}),flush=True)"
        report=self.exercise(mode)
        self.assertFalse(report['drain_complete']);self.assertGreater(report['worker_failures'],0)
        self.assertEqual(report['pending'],2)

    def test_trailing_frame_after_done_rejects_even_complete_samples(self):
        mode="print(json.dumps({'kind':'sample','sample':second}),flush=True);print(json.dumps({'kind':'sample','sample':first}),flush=True);print('{\"kind\":\"done\"}',flush=True);print('{\"kind\":\"done\"}',flush=True)"
        report=self.exercise(mode)
        self.assertFalse(report['drain_complete']);self.assertGreater(report['worker_failures'],0)


if __name__=='__main__':unittest.main()
