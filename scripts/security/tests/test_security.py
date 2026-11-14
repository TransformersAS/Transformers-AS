import copy
import datetime as dt
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from report import CoverageError, evaluate, group_alerts, sanitize_alerts, validate_coverage, write_reports
from run import assert_config, finalize_executive_report
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


class ExecutiveReportTests(unittest.TestCase):
    def setUp(self):
        specifications = [
            ('10036', 'Server Leaks Version Information', 1, 24),
            ('10021', 'X-Content-Type-Options Header Missing', 1, 14),
            ('10096', 'Timestamp Disclosure - Unix', 1, 5),
            ('10020', 'Missing Anti-clickjacking Header', 2, 1),
            ('10038', 'Content Security Policy Header Not Set', 2, 1),
            ('90003', 'Sub Resource Integrity Attribute Missing', 2, 1),
            ('10003', 'Vulnerable JS Library', 3, 1),
            ('10109', 'Modern Web Application', 0, 1),
        ]
        self.alerts = [dict(pluginId=plugin, alert=name, riskcode=risk, confidencecode=3,
                            url=f'http://frontend/resource-{i}.js', evidence='Evidencia de prueba sanitizada',
                            review_status='Pendiente de revisión; no confirmada')
                       for plugin, name, risk, count in specifications for i in range(count)]
        self.policy = {'block_risk_at_or_above': 3, 'exceptions': []}
        self.data = {'alerts': self.alerts, 'result': evaluate(self.alerts, self.policy),
                     'technical_status': 'ok', 'version': '2.17.0', 'messages_count': 24,
                     'duration_seconds': 30.035, 'login_scanned': False,
                     'coverage': [dict(path='/api/cart', role='COMPRADOR', status=200, contract_valid=True)],
                     'exclusions': ['pagos y webhooks', 'registro CU-12']}

    def render(self, data=None, metadata=None):
        with tempfile.TemporaryDirectory() as directory:
            write_reports(directory, self.data if data is None else data)
            if metadata is not None:
                finalize_executive_report(directory, metadata)
            return {name: (Path(directory) / name).read_text() for name in
                    ('zap-report.json', 'zap-report.html', 'zap-resumen-ejecutivo.html')}

    def test_groups_repeated_alerts_without_confusing_instances_with_types(self):
        groups = group_alerts(self.alerts)
        self.assertEqual(len(groups), 8)
        repeated = next(instances for key, instances in groups if key[0] == '10036')
        self.assertEqual(len(repeated), 24)
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertEqual(page.count('<article class="finding '), 8)
        self.assertIn('24 alertas · 24 ubicaciones distintas', page)
        self.assertIn('48 alertas no equivalen necesariamente a 48 vulnerabilidades distintas', page)

    def test_group_key_includes_rule_name_and_risk(self):
        base = self.alerts[0]
        self.assertEqual(len(group_alerts([base, dict(base), dict(base, alert='Otro nombre'),
                                          dict(base, riskcode=2), dict(base, pluginId='future')])), 4)

    def test_exact_counts_are_derived_from_alerts_not_stale_summary(self):
        self.data['result']['alerts_by_risk'] = {'3': 999}
        page = self.render()['zap-resumen-ejecutivo.html']
        for number, label in ((1, 'Alertas altas'), (3, 'Alertas medias'), (43, 'Alertas bajas'),
                              (1, 'Avisos informativos'), (48, 'Total de alertas'),
                              (8, 'Tipos de hallazgos'), (24, 'Solicitudes inspeccionadas')):
            self.assertIn(f'<strong>{number}</strong><span>{label}</span>', page)

    def test_zero_alerts_are_reported_with_limitations(self):
        self.data['alerts'] = []
        self.data['result'] = evaluate([], self.policy)
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertIn('No se detectaron alertas en el tráfico inspeccionado', page)
        self.assertIn('no demuestra ausencia de vulnerabilidades', page)
        self.assertIn('<strong>0</strong><span>Total de alertas</span>', page)
        self.assertNotIn('<article class="finding ', page)

    def test_blocking_high_still_generates_all_three_reports_and_preserves_exit(self):
        files = self.render()
        self.assertEqual(len(files), 3)
        self.assertEqual(json.loads(files['zap-report.json'])['result']['exit_code'], 1)
        page = files['zap-resumen-ejecutivo.html']
        self.assertIn('Completado correctamente', page)
        self.assertIn('evaluación no aprobada', page)
        self.assertIn('Biblioteca JavaScript con posibles vulnerabilidades', page)

    def test_priority_uses_real_detector_signature_without_confirming_vulnerability(self):
        high = next(a for a in self.alerts if a['riskcode'] == 3)
        high['evidence'] = '["ng-version","18.2.14"]'
        high['url'] = 'http://frontend/main.js'
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertIn('cadena de versión Angular 18.2.14 en http://frontend/main.js', page)
        self.assertIn('su aplicabilidad está pendiente de revisión', page)
        self.assertIn('1 alerta · 1 ubicación distinta', page)

    def test_accepted_alert_remains_visible_without_changing_policy(self):
        high = next(a for a in self.alerts if a['riskcode'] == 3)
        self.policy['exceptions'] = [{'pluginId': high['pluginId'], 'url': high['url'],
                                     'reason': 'Justificación de prueba', 'reviewer': 'Equipo', 'expires': '2026-12-01'}]
        self.data['result'] = evaluate(self.alerts, self.policy, dt.date(2026, 11, 13))
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertEqual(self.data['result']['exit_code'], 0)
        self.assertIn('Aceptada temporalmente', page)
        self.assertIn('Justificación de prueba', page)
        self.assertIn('Revisar la vigencia', page)
        self.assertIn('<strong>1</strong><span>Alertas altas</span>', page)

    def test_unknown_rules_remain_visible_without_invented_explanation(self):
        self.data['alerts'].append(dict(self.alerts[0], pluginId='future-9000', alert='Future Detector'))
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertIn('Future Detector', page)
        self.assertIn('No hay una explicación editorial específica', page)
        self.assertIn('La interpretación requiere revisión', page)

    def test_known_rules_have_readable_spanish_explanations(self):
        page = self.render()['zap-resumen-ejecutivo.html']
        for phrase in ('Biblioteca JavaScript con posibles vulnerabilidades',
                       'Falta una política de seguridad de contenido', 'clics engañosos',
                       'comprobación explícita de integridad', 'El servidor revela información',
                       'respetar el tipo de contenido', 'Posible fecha interna', 'comportamiento dinámico',
                       '¿Qué significa?', '¿Por qué podría importar?', 'Estado de revisión'):
            self.assertIn(phrase, page)

    def test_untrusted_text_is_escaped_and_never_becomes_markup_or_remote_resource(self):
        injection = '<img src="https://third.test/" onerror="alert(1)"><script>alert(1)</script>'
        self.data['alerts'] = [dict(self.alerts[0], pluginId='unknown', alert=injection,
                                   evidence=injection, other='</pre>' + injection,
                                   review_status=injection)]
        page = self.render(metadata={'revision': injection})['zap-resumen-ejecutivo.html']
        self.assertNotIn('<img', page)
        self.assertNotIn('<script', page)
        self.assertIn('&lt;script&gt;', page)
        self.assertNotIn('href="https://third.test/', page)

    def test_sanitization_and_metadata_allowlist_do_not_publish_secrets(self):
        raw = {'pluginId': 'future', 'alert': 'Future rule', 'risk': 'High', 'confidence': 'Medium',
               'url': 'http://frontend/', 'evidence': 'Cookie: SESSION=reserved\ncsrf-secret',
               'other': 'Authorization: Bearer hidden-authorization'}
        self.data['alerts'] = sanitize_alerts([raw], {'http://frontend/'}, ['reserved', 'csrf-secret'])
        page = self.render(metadata={'revision': 'token=private-token',
                                     'password': 'private-password', 'cookie': 'private-cookie',
                                     'response_body': 'private-body'})['zap-resumen-ejecutivo.html']
        for secret in ('reserved', 'csrf-secret', 'hidden-authorization', 'private-token',
                       'private-password', 'private-cookie', 'private-body'):
            self.assertNotIn(secret, page)

    def test_traceability_uses_final_metadata_without_changing_technical_files(self):
        with tempfile.TemporaryDirectory() as directory:
            write_reports(directory, self.data)
            paths = [Path(directory) / name for name in ('zap-report.html', 'zap-report.json')]
            originals = [p.read_bytes() for p in paths]
            metadata = {'started_utc': '2026-11-14T01:15:12+00:00', 'revision': 'a' * 40,
                        'github_run_id': '123456', 'duration_seconds': 91.939, 'exit_code': 1}
            finalize_executive_report(directory, metadata)
            self.assertEqual(originals, [p.read_bytes() for p in paths])
            self.assertEqual(json.loads(paths[1].read_text()), self.data)
            page = (Path(directory) / 'zap-resumen-ejecutivo.html').read_text()
            for text in ('13/11/2026 · 20:15:12', 'a' * 40, '123456', '2.17.0',
                         '30,0 segundos', '91,9 segundos', self.data['result']['policy']):
                self.assertIn(text, page)

    def test_technical_failure_without_results_never_claims_success_or_zero_alerts(self):
        with tempfile.TemporaryDirectory() as directory:
            finalize_executive_report(directory, {'technical_error': 'scanner unavailable', 'exit_code': 3})
            page = (Path(directory) / 'zap-resumen-ejecutivo.html').read_text()
            self.assertIn('La ejecución presentó un error técnico', page)
            self.assertIn('No verificable', page)
            self.assertNotIn('Análisis completado', page)
            self.assertNotIn('No se detectaron alertas', page)
            self.assertNotIn('El inicio de sesión se validó por separado', page)

    def test_partial_results_do_not_override_technical_failure(self):
        page = self.render(metadata={'exit_code': 3, 'technical_error': 'coverage'})['zap-resumen-ejecutivo.html']
        self.assertIn('La ejecución presentó un error técnico', page)
        self.assertIn('datos disponibles son parciales', page)
        self.assertNotIn('Análisis completado', page)

    def test_mixed_confidence_and_review_states_are_not_collapsed(self):
        self.alerts[0]['confidencecode'] = 1
        self.alerts[0]['review_status'] = 'Confirmada'
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertIn('Alta: 23; Baja: 1', page)
        self.assertIn('Confirmada: 1', page)
        self.assertIn('Corregir la vulnerabilidad confirmada', page)

    def test_self_contained_printable_document_links_to_technical_reports(self):
        page = self.render()['zap-resumen-ejecutivo.html']
        self.assertIn('<html lang="es">', page)
        self.assertIn('@media print', page)
        self.assertIn('href="zap-report.html"', page)
        self.assertIn('href="zap-report.json"', page)
        self.assertNotIn('<script', page)
        self.assertNotIn('<link', page)
        self.assertNotIn('@import', page)


if __name__ == '__main__':
    unittest.main()
