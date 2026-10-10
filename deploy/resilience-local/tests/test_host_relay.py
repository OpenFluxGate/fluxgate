"""Owned offline relay/host lifecycle regressions; no cluster commands."""
import asyncio
import contextlib
import inspect
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SOURCE=Path(__file__).resolve().parents[1]/'verify-credentials.py'
M=runpy.run_path(str(SOURCE))

class HostLifecycle(unittest.TestCase):
    def exercise(self,fail=False):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup);root=Path(directory.name)
        proof=M['Proof'].__new__(M['Proof']);proof.work=root;proof.ns='offline';proof.api_key='synthetic-secret'
        proof.results={};proof.f={'gateway_service':'gateway','gateway_namespace':'gateway-ns','gateway_host':'offline',
            'load_path':'/load','kubeconfig':'offline','context':'offline'}
        proof.get=lambda *a,**kw:{'spec':{'ports':[{'port':80,'targetPort':10080}],'selector':{'app':'gateway'}}}
        resources={};events=[];commands=[]
        def kube(*args,data=None,**kw):
            if args[0]=='create':
                obj=json.loads(data);kind='pod' if obj['kind']=='Pod' else 'networkpolicy'
                obj['metadata']['uid']=kind+'-uid';resources[kind]=obj
                return SimpleNamespace(returncode=0,stdout=json.dumps(obj).encode())
            return SimpleNamespace(returncode=0,stdout=b'')
        proof.kube=kube
        def cleanup(name,uids):events.append('delete');return {'pod_absent':True,'networkpolicy_absent':True,'ownership_checked':True}
        proof.cleanup_sampler_resources=cleanup
        @contextlib.contextmanager
        def forward(*a,**kw):events.append('forward-open');yield 12345;events.append('forward-close')
        proof.forward=forward
        class Process:
            def __init__(self,args,**kw):
                commands.append(args);self.returncode=None
                class Input(io.BytesIO):
                    def write(inner,data):
                        config=json.loads(data)
                        if 'positive_path' in config:Path(config['positive_path']).write_text('yes')
                        return super().write(data)
                self.stdin=Input()
            def poll(self):return self.returncode
            def communicate(self,**kw):
                events.append('host-drain')
                if fail:raise M['subprocess'].TimeoutExpired('owned-host',15)
                self.returncode=0
                return json.dumps({'scheduled':1,'omitted_schedules':0,'samples':[{'status':200,'body_valid':True}]}).encode(),b''
            def terminate(self):events.append('host-terminate');self.returncode=-15
            def kill(self):events.append('host-kill');self.returncode=-9
            def wait(self,**kw):events.append('host-wait');return self.returncode
        with patch.object(M['subprocess'],'Popen',Process):
            if fail:
                with self.assertRaises(M['subprocess'].TimeoutExpired):
                    with proof.traffic_sampler('unit'):pass
            else:
                with proof.traffic_sampler('unit'):pass
        return resources,events,commands,proof
    def test_owned_controls_host_stdin_and_forward_lifetime(self):
        resources,events,commands,proof=self.exercise()
        pod=resources['pod'];policy=resources['networkpolicy'];spec=pod['spec'];container=spec['containers'][0]
        self.assertEqual(policy['spec']['policyTypes'],['Ingress','Egress']);self.assertEqual(policy['spec']['ingress'],[])
        self.assertFalse(spec['automountServiceAccountToken']);self.assertTrue(spec['securityContext']['runAsNonRoot'])
        self.assertTrue(container['securityContext']['readOnlyRootFilesystem']);self.assertEqual(container['securityContext']['capabilities']['drop'],['ALL'])
        self.assertEqual(container['resources'],{'requests':{'cpu':'25m','memory':'32Mi'},'limits':{'cpu':'250m','memory':'128Mi'}})
        self.assertEqual(commands[0][0],sys.executable);self.assertNotIn(proof.api_key,str(commands)+str(pod))
        self.assertLess(events.index('host-drain'),events.index('forward-close'));self.assertLess(events.index('forward-close'),events.index('delete'))
        self.assertIn('owned relay',proof.results['rotation_traffic']['unit']['transport'])
    def test_host_drain_failure_still_closes_forward_before_owned_resource_cleanup(self):
        _,events,_,_=self.exercise(fail=True)
        self.assertIn('host-terminate',events);self.assertLess(events.index('host-wait'),events.index('forward-close'))
        self.assertIn('delete',events);self.assertLess(events.index('forward-close'),events.index('delete'))
    def test_invalid_gateway_selector_rejects_before_any_resource_creation(self):
        for selector in ({}, None, [], {'app': ''}, {'': 'gateway'}, {'app': 1}):
            with self.subTest(selector=selector), tempfile.TemporaryDirectory() as directory:
                proof=M['Proof'].__new__(M['Proof']);proof.work=Path(directory);proof.ns='offline'
                proof.f={'gateway_service':'gateway','gateway_namespace':'gateway-ns'}
                proof.get=lambda *a,**kw:{'spec':{'ports':[{'port':80,'targetPort':10080}],'selector':selector}}
                calls=[];proof.kube=lambda *a,**kw:calls.append(a)
                with self.assertRaisesRegex(M['ProofError'],'selector required'):
                    with proof.traffic_sampler('unit'):pass
                self.assertEqual(calls,[])
                self.assertEqual(list(proof.work.iterdir()),[])

    def test_forward_is_loopback_only_and_kill_wait_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            proof=M['Proof'].__new__(M['Proof']);proof.work=Path(directory);proof.ns='offline'
            proof.f={'kubeconfig':'offline','context':'offline'};calls=[]
            class Process:
                def __init__(self,args,**kw):calls.append(args)
                def poll(self):return None
                def terminate(self):calls.append('terminate')
                def kill(self):calls.append('kill')
                def wait(self,timeout):
                    calls.append(('wait',timeout))
                    if calls.count(('wait',timeout))==1:raise M['subprocess'].TimeoutExpired('owned',timeout)
                    return -9
            with patch.object(M['subprocess'],'Popen',Process),patch.object(M['socket'],'create_connection',return_value=contextlib.nullcontext()):
                with proof.forward('pod/owned-relay',18080):pass
            self.assertIn('--address',calls[0]);self.assertIn('127.0.0.1',calls[0])
            self.assertEqual(calls[1:],['terminate',('wait',5),'kill',('wait',5)])

class Relay(unittest.IsolatedAsyncioTestCase):
    async def test_empty_readiness_connection_never_opens_upstream(self):
        self.assertIn('relay_connection',M)
        state={'active':0};reader=SimpleNamespace(read=lambda n:asyncio.sleep(0,result=b''));writer=FakeWriter()
        with patch.object(asyncio,'open_connection') as opening:
            await M['relay_connection'](reader,writer,{'host':'offline','port':80},state)
        opening.assert_not_called();self.assertTrue(writer.closed);self.assertEqual(state['active'],0)
    async def test_capacity_rejected_immediately_without_queue_or_upstream(self):
        self.assertIn('relay_connection',M)
        state={'active':24};writer=FakeWriter()
        with patch.object(asyncio,'open_connection') as opening:
            await M['relay_connection'](None,writer,{'host':'offline','port':80},state)
        opening.assert_not_called();self.assertTrue(writer.closed);self.assertEqual(state['active'],24)
    async def test_backpressure_bounds_first_read_and_chunk_size(self):
        self.assertIn('relay_connection',M)
        gate=asyncio.Event();calls=[];chunks=[b'x'*65536,b'']
        async def read(n):calls.append(n);return chunks.pop(0)
        writer=FakeWriter();upstream=FakeWriter(gate);remote=SimpleNamespace(read=lambda n:asyncio.sleep(0,result=b''))
        with patch.object(asyncio,'open_connection',return_value=(remote,upstream)):
            task=asyncio.create_task(M['relay_connection'](SimpleNamespace(read=read),writer,{'host':'offline','port':80},{'active':0}))
            await asyncio.sleep(.01);self.assertEqual(calls,[65536]);self.assertEqual(len(upstream.data),65536)
            gate.set();await asyncio.wait_for(task,1)
        self.assertTrue(writer.closed and upstream.closed);self.assertTrue(all(n==65536 for n in calls))
    async def test_connect_failure_closes_client_without_retry(self):
        self.assertIn('relay_connection',M)
        writer=FakeWriter();state={'active':0};reader=SimpleNamespace(read=lambda n:asyncio.sleep(0,result=b'GET'))
        with patch.object(asyncio,'open_connection',side_effect=ConnectionRefusedError) as opening:
            await M['relay_connection'](reader,writer,{'host':'offline','port':80},state)
        self.assertEqual(opening.call_count,1);self.assertTrue(writer.closed);self.assertEqual(state['active'],0)

    async def test_real_host_sampler_preserves_wrong_and_truncated_bodies_and_fresh_upstreams(self):
        peers=[]; count=0; state={'active':0}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);stop=root/'stop'
            async def backend(reader,writer):
                nonlocal count
                await reader.readuntil(b'\r\n\r\n')
                peers.append(writer.get_extra_info('peername'));count+=1
                body=b'wrong' if count==1 else b'marker'
                length=len(body)+10 if count==2 else len(body)
                writer.write(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(length).encode()+b'\r\nConnection: close\r\n\r\n'+body)
                await writer.drain()
                if count==3:stop.write_text('stop')
                writer.close();await writer.wait_closed()
            backend_server=await asyncio.start_server(backend,'127.0.0.1',0)
            backend_port=backend_server.sockets[0].getsockname()[1]
            relay_server=await asyncio.start_server(lambda r,w:M['relay_connection'](r,w,{'host':'127.0.0.1','port':backend_port},state),'127.0.0.1',0,limit=65536)
            relay_port=relay_server.sockets[0].getsockname()[1]
            process=None
            try:
                process=await asyncio.create_subprocess_exec(sys.executable,'-c',M['sampler_program'](str(stop),str(root/'started')),
                    stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
                config={'service':'127.0.0.1','port':relay_port,'host':'offline','path':'/load','api_key':'synthetic',
                        'body':'marker','positive_path':str(root/'positive'),'progress_path':str(root/'progress')}
                output,error=await asyncio.wait_for(process.communicate(json.dumps(config).encode()),10)
                self.assertEqual(process.returncode,0,error.decode())
                report=json.loads(output);samples=report['samples']
                self.assertEqual(len(samples),3);self.assertEqual(len(set(peers)),3)
                self.assertFalse(samples[0]['body_valid']);self.assertFalse(samples[1]['body_valid'])
                self.assertIn('IncompleteRead',samples[1]['error']);self.assertTrue(samples[2]['body_valid'])
                self.assertEqual(report['omitted_schedules'],0);self.assertTrue(report['drain_complete'])
                self.assertEqual((root/'positive').read_bytes(),b'yes')
                self.assertEqual((root/'progress').stat().st_mode & 0o777,0o600)
            finally:
                if process is not None and process.returncode is None:
                    process.kill();await process.wait()
                relay_server.close();backend_server.close()
                await relay_server.wait_closed();await backend_server.wait_closed()
        self.assertEqual(state['active'],0)

    async def test_real_half_close_forwards_complete_response_before_closing(self):
        state={'active':0}
        async def backend(reader,writer):
            request=await reader.read()
            writer.write(b'response:'+request);await writer.drain()
            writer.close();await writer.wait_closed()
        backend_server=await asyncio.start_server(backend,'127.0.0.1',0)
        relay_server=await asyncio.start_server(lambda r,w:M['relay_connection'](r,w,
            {'host':'127.0.0.1','port':backend_server.sockets[0].getsockname()[1]},state),'127.0.0.1',0)
        try:
            reader,writer=await asyncio.open_connection('127.0.0.1',relay_server.sockets[0].getsockname()[1])
            writer.write(b'opaque');await writer.drain();writer.write_eof()
            self.assertEqual(await asyncio.wait_for(reader.read(),2),b'response:opaque')
            writer.close();await writer.wait_closed()
        finally:
            relay_server.close();backend_server.close()
            await relay_server.wait_closed();await backend_server.wait_closed()

class FakeWriter:
    def __init__(self,gate=None):self.closed=False;self.data=b'';self.gate=gate
    def write(self,data):self.data+=data
    async def drain(self):
        if self.gate is not None:await self.gate.wait()
    def can_write_eof(self):return True
    def write_eof(self):pass
    def close(self):self.closed=True
    async def wait_closed(self):pass

if __name__=='__main__':unittest.main()
