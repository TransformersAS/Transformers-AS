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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from report import assess, write_report
from seed import png, sql
from run import safe_capacity, isolation
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
