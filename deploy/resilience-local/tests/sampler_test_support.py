"""Shared offline fixtures for the actual generated process sampler; no cluster access."""
import contextlib
import http.server
import json
import os
from pathlib import Path
import runpy
import tempfile
import threading
import time
from types import ModuleType,SimpleNamespace
import unittest

SOURCE=Path(os.environ.get('FLUXGATE_SAMPLER_TEST_SOURCE',str(Path(__file__).resolve().parents[1]/'verify-credentials.py')))
MODULE=runpy.run_path(str(SOURCE))

def load_sampler():
    module=ModuleType('generated_sampler')
    exec(MODULE['sampler_program']().split('\nconfig = ',1)[0],module.__dict__)
    return module

def local_server(owner,body=b'backend',delay=0):
    records=[];enough=threading.Event()
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            record=SimpleNamespace(address=self.client_address,closed=False,
                connection=self.headers.get('Connection'),key=self.headers.get('x-api-key'))
            records.append(record)
            if len(records)>=5:enough.set()
            time.sleep(delay)
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers()
            try:self.wfile.write(body)
            except OSError:pass
            finally:record.closed=True
        def log_message(self,*args):pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    def cleanup():server.shutdown();server.server_close();thread.join()
    owner.addCleanup(cleanup)
    return server.server_port,records,enough

class SamplerCase(unittest.TestCase):
    def directory(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        return Path(directory.name)
    @contextlib.contextmanager
    def server(self,delay=0,body=b'expected'):
        port,records,_=local_server(self,body,delay)
        yield port,records
    def config(self,port,root):
        return {'service':'127.0.0.1','port':port,'path':'/','host':'offline',
            'api_key':'synthetic-secret','body':'expected','positive_path':str(root/'positive'),
            'progress_path':str(root/'progress')}
    def run_for(self,config,root,seconds):
        stop=root/'stop';stopper=threading.Timer(seconds,stop.touch);stopper.start()
        try:return self.sampler.sampler_worker(config,stop)
        finally:stopper.cancel();stopper.join()
    def assert_accounting(self,report):
        self.assertEqual(report['scheduled'],len(report['samples'])+report['pending']+report['omitted_schedules'])
        self.assertLessEqual(report['peak_inflight'],24)
