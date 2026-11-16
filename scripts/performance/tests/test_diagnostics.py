"""Pure unit tests: all Docker/HTTP commands are replaced with in-memory doubles."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from diagnostics import Observer, MYSQL_SQL, nginx_template, nginx_timing, prometheus_points
import run as runner

ROOT = Path(__file__).resolve().parents[3]
PROJECT = 'rnf-perf-123456abcdef'
COMPOSE = ['docker', 'compose', '-f', 'compose.performance.yaml', '-p', PROJECT]


class DiagnosticTests(unittest.TestCase):
    def test_prometheus_exports_only_allowed_numeric_metrics_and_routes(self):
        text = '\n'.join([
            'hikaricp_connections_active{pool="HikariPool-1"} 12',
            'hikaricp_connections_pending{pool="HikariPool-1"} 3',
            'hikaricp_connections_acquire_seconds_sum{pool="HikariPool-1"} 1.25',
            'http_server_requests_seconds_count{method="GET",uri="/api/addresses",status="200",outcome="SUCCESS",exception="none"} 50',
            'jvm_gc_pause_seconds_sum{action="end of minor GC",cause="G1 Evacuation Pause"} 0.2',
            'process_cpu_usage 0.5',
            'http_server_requests_seconds_count{uri="/api/private/person@example.test"} 1',
            'http_server_requests_seconds_count{uri="/api/addresses",email="private@example.test"} 1',
            'secret_token{value="private"} 12',
            'http_server_requests_seconds_count{uri="/api/addresses",method="GET\\nprivate"} 1',
            'hikaricp_connections_max{pool="HikariPool-1"} NaN',
        ])
        points = prometheus_points(text)
        self.assertEqual(len(points), 7)
        self.assertEqual(points[-1]['value'], None)
        saved = json.dumps(points, allow_nan=False)
        self.assertNotIn('private', saved)
        self.assertNotIn('secret', saved)
        self.assertTrue(any(p['name']=='jvm_gc_pause_seconds_sum' for p in points))

    def test_nginx_preserves_proxy_and_security_and_omits_personal_fields(self):
        original = (ROOT/'frontend/nginx.conf').read_text()
        generated = nginx_template(original)
        self.assertTrue(generated.endswith(original.replace('server {', 'server {\n    access_log /dev/stdout performance_timing;', 1)))
        log_format = generated.split('log_format performance_timing', 1)[1].split(';', 1)[0]
        for unsafe in ('$request_uri', '$args', '$remote_addr', '$http_cookie', '$http_authorization', '$request_body'):
            self.assertNotIn(unsafe, log_format)
        self.assertIn('$upstream_response_time', log_format)
        self.assertIn('$request_time', log_format)
        with self.assertRaises(ValueError): nginx_template('server {} server {}')

    def test_timing_logs_survive_sanitization_without_accepting_extra_fields(self):
        row = {'perf_timing':True,'epoch':'100.012','route':'profile','method':'PUT','status':'200',
               'request_seconds':'0.5','upstream_seconds':'0.49','connect_seconds':'0.0','header_seconds':'0.48'}
        line = 'frontend | '+json.dumps(row,separators=(',', ':'))
        self.assertEqual(json.loads(runner.sanitize_environment_log(line)),row)
        self.assertEqual(json.loads(nginx_timing(line)),row)
        row['token']='private-value'
        line='frontend | '+json.dumps(row,separators=(',', ':'))
        self.assertIsNone(nginx_timing(line))
        self.assertNotIn('private-value',runner.sanitize_environment_log(line))

    def test_sampler_commands_scoped_and_no_sql_values_exported(self):
        calls=[]
        def command(args, **kwargs):
            calls.append((args, kwargs))
            if args[:3]==['docker','container','ls']:
                text='123456abcdef backend\nabcdef123456 alien-service\n'
            elif args[:2]==['docker','exec']:
                self.assertEqual(args[2], '123456abcdef')
                text='usage_usec 100\nnr_periods 10\nnr_throttled 2\nthrottled_usec 30\n'
            elif 'backend' in args:
                text='hikaricp_connections_pending{pool="HikariPool-1"} 2\nprocess_cpu_usage 0.8\n'
            else:
                self.assertEqual(kwargs['input'],MYSQL_SQL)
                text='{"kind":"fixture_counts","addresses":500,"favorites":10,"cart_items":5}\n'
            return subprocess.CompletedProcess(args,0,stdout=text,stderr='')
        with tempfile.TemporaryDirectory() as d:
            observer=Observer(COMPOSE,PROJECT,d,'measurement-resources-100',command)
            sample=observer.sample()
        self.assertEqual(sample['cgroups']['backend']['nr_throttled'],2)
        self.assertEqual(sample['mysql'][0]['addresses'],500)
        self.assertTrue(sample['micrometer']['coverage']['hikari'])
        self.assertFalse(sample['micrometer']['coverage']['gc'])
        for args, kwargs in calls:
            self.assertEqual(kwargs['timeout'],4)
            self.assertFalse(kwargs['check'])
            if args[:3]==['docker','container','ls']:
                self.assertIn('label=com.docker.compose.project='+PROJECT,args)
            elif args[:2]!=['docker','exec']:
                self.assertEqual(args[:len(COMPOSE)],COMPOSE)
        self.assertNotIn('SELECT DIGEST_TEXT',MYSQL_SQL)
        self.assertNotIn('SQL_TEXT',MYSQL_SQL)
        self.assertNotIn('PROCESSLIST',MYSQL_SQL)
        self.assertIn("SCHEMA_NAME='performance_fixture'",MYSQL_SQL)
        self.assertNotIn('UPDATE ',MYSQL_SQL)

    def test_missing_observability_is_not_reported_as_zero(self):
        def command(args,**kwargs): raise subprocess.TimeoutExpired(args,4)
        with tempfile.TemporaryDirectory() as d:
            observer=Observer(COMPOSE,PROJECT,d,'test',command)
            sample=observer.sample()
            for name in ('mysql','micrometer','cgroups'):
                self.assertEqual(sample[name],{'unavailable':'TimeoutExpired'})
            self.assertGreaterEqual(sample['collection_seconds'],0)
            self.assertTrue(observer.stop()['stopped'])
        with self.assertRaises(ValueError): Observer(COMPOSE,'production', '.', 'test',command)

    def test_background_observer_flushes_and_stops_without_real_commands(self):
        with tempfile.TemporaryDirectory() as d:
            observer=Observer(COMPOSE,PROJECT,d,'test',None,interval=100)
            with patch.object(observer,'sample',return_value={'test':True}) as sample:
                observer.start()
                # Event synchronization, not a load or service startup.
                import time
                deadline=time.monotonic()+2
                while (not observer.path.exists() or not observer.path.stat().st_size) and time.monotonic()<deadline:
                    time.sleep(.01)
                status=observer.stop()
                self.assertEqual(status,{'stopped':True,'failure':None})
                self.assertEqual(json.loads(observer.path.read_text()),{'test':True})
                self.assertEqual(sample.call_count,1)

    def test_global_guard_still_counts_external_cpu(self):
        with patch.object(runner,'cmd',side_effect=[
                subprocess.CompletedProcess([],0,stdout='{"MemTotal":9000000000,"NCPU":10}'),
                subprocess.CompletedProcess([],0,stdout='\n'.join(json.dumps(r) for r in [
                    {'Name':runner.PROJECT+'-backend-1','MemUsage':'500MiB / 1GiB','CPUPerc':'500%'},
                    {'Name':'unrelated-service','MemUsage':'100MiB / 1GiB','CPUPerc':'250%'}]))]):
            sample=runner.capacity()
        self.assertEqual(sample['cpu_percent_of_one_core'],750)
        self.assertEqual(sample['own_cpu_percent_of_one_core'],500)
        self.assertEqual(sample['external_cpu_percent_of_one_core'],250)
        self.assertFalse(runner.safe_capacity(sample))


if __name__=='__main__': unittest.main()
