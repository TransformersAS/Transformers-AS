import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from report import assess, write_report
from seed import png, sql
from run import safe_capacity, isolation
import run as runner
ROOT=Path(__file__).resolve().parents[3]


class EvidenceTests(unittest.TestCase):
    def data(self):
        def metric(**kwargs):return {'values':kwargs}
        return {'performance':{'scenario':'catalog','vus':50,'minimum_samples':20,'run_kind':'measurement',
                               'operations':{'catalog_list':[3000,'RNF-016']},'measurement_seconds':60},
                'metrics':{'catalog_list_attempts':metric(count=100),'catalog_list_successes':metric(count=100),
                           'catalog_list_ms':metric(avg=80,**{'p(95)':120},max=200),
                           'catalog_list_success_ms':metric(**{'p(95)':120}),
                           'measurement_elapsed_ms':metric(max=60000),'measured_vus':metric(count=50),
                           'started_flows':metric(count=50),'finished_flows':metric(count=50),
                           'contract_errors':metric(rate=0),'flow_errors':metric(rate=0),
                           'flow_ms':metric(avg=100,**{'p(95)':200}),'measured_requests':metric(count=100)}}

    def test_missing_samples_never_pass(self):
        data=self.data();data['metrics']['catalog_list_successes']['values']['count']=0
        self.assertEqual(assess(data,0)['state'],'No evaluado')

    def test_contract_errors_and_incomplete_iterations_invalidate_measurement(self):
        for name,key,value in [('contract_errors','rate',.001),('finished_flows','count',49),('measured_vus','count',49)]:
            data=self.data();data['metrics'][name]['values'][key]=value
            self.assertEqual(assess(data,0)['state'],'No evaluado')

    def test_official_p95_and_strict_error_boundary(self):
        data=self.data();self.assertEqual(assess(data,0)['state'],'Cumple')
        data['metrics']['catalog_list_ms']['values']['p(95)']=3001
        self.assertEqual(assess(data,99)['state'],'No cumple')
        data=self.data();data['metrics']['catalog_list_successes']['values']['count']=98
        self.assertEqual(assess(data,99)['state'],'No cumple')

    def test_report_escapes_untrusted_metadata_and_keeps_failures(self):
        with tempfile.TemporaryDirectory() as d:
            write_report(d,{'risks':['<script>unsafe</script>']},[assess(self.data(),99)])
            page=(Path(d)/'performance-executive.html').read_text()
            self.assertNotIn('<script>',page);self.assertIn('&lt;script&gt;',page)
            self.assertIn('No cumple',page);self.assertIn('no fue verificado',page)

    def test_no_results_are_not_a_pass(self):
        self.assertEqual(assess({},3)['state'],'No evaluado')

    def test_invalid_contract_keeps_observed_breaches_separate(self):
        data=self.data()
        data['metrics']['contract_errors']['values']['rate']=.001
        data['metrics']['flow_ms']['values']['p(95)']=12463.7
        data['metrics']['flow_errors']['values']['rate']=.0504
        result=assess(data,99)
        self.assertEqual(result['state'],'No evaluado')
        self.assertFalse(result['valid_evidence'])
        self.assertEqual({b['metric'] for b in result['observed_performance_breaches']},
                         {'flow_p95_ms','flow_error_rate'})
        with tempfile.TemporaryDirectory() as d:
            write_report(d,{},[result])
            page=(Path(d)/'performance-executive.html').read_text()
            self.assertIn('No evaluado',page)
            self.assertIn('Incumplimientos de rendimiento observados',page)

    def test_historical_summary_replay_does_not_change_approval_or_metrics(self):
        artifact=ROOT/'artifacts/performance/rnf-perf-5029cca0fe4c'
        if not artifact.exists(): self.skipTest('Historical local evidence unavailable')
        summary=json.loads((artifact/'performance-summary.json').read_text())
        for previous in summary['runs']:
            label=f"{previous['run_kind']}-{previous['scenario']}-{previous['vus']}"
            raw=json.loads((artifact/'results'/(label+'.json')).read_text())
            result=assess(raw,previous['k6_exit_code'])
            self.assertEqual({k:v for k,v in result.items() if k!='observed_performance_breaches'},previous)

    def test_capacity_guard(self):
        self.assertFalse(safe_capacity({'estimated_free_bytes':100,'cpu_percent_of_one_core':0,'docker_cpus':10},True))
        self.assertFalse(safe_capacity({'estimated_free_bytes':4*1024**3,'cpu_percent_of_one_core':800,'docker_cpus':10}))

    def test_seed_contains_expected_active_population_and_owned_resources(self):
        text=sql()
        self.assertEqual(text.count('INSERT INTO products('),1000)
        self.assertEqual(text.count('INSERT INTO user_accounts('),100)
        self.assertEqual(text.count('INSERT INTO product_reviews('),26)
        self.assertEqual(text.count('INSERT INTO addresses('),100)
        self.assertTrue(png(32).startswith(b'\x89PNG\r\n\x1a\n'))


class RunnerDiagnosticsTests(unittest.TestCase):
    def test_sampling_failure_still_preserves_partial_evidence_and_collects_logs(self):
        class Process:
            returncode=None
            def poll(self): return self.returncode
            def terminate(self): self.returncode=-15
            def wait(self,timeout): return self.returncode
        with tempfile.TemporaryDirectory() as d, patch.object(runner,'OUT',Path(d)), \
                patch.object(runner,'reset'), patch.object(runner.subprocess,'Popen',return_value=Process()), \
                patch.object(runner,'Observer') as observer, \
                patch.object(runner,'capacity',side_effect=RuntimeError('sampling failed')), \
                patch.object(runner,'collect_environment_logs') as capture:
            observer.return_value.stop.return_value={'stopped':True,'failure':None}
            with self.assertRaisesRegex(RuntimeError,'sampling failed'):
                runner.run_load('resources',100,False,60,10)
            self.assertEqual(json.loads((Path(d)/'measurement-resources-100-resources.json').read_text()),[])
            self.assertTrue((Path(d)/'measurement-resources-100.log').exists())
            capture.assert_called_once_with('measurement-resources-100')

    def test_scoped_bounded_logs_and_capture_failure_do_not_skip_other_services(self):
        calls=[]
        def fake_cmd(args, **kwargs):
            calls.append((args,kwargs))
            if args[-1]=='frontend': raise subprocess.TimeoutExpired(args,30)
            return subprocess.CompletedProcess(args,0,stdout='x'*600000,stderr='')
        with tempfile.TemporaryDirectory() as d, patch.object(runner,'OUT',Path(d)), patch.object(runner,'cmd',side_effect=fake_cmd):
            runner.collect_environment_logs('measurement-resources-100')
            manifest=json.loads((Path(d)/'measurement-resources-100-environment-logs.json').read_text())
            self.assertEqual([m['service'] for m in manifest],['backend','frontend','mysql'])
            self.assertEqual(manifest[1]['capture_error'],'TimeoutExpired')
            for item in (manifest[0],manifest[2]):
                self.assertTrue(item['truncated'])
                self.assertLessEqual((Path(d)/item['file']).stat().st_size,512*1024)
        for args,kwargs in calls:
            self.assertEqual(args[:len(runner.COMPOSE)],runner.COMPOSE)
            self.assertIn(runner.PROJECT,args)
            self.assertEqual(args[len(runner.COMPOSE):],['logs','--no-color','--timestamps','--tail','1000',args[-1]])
            self.assertEqual(kwargs,{'timeout':30,'check':False})

    def test_environment_logs_redact_values_but_keep_technical_failure(self):
        raw=('2026-11-16T17:42:33Z java.lang.OutOfMemoryError: Java heap space\n'
             '2026-11-16T17:42:34Z Cookie: SESSION=private\n'
             'password='+runner.PASSWORD+'\n'+runner.HASH+'\n'
             'Authorization: Bearer private-token\n'
             'email=person@example.test phone=3001234567\n'
             '192.168.1.2 GET /api/products?credential=private\n'
             'request: "GET /api/profile?name=private HTTP/1.1"\n'
             "SQL error value 'Private Name'\n")
        safe=runner.sanitize_environment_log(raw)
        for forbidden in (runner.PASSWORD,runner.HASH,'SESSION=private','private-token','person@example.test',
                          '3001234567','192.168.1.2','name=private','Private Name'):
            self.assertNotIn(forbidden,safe)
        self.assertIn('java.lang.OutOfMemoryError: Java heap space',safe)
        self.assertIn('2026-11-16T17:42:34Z',safe)


@unittest.skipUnless(shutil.which('k6'),'k6 local no disponible')
class K6WorkloadContractTests(unittest.TestCase):
    def execute(self, broken=False):
        calls=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_GET(self):
                calls.append(self.path)
                body=None
                if self.path=='/api/products':body=[{'id':i,'name':'Fixture','active':True} for i in range(1,1001)]
                elif self.path.startswith('/performance/'):
                    content=png(32);self.send_response(200);self.end_headers();self.wfile.write(content);return
                elif self.path.startswith('/api/products/'):
                    i=int(self.path.split('/')[-1]);n=[1,3,5][i-1]
                    body={'id':999 if broken else i,'imageUrls':['/performance/small.png']*n,
                          'variants':[{}]*n,'reviewCount':[1,5,20][i-1]}
                elif self.path.startswith('/api/reviews/products/'):
                    i=int(self.path.split('/')[4]);n=[1,5,20][i-1]
                    body=({'productId':i,'count':n,'average':5} if self.path.endswith('/summary') else
                          [{'productId':i,'rating':5}]*n)
                self.send_response(200 if body is not None else 404);self.send_header('Content-Type','application/json');self.end_headers()
                self.wfile.write(json.dumps(body).encode())
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                output=Path(directory)/'summary.json'
                env={k:v for k,v in os.environ.items() if k in ('PATH','HOME','TMPDIR')}
                env.update(BASE_URL=f'http://127.0.0.1:{server.server_port}',DISPOSABLE_PERFORMANCE='1',
                           SCENARIO='catalog',VUS='3',RUN_KIND='smoke',MEASURE_SECONDS='2',
                           WARMUP_SECONDS='0',RESULT_PATH=str(output),K6_NO_USAGE_REPORT='true')
                result=subprocess.run(['k6','run',str(ROOT/'scripts/k6/performance.js')],env=env,
                                      text=True,capture_output=True,timeout=25)
                data=json.loads(output.read_text()) if output.exists() else {}
                return result,data,calls
        finally:server.shutdown();server.server_close();thread.join()

    def test_real_k6_contract_roundtrip_counts_only_relevant_requests(self):
        result,data,calls=self.execute()
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertEqual(assess(data,result.returncode)['state'],'Cumple')
        self.assertNotIn('/api/auth/login',calls)
        self.assertFalse(any('/payments' in path or '/sellers' in path for path in calls))

    def test_http_200_with_wrong_contract_is_not_success(self):
        result,data,_=self.execute(True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(assess(data,result.returncode)['state'],'No evaluado')


if __name__=='__main__':unittest.main()
