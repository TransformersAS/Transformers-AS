"""Reportes ZAP sin mensajes HTTP ni credenciales; política explícita y comprobable."""
import datetime as dt
import html
import json
import re
from collections import Counter
from pathlib import Path


class CoverageError(RuntimeError):
    pass


def redact(value, secrets=()):
    text = str(value)
    for secret in sorted(set(secrets), key=len, reverse=True):
        if secret:
            text = text.replace(secret, '[REDACTED]')
    text = re.sub(r'(?i)(?:set-cookie|cookie|authorization|x-csrf-token|x-xsrf-token)\s*:[^\r\n<]*',
                  '[HEADER REDACTED]', text)
    text = re.sub(r'(?i)(?:password|token|session|apikey)=([^\s&<]+)', '[VALUE REDACTED]', text)
    return text


def validate_coverage(expected, observed, zap_urls, messages):
    """Exige contratos comprobados y mensajes GET 200 que ZAP realmente recibió."""
    for path, role in expected.items():
        if not any(row['path'] == path and row['role'] == role and row['status'] == 200
                   and row['contract_valid'] for row in observed):
            raise CoverageError('Contrato o rol no comprobado: ' + path)
        url = 'http://frontend' + path
        if url not in zap_urls:
            raise CoverageError('URL ausente del árbol ZAP: ' + path)
        if not any(m['url'] == url and m['method'] == 'GET' and m['status'] == 200 for m in messages):
            raise CoverageError('Mensaje GET 200 ausente en ZAP: ' + path)


def sanitize_alerts(raw, allowed_urls, secrets):
    alerts = []
    for item in raw:
        url = str(item.get('url', ''))
        if url not in allowed_urls:
            raise CoverageError('Alerta fuera del alcance permitido')
        alert = {key: redact(item.get(key, ''), secrets) for key in (
            'pluginId', 'alert', 'risk', 'confidence', 'url', 'method', 'param',
            'evidence', 'description', 'other', 'solution', 'reference', 'cweid', 'wascid')}
        # La API de alertas usa los nombres canónicos ingleses; algunos exports incluyen códigos.
        risks = {'Informational': 0, 'Low': 1, 'Medium': 2, 'High': 3}
        confidences = {'False Positive': 0, 'Low': 1, 'Medium': 2, 'High': 3, 'Confirmed': 4}
        alert['riskcode'] = int(item['riskcode']) if 'riskcode' in item else risks[item['risk']]
        alert['confidencecode'] = int(item['confidencecode']) if 'confidencecode' in item else confidences[item['confidence']]
        if alert['riskcode'] not in range(4) or alert['confidencecode'] not in range(5):
            raise ValueError('Clasificación ZAP desconocida')
        alert['review_status'] = 'Pendiente de revisión; no confirmada'
        alerts.append(alert)
    return alerts


def evaluate(alerts, policy, today=None):
    today = today or dt.date.today()
    threshold = policy['block_risk_at_or_above']
    if threshold != 3:
        raise ValueError('P0 bloquea riesgo Alto; no cambiar sin revisar la política')
    exceptions = policy['exceptions']
    for exception in exceptions:
        if not all(exception.get(k) for k in ('pluginId', 'url', 'reason', 'reviewer', 'expires')):
            raise ValueError('Excepción incompleta')
        if dt.date.fromisoformat(exception['expires']) < today:
            raise ValueError('Excepción vencida')
    blocked, accepted = [], []
    for alert in alerts:
        match = next((x for x in exceptions if str(x['pluginId']) == alert['pluginId']
                      and x['url'] == alert['url']), None)
        if match:
            alert['review_status'] = 'Aceptada temporalmente'
            alert['exception'] = match
            accepted.append(alert)
        elif alert['riskcode'] >= threshold:
            blocked.append(alert)
    return {
        'status': 'security_findings' if blocked else ('warnings_for_review' if alerts else 'no_alerts_in_scope'),
        'alerts_by_risk': dict(Counter(str(a['riskcode']) for a in alerts)),
        'blocking_alerts': len(blocked), 'temporarily_accepted': len(accepted),
        'confirmed_vulnerabilities': 0,
        'exit_code': 1 if blocked else 0,
        'policy': 'Alto bloquea salvo excepción exacta vigente; Medio/Bajo/Informativo se conservan.',
    }


def write_reports(directory, data):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'zap-report.json').write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    esc = lambda x: html.escape(str(x), quote=True)
    sections = []
    for alert in data['alerts']:
        details = ''.join('<dt>' + esc(k) + '</dt><dd><pre>' + esc(v) + '</pre></dd>'
                          for k, v in alert.items())
        sections.append('<article><h2>' + esc(alert['alert']) + '</h2><dl>' + details + '</dl></article>')
    body = '\n'.join(sections) or '<p>Cero alertas en el tráfico inspeccionado. No certifica ausencia de vulnerabilidades.</p>'
    document = ('<!doctype html><html lang="es"><meta charset="utf-8"><title>OWASP ZAP — Marketplace</title>'
                '<style>body{max-width:1100px;margin:2rem auto;font:16px system-ui;padding:1rem}'
                'article{border-top:2px solid #777;margin-top:2rem}dt{font-weight:bold}'
                'pre{white-space:pre-wrap;overflow-wrap:anywhere}dd{margin-bottom:1rem}</style>'
                '<h1>OWASP ZAP — informe pasivo sanitizado</h1>'
                '<p>Fuente: alertas reales de la API ZAP. Sin mensajes HTTP, cookies ni tokens. '
                'No es Full Scan, SCA ni certificación de seguridad.</p><pre>'
                + esc(json.dumps({k: v for k, v in data.items() if k != 'alerts'}, indent=2, ensure_ascii=False))
                + '</pre>' + body + '</html>')
    (directory / 'zap-report.html').write_text(document)
