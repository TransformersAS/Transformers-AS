"""Contrato del script k6 contra HTTP simulado; NO acredita ASR del Marketplace."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which('k6'), 'k6 no instalado; contrato HTTP se ejecuta localmente con k6')
class K6ContractTests(unittest.TestCase):
    def run_scenario(self, mode, credentials=True):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                calls.append(self.path)
                status, body = 200, {}
                if self.path == '/api/auth/csrf':
                    body = {'headerName': 'X-CSRF-TOKEN', 'token': 'test'}
                elif self.path == '/api/products':
                    body = [] if mode == 'empty' else [{'id': 1}]
                elif self.path == '/api/products/1':
                    body = {'id': 1}
                else:
                    status = 404
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_POST(self):
                calls.append(self.path)
                self.rfile.read(int(self.headers.get('Content-Length', 0)))
                self.send_response(401 if mode == 'bad-login' else 204)
                self.end_headers()

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                env = os.environ.copy()
                for key in list(env):
                    if key.startswith('K6_') or key in ('P95_LIMIT_MS', 'ERROR_RATE_LIMIT', 'CATALOG_EMAIL', 'CATALOG_PASSWORD'):
                        env.pop(key, None)
                env.update(BASE_URL=f'http://127.0.0.1:{server.server_port}', VUS='1', DURATION='1s')
                if credentials:
                    env.update(CATALOG_EMAIL='contract@example.test', CATALOG_PASSWORD='contract-only')
                summary = Path(directory) / 'summary.json'
                result = subprocess.run(['k6', 'run', '--summary-export', str(summary),
                                         str(ROOT / 'scripts/k6/catalog-browse.js')],
                                        env=env, capture_output=True, text=True, timeout=25)
                data = json.loads(summary.read_text()) if summary.exists() else {}
                return result, data, calls
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_no_credentials_fails_without_registering(self):
        result, _, calls = self.run_scenario('healthy', credentials=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_empty_catalog_is_not_a_pass(self):
        result, _, calls = self.run_scenario('empty')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('/api/sellers/register', calls)

    def test_bad_login_aborts_before_catalog_load(self):
        result, _, calls = self.run_scenario('bad-login')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('/api/products', calls)

    def test_complete_roundtrip_reports_exact_counts(self):
        result, data, calls = self.run_scenario('healthy')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('/api/sellers/register', calls)
        metrics = data['metrics']
        def count(name):
            return metrics[name].get('values', metrics[name])['count']
        self.assertGreater(count('catalog_throughput'), 0)
        self.assertEqual(count('catalog_requests'), count('catalog_successes') + count('catalog_failures'))
        self.assertEqual(count('catalog_failures'), 0)
