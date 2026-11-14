import copy
import datetime as dt
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from report import CoverageError, evaluate, sanitize_alerts, validate_coverage, write_reports
from run import assert_config
from scan import Assets, canonical_url, summarize_messages
from cleanup import runner_active


class SecurityEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.policy = {'block_risk_at_or_above': 3, 'exceptions': []}
        self.alert = {'pluginId': '10038', 'alert': 'Example', 'risk': 'High', 'confidence': 'Medium',
                      'url': 'http://frontend/', 'evidence': 'Cookie: SESSION=reserved\ncsrf-value',
                      'solution': '<script>alert(1)</script>'}

    def test_redacts_secrets_and_escapes_html_without_hiding_finding(self):
        alerts = sanitize_alerts([self.alert], {'http://frontend/'}, ['reserved', 'csrf-value'])
        self.assertEqual(alerts[0]['riskcode'], 3)
        with tempfile.TemporaryDirectory() as directory:
            write_reports(directory, {'alerts': alerts, 'result': evaluate(alerts, self.policy)})
            html = (Path(directory) / 'zap-report.html').read_text()
            exported = (Path(directory) / 'zap-report.json').read_text()
        self.assertNotIn('reserved', exported)
        self.assertNotIn('csrf-value', exported)
        self.assertNotIn('<script>', html)
        self.assertIn('Example', html)
        self.assertEqual(json.loads(exported)['result']['exit_code'], 1)

    def test_high_blocks_medium_remains_visible_zero_is_explicit(self):
        alerts = sanitize_alerts([self.alert], {'http://frontend/'}, [])
        self.assertEqual(evaluate(alerts, self.policy)['status'], 'security_findings')
        alerts[0]['riskcode'] = 2
        self.assertEqual(evaluate(alerts, self.policy)['status'], 'warnings_for_review')
        self.assertEqual(evaluate([], self.policy)['status'], 'no_alerts_in_scope')

    def test_exceptions_require_exact_url_justification_and_expiry(self):
        alerts = sanitize_alerts([self.alert], {'http://frontend/'}, [])
        self.policy['exceptions'] = [{'pluginId': '10038', 'url': 'http://frontend/',
                                     'reason': 'Reviewed fixture', 'reviewer': 'team', 'expires': '2026-12-01'}]
        self.assertEqual(evaluate(alerts, self.policy, dt.date(2026, 11, 13))['temporarily_accepted'], 1)
        with self.assertRaises(ValueError):
            evaluate(alerts, self.policy, dt.date(2026, 12, 2))
        self.policy['exceptions'][0]['reason'] = ''
        with self.assertRaises(ValueError):
            evaluate(alerts, self.policy, dt.date(2026, 11, 13))

    def test_coverage_requires_real_zap_messages_valid_contract_and_role(self):
        observed = [{'path': '/api/cart', 'role': 'COMPRADOR', 'status': 200, 'contract_valid': True}]
        urls = ['http://frontend/api/cart']
        messages = [{'url': urls[0], 'method': 'GET', 'status': 200}]
        validate_coverage({'/api/cart': 'COMPRADOR'}, observed, urls, messages)
        for bad_observed, bad_urls, bad_messages in ((observed, [], messages),
                                                    (observed, urls, []),
                                                    ([dict(observed[0], role='anonymous')], urls, messages),
                                                    ([dict(observed[0], contract_valid=False)], urls, messages)):
            with self.assertRaises(CoverageError):
                validate_coverage({'/api/cart': 'COMPRADOR'}, bad_observed, bad_urls, bad_messages)

    def test_external_alert_is_technical_scope_failure(self):
        with self.assertRaises(CoverageError):
            sanitize_alerts([self.alert], {'http://third-party/'}, [])

    def test_assets_never_follow_external_urls_or_business_routes(self):
        parser = Assets()
        parser.feed('<script src="main.js"></script><script src="//third.test/x.js"></script>'
                    '<script src="https://third.test/a.js"></script><a href="/api/payments">pay</a>'
                    '<script src="/api/imports/file.js"></script><script src="../secret.js"></script>')
        self.assertEqual(parser.paths, {'/main.js'})

    def test_canonical_urls_accept_default_port_but_never_other_origin(self):
        self.assertEqual(canonical_url('http://frontend:80/api/cart'), 'http://frontend/api/cart')
        self.assertEqual(canonical_url('/api/cart'), 'http://frontend/api/cart')
        for url in ('http://third.test/', 'https://frontend/', 'http://frontend:8080/',
                    'http://frontend/?token=secret', 'http://user@frontend/'):
            with self.assertRaises(CoverageError):
                canonical_url(url)

    def test_structural_parent_nodes_never_count_as_real_coverage(self):
        raw = {'requestHeader': 'GET http://frontend/api/cart HTTP/1.1\r\n',
               'responseHeader': 'HTTP/1.1 200 OK\r\n', 'type': '0'}
        messages, structural = summarize_messages([raw])
        self.assertEqual(messages, [])
        with self.assertRaises(CoverageError):
            validate_coverage({'/api/cart': 'COMPRADOR'},
                              [{'path': '/api/cart', 'role': 'COMPRADOR', 'status': 200, 'contract_valid': True}],
                              structural, messages)
        messages, _ = summarize_messages([dict(raw, type='1')])
        self.assertEqual(len(messages), 1)
        with self.assertRaises(CoverageError):
            summarize_messages([dict(raw, type='3')])

    def test_truncated_history_is_not_accepted(self):
        with self.assertRaises(CoverageError):
            summarize_messages([{}] * 1000)

    def test_fallback_cleanup_does_not_interrupt_a_live_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.assertFalse(runner_active(path))
            (path / '.runner-active').write_text(str(os.getpid()))
            self.assertTrue(runner_active(path))

    def test_config_refuses_public_ports_networks_and_persistent_mounts(self):
        config = {'services': {s: {} for s in ('mysql', 'backend', 'frontend', 'zap')},
                  'networks': {'target': {'internal': True}}}
        config['services']['zap']['image'] = 'ghcr.io/zaproxy/zaproxy@sha256:' + 'a' * 64
        config['services']['mysql']['tmpfs'] = ['/var/lib/mysql']
        config['services']['backend']['environment'] = {
            'MARKETPLACE_DEMO_DATA': 'false', 'PAYMENTS_MOCK_ENABLED': 'false',
            'PAYMENTS_RECOVERY_ENABLED': 'false', 'NOTIFICATIONS_QUEUE_ENABLED': 'false',
            'NOTIFICATIONS_PUSH_ENABLED': 'false', 'LOGISTICS_TRACKING_POLLING_ENABLED': 'false',
            'MERCADOPAGO_ACCESS_TOKEN': '', 'GEMINI_API_KEY': '', 'SPRING_MAIL_HOST': ''}
        assert_config(config)
        variants = []
        bad = copy.deepcopy(config)
        bad['services']['zap']['ports'] = [{'published': '8090'}]
        variants.append(bad)
        bad = copy.deepcopy(config)
        bad['networks']['target']['internal'] = False
        variants.append(bad)
        bad = copy.deepcopy(config)
        bad['services']['mysql']['volumes'] = [{'type': 'volume', 'target': '/var/lib/mysql'}]
        variants.append(bad)
        for variant in variants:
            with self.assertRaises(RuntimeError):
                assert_config(variant)


if __name__ == '__main__':
    unittest.main()
