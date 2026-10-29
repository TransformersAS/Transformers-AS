"""Pruebas del verificador de balanceo y de la configuración que lo hace posible; no miden el sistema real."""
import importlib.util
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('quality_run', ROOT / 'scripts/quality/run.py')
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)


def served(frontend, backend):
    return {'X-Served-By-Frontend': frontend, 'X-Served-By-Backend': backend}


class DistributionTests(unittest.TestCase):
    def test_the_backend_that_answered_is_the_last_hop(self):
        self.assertEqual(quality.served_by(served('web1', '10.0.1.5:8080, 10.0.1.6:8080')),
                         ('web1', ['10.0.1.5:8080', '10.0.1.6:8080']))
        self.assertEqual(quality.served_by({}), ('', []))

    def test_even_split_between_two_backends_is_balanced(self):
        distribution = quality.Distribution()
        for index in range(10):
            distribution.add(200, served('web1', f'10.0.1.{5 + index % 2}:8080'))
        result = distribution.result(0.3, {'10.0.1.5': {'task': 't1', 'node': 'pc1'}})
        self.assertTrue(result['balanced'])
        self.assertEqual(result['backends']['10.0.1.5:8080']['task'], {'task': 't1', 'node': 'pc1'})
        self.assertEqual(result['backends']['10.0.1.6:8080']['share'], 0.5)

    def test_a_single_backend_is_not_balanced(self):
        distribution = quality.Distribution()
        for _ in range(10):
            distribution.add(200, served('web1', '10.0.0.9:8080'))
        self.assertFalse(distribution.result(0.3)['balanced'])

    def test_a_lopsided_split_or_a_failure_is_not_balanced(self):
        lopsided = quality.Distribution()
        for index in range(10):
            lopsided.add(200, served('web1', '10.0.1.5:8080' if index else '10.0.1.6:8080'))
        self.assertFalse(lopsided.result(0.3)['balanced'])

        failing = quality.Distribution()
        for index in range(10):
            failing.add(200 if index else 502, served('web1', f'10.0.1.{5 + index % 2}:8080'))
        self.assertFalse(failing.result(0.3)['balanced'])

    def test_a_retry_on_the_other_backend_is_counted(self):
        distribution = quality.Distribution()
        distribution.add(200, served('web1', '10.0.1.5:8080, 10.0.1.6:8080'))
        self.assertEqual(distribution.result(0.3)['retried_on_another_backend'], 1)
        self.assertEqual(distribution.backends['10.0.1.6:8080'], 1)


class ConfigurationTests(unittest.TestCase):
    nginx = (ROOT / 'frontend/nginx.conf').read_text()
    dockerfile = (ROOT / 'frontend/Dockerfile').read_text()
    stack = (ROOT / 'stack.yml').read_text()

    def test_nginx_resolves_each_replica_and_retries_on_another(self):
        self.assertRegex(self.nginx, r'server \$\{BACKEND_UPSTREAM_HOST\}:8080 resolve')
        self.assertIn('resolver 127.0.0.11', self.nginx)
        self.assertIn('zone backend_upstream', self.nginx)
        self.assertIn('proxy_next_upstream_tries 2;', self.nginx)
        self.assertRegex(self.nginx, r'proxy_connect_timeout [1-5]s;')

    def test_nginx_reports_who_served_each_request(self):
        self.assertIn('add_header X-Served-By-Frontend $hostname always;', self.nginx)
        self.assertIn('add_header X-Served-By-Backend $upstream_addr always;', self.nginx)

    def test_template_only_substitutes_backend_variables(self):
        self.assertIn('/etc/nginx/templates/default.conf.template', self.dockerfile)
        self.assertIn('NGINX_ENVSUBST_FILTER=^BACKEND_', self.dockerfile)
        # Las variables de nginx van en minúscula: si alguna fuera BACKEND_* la plantilla la borraría.
        used = set(re.findall(r'\$\{?([A-Za-z_]+)', self.nginx))
        self.assertEqual({name for name in used if name.startswith('BACKEND_')}, {'BACKEND_UPSTREAM_HOST'})

    def test_swarm_points_nginx_at_the_individual_tasks(self):
        self.assertIn('BACKEND_UPSTREAM_HOST: tasks.backend', self.stack)


if __name__ == '__main__':
    unittest.main()
