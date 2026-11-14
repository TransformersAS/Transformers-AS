"""Reportes ZAP sin mensajes HTTP ni credenciales; política explícita y comprobable."""
import datetime as dt
import html
import json
import re
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


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
    write_executive_report(directory, data)


RISK_LABELS = {3: 'Alta', 2: 'Media', 1: 'Baja', 0: 'Informativa'}
CONFIDENCE_LABELS = {0: 'Falso positivo según ZAP', 1: 'Baja', 2: 'Media', 3: 'Alta',
                     4: 'Confirmada según ZAP; requiere revisión del equipo'}
# Explicaciones editoriales de reglas, no diagnósticos individuales ni confirmaciones.
EXPLANATIONS = {
    '10003': ('Biblioteca JavaScript con posibles vulnerabilidades',
              'ZAP reconoció una biblioteca y una versión que su base de detección relaciona con avisos de seguridad.',
              'La versión detectada requiere revisión. Esta coincidencia no demuestra que un ataque funcione en esta aplicación.',
              'Comprobar la versión realmente utilizada, leer los avisos citados y confirmar si aplican. Si se confirma el problema, planificar la actualización compatible y repetir las pruebas.'),
    '10038': ('Falta una política de seguridad de contenido (CSP)',
              'La respuesta inspeccionada no declaró una política que limite desde dónde puede cargar contenido el navegador.',
              'Es una medida de protección adicional. Su ausencia no demuestra por sí sola una inyección de código.',
              'Revisar los orígenes que necesita la aplicación y evaluar una política CSP compatible. Probar la navegación antes de aplicarla.'),
    '10020': ('Falta protección explícita contra clics engañosos',
              'ZAP no encontró en la respuesta inspeccionada la protección de cabeceras que espera frente a la inclusión en marcos de otra página.',
              'Si una pantalla permite ser incrustada por terceros, podría facilitar interacciones engañosas. Debe verificarse en el navegador.',
              'Comprobar si se permite incrustar la pantalla y revisar las cabeceras de protección, incluida la política de marcos de CSP.'),
    '90003': ('Recursos sin comprobación explícita de integridad',
              'ZAP encontró referencias a recursos sin el atributo de integridad que comprueba esta regla.',
              'La importancia depende del origen y del modo de distribución del recurso; una referencia propia no implica automáticamente un recurso comprometido.',
              'Identificar cada recurso y su origen. Evaluar si corresponde comprobar su integridad y cómo mantener esa comprobación con las nuevas compilaciones.'),
    '10036': ('El servidor revela información de su versión',
              'La cabecera de respuesta del servidor contiene información que permite identificar su software o versión.',
              'Puede dar información útil a quien investiga el servidor. No demuestra que esa versión sea vulnerable.',
              'Revisar la evidencia de la cabecera y evaluar reducir la información publicada. Mantener el servidor actualizado sigue siendo necesario.'),
    '10021': ('Falta una cabecera para respetar el tipo de contenido',
              'ZAP no encontró X-Content-Type-Options: nosniff en las respuestas indicadas.',
              'Esa cabecera ayuda a que el navegador respete el tipo de contenido declarado. Su ausencia no prueba una explotación.',
              'Verificar los tipos de contenido y evaluar la cabecera nosniff en las respuestas afectadas, probando que los recursos sigan cargando.'),
    '10096': ('Posible fecha interna expuesta en una respuesta',
              'ZAP interpretó un número de la respuesta como una fecha en formato Unix.',
              'Puede ser un dato normal o una coincidencia en un archivo JavaScript. Primero hay que comprobar si representa información sensible.',
              'Localizar el valor, interpretar su contexto y decidir si debe publicarse. Documentar un falso positivo únicamente con evidencia que lo justifique.'),
    '10109': ('Aplicación web con comportamiento dinámico',
              'ZAP identificó características de una aplicación web moderna que utiliza JavaScript.',
              'Es un aviso informativo sobre cómo explorar la aplicación; no es por sí mismo una vulnerabilidad.',
              'Revisar si el recorrido cubrió las pantallas necesarias. Registrar las rutas dinámicas que no se inspeccionaron.'),
}


def _esc(value):
    return html.escape(redact(value), quote=True)


def _display_url(value):
    """Mostrar ubicación sin credenciales de URL, parámetros ni fragmentos."""
    parsed = urlsplit(str(value))
    if parsed.scheme and parsed.hostname:
        host = parsed.hostname
        if ':' in host:
            host = '[' + host + ']'
        if parsed.port:
            host += ':' + str(parsed.port)
        return urlunsplit((parsed.scheme, host, parsed.path or '/', '', ''))
    return parsed.path


def group_alerts(alerts):
    groups = {}
    for alert in alerts:
        key = (str(alert['pluginId']), str(alert['alert']), int(alert['riskcode']))
        groups.setdefault(key, []).append(alert)
    return sorted(groups.items(), key=lambda entry: (-entry[0][2], entry[0][0], entry[0][1]))


def _execution_state(data, metadata, scanner_status):
    technical = scanner_status.get('technical_status', data.get('technical_status'))
    if (metadata.get('technical_error') or metadata.get('exit_code') == 3
            or technical in ('technical_error', 'coverage_failure')):
        return 'error'
    result = data.get('result', {})
    coverage = data.get('coverage', [])
    checked = bool(coverage) and all(r.get('status') == 200 and r.get('contract_valid') for r in coverage)
    if (technical == 'ok' or checked) and result.get('exit_code') in (0, 1):
        return 'complete'
    return 'unknown'


def _date_label(value):
    if not value:
        return 'No disponible'
    try:
        stamp = dt.datetime.fromisoformat(str(value).replace('Z', '+00:00'))
        if stamp.tzinfo is None:
            return 'Fecha sin zona horaria verificada'
        local = stamp.astimezone(dt.timezone(dt.timedelta(hours=-5)))
        return local.strftime('%d/%m/%Y · %H:%M:%S') + ' (Bogotá, UTC−5)'
    except ValueError:
        return 'Fecha no verificable'


def _duration_label(value):
    if isinstance(value, (int, float)) and value >= 0:
        return f'{value:,.1f}'.replace(',', '_').replace('.', ',').replace('_', '.') + ' segundos'
    return 'No disponible'


def _amount(number, singular, plural):
    return f'{number} {singular if number == 1 else plural}'


def _priority_evidence(key, instances):
    """Reconocer únicamente una firma explícita del detector, sin confirmar su aplicabilidad."""
    if key[0] == '10003':
        for alert in instances:
            try:
                signature = json.loads(alert.get('evidence', ''))
            except (ValueError, TypeError):
                continue
            if (isinstance(signature, list) and len(signature) == 2 and signature[0] == 'ng-version'
                    and isinstance(signature[1], str) and re.fullmatch(r'[0-9][A-Za-z0-9.+_-]{0,49}', signature[1])):
                return (' ZAP detectó la cadena de versión Angular ' + signature[1] + ' en '
                        + _display_url(alert.get('url', '')) + '; su aplicabilidad está pendiente de revisión.')
    return ''


def write_executive_report(directory, data, metadata=None, scanner_status=None):
    """Resumen autocontenido a partir de datos sanitizados; no escanea ni reevalúa política."""
    metadata, scanner_status = metadata or {}, scanner_status or {}
    alerts = data.get('alerts', [])
    groups = group_alerts(alerts)
    counts = Counter(int(a['riskcode']) for a in alerts)
    result = data.get('result', {})
    state = _execution_state(data, metadata, scanner_status)
    complete = state == 'complete'
    blocked = complete and result.get('exit_code') == 1
    if state == 'error':
        headline = 'La ejecución presentó un error técnico'
        summary = ('No se puede presentar esta ejecución como completada correctamente. '
                   'La evaluación de seguridad no es verificable; revise el diagnóstico técnico antes de repetir el análisis.')
        scan_label, policy_label = 'Con error técnico', 'No verificable'
    elif not complete:
        headline = 'Falta evidencia para verificar el resultado'
        summary = ('La información disponible no permite confirmar que el análisis y su cobertura terminaron correctamente. '
                   'Este documento no acredita una evaluación aprobada.')
        scan_label, policy_label = 'Sin confirmar', 'No verificable'
    elif blocked:
        headline = 'Análisis completado · evaluación no aprobada'
        summary = ('El análisis de seguridad se completó correctamente. La política no aprobó la ejecución '
                   'porque existen alertas bloqueantes que requieren revisión. No son vulnerabilidades confirmadas automáticamente.')
        scan_label, policy_label = 'Completado correctamente', 'No aprobó'
    else:
        headline = 'Análisis completado · política automática aprobada'
        summary = ('El análisis terminó correctamente y no activó bloqueos de la política. '
                   + ('Las alertas encontradas siguen pendientes de revisión.' if alerts else
                      'No se detectaron alertas en el tráfico inspeccionado. Este resultado no demuestra ausencia de vulnerabilidades.'))
        scan_label, policy_label = 'Completado correctamente', 'Aprobó la política automática'

    priority = next((g for g in groups if g[0][2] == 3), groups[0] if groups else None)
    if complete and priority:
        key, instances = priority
        title = EXPLANATIONS.get(key[0], (key[1],))[0]
        attention = (f'Prioridad de revisión: {title}. '
                     + _amount(len(instances), 'alerta', 'alertas')
                     + f' de severidad {RISK_LABELS[key[2]].lower()}.' + _priority_evidence(key, instances))
    elif complete:
        attention = 'Siguiente paso: conservar la evidencia y revisar los límites de cobertura.'
    else:
        attention = 'Siguiente paso: resolver el error o completar la evidencia y repetir la ejecución.'

    cards = []
    for risk, title in ((3, 'Alertas altas'), (2, 'Alertas medias'), (1, 'Alertas bajas'), (0, 'Avisos informativos')):
        cards.append(f'<div class="metric risk-{risk}"><strong>{counts[risk] if complete or alerts else "—"}</strong><span>{title}</span></div>')
    for title, value in (('Total de alertas', len(alerts)), ('Tipos de hallazgos', len(groups)),
                         ('Solicitudes inspeccionadas', data.get('messages_count'))):
        display = value if value is not None and (complete or alerts) else '—'
        cards.append(f'<div class="metric"><strong>{_esc(display)}</strong><span>{title}</span></div>')
    bars = []
    peak = max(counts.values(), default=1)
    for risk in (3, 2, 1, 0):
        width = counts[risk] / max(peak, 1) * 100
        bars.append(f'<div class="bar-row"><span>{RISK_LABELS[risk]}</span><div class="track"><div class="bar risk-{risk}" style="width:{width:.2f}%"></div></div><b>{counts[risk]}</b></div>')

    findings, actions = [], []
    for index, (key, instances) in enumerate(groups, 1):
        plugin, original_name, risk = key
        explanation = EXPLANATIONS.get(plugin, (
            'Hallazgo que requiere interpretación: ' + original_name,
            'ZAP registró una alerta para esta regla. No hay una explicación editorial específica para ella en este informe.',
            'Su efecto no se puede determinar solo a partir del nombre o del nivel de riesgo. La interpretación requiere revisión.',
            'Consultar la evidencia y la documentación de la regla en el informe técnico. Confirmar la aplicabilidad antes de decidir una corrección.'))
        title, meaning, importance, recommendation = explanation
        urls = sorted({_display_url(a.get('url', '')) for a in instances})
        confidence = Counter(CONFIDENCE_LABELS.get(a.get('confidencecode'), 'No disponible') for a in instances)
        confidence_text = (next(iter(confidence)) if len(confidence) == 1 else
                           '; '.join(f'{name}: {count}' for name, count in sorted(confidence.items())))
        reviews = Counter(a.get('review_status', 'Pendiente de revisión') for a in instances)
        review_text = (next(iter(reviews)) if len(reviews) == 1 else
                       '; '.join(f'{status}: {count}' for status, count in sorted(reviews.items())))
        # Evita repetir la misma evidencia por cada respuesta equivalente.
        evidence = sorted({redact(a.get('evidence', '')) for a in instances if a.get('evidence')})
        additional = sorted({redact(a.get('other', '')) for a in instances if a.get('other')})
        details = ('<h4>Ubicaciones afectadas</h4><ul>' + ''.join('<li><code>' + _esc(u) + '</code></li>' for u in urls) + '</ul>')
        if evidence:
            details += '<h4>Evidencia técnica original</h4>' + ''.join('<pre>' + _esc(e) + '</pre>' for e in evidence)
        if additional:
            details += '<h4>Información adicional del escáner</h4>' + ''.join('<pre>' + _esc(a) + '</pre>' for a in additional)
        exceptions = [a['exception'] for a in instances if a.get('exception')]
        if exceptions:
            details += '<h4>Excepciones documentadas</h4><ul>' + ''.join(
                '<li>' + _esc(x.get('reason', '')) + ' · Revisor: ' + _esc(x.get('reviewer', ''))
                + ' · Vence: ' + _esc(x.get('expires', '')) + '</li>' for x in exceptions) + '</ul>'
        findings.append(f'<article class="finding risk-{risk}" id="hallazgo-{index}">'
                        f'<div class="finding-top"><span class="badge risk-{risk}">Severidad {RISK_LABELS[risk]}</span>'
                        f'<span>{_amount(len(instances), "alerta", "alertas")} · {_amount(len(urls), "ubicación distinta", "ubicaciones distintas")}</span></div>'
                        f'<h3>{_esc(title)}</h3><p class="subtle">Regla {_esc(plugin)} · Nombre original: {_esc(original_name)}</p>'
                        f'<p><b>Confianza de ZAP:</b> {_esc(confidence_text)}</p>'
                        f'<div class="explanation"><div><h4>¿Qué significa?</h4><p>{_esc(meaning)}</p></div>'
                        f'<div><h4>¿Por qué podría importar?</h4><p>{_esc(importance)}</p></div></div>'
                        f'<p><b>Qué hacer:</b> {_esc(recommendation)}</p>'
                        f'<p class="review"><b>Estado de revisión:</b> {_esc(review_text)}</p>'
                        f'<details><summary>Ver ubicaciones y evidencia · {_amount(len(urls), "ubicación", "ubicaciones")}</summary>{details}</details></article>')
        statuses = {str(a.get('review_status', '')).strip().casefold() for a in instances}
        if statuses and all(s.startswith('aceptada temporalmente') for s in statuses):
            action = 'Revisar la vigencia y justificación de las excepciones; conservar la evidencia y repetir el análisis al cambiar el contexto.'
        elif any(s in ('confirmada', 'confirmado', 'vulnerabilidad confirmada') for s in statuses):
            action = 'Corregir la vulnerabilidad confirmada en las instancias revisadas y repetir las pruebas para verificar la remediación.'
        elif statuses and all(s.startswith('falso positivo justificado') for s in statuses):
            action = 'Conservar la justificación del falso positivo y revalidarla si cambia la aplicación o el alcance.'
        else:
            action = 'Investigar la evidencia y confirmar su aplicabilidad. ' + recommendation
        actions.append(f'<li><a href="#hallazgo-{index}">{_esc(title)}</a>'
                       f'<span class="action-risk">{RISK_LABELS[risk]}</span><p>{_esc(action)}</p></li>')

    coverage = data.get('coverage', [])
    role_names = {'anonymous': 'Público, sin sesión', 'COMPRADOR': 'Comprador autenticado',
                  'VENDEDOR': 'Vendedor', 'ADMIN': 'Administrador', 'SOPORTE': 'Soporte'}
    role_counts = Counter(role_names.get(row.get('role'), 'Rol no identificado') for row in coverage
                          if row.get('status') == 200 and row.get('contract_valid'))
    role_summary = '; '.join(f'{name}: {count} recorridos comprobados' for name, count in sorted(role_counts.items())) or 'No hay recorridos comprobados disponibles.'
    paths = sorted({_display_url(row.get('path', '')) for row in coverage})
    scope_details = '<details><summary>Consultar rutas del recorrido documentado</summary><ul>' + ''.join(
        '<li><code>' + _esc(path) + '</code></li>' for path in paths) + '</ul></details>'
    exclusions = data.get('exclusions', [])
    excluded = '; '.join(str(x) for x in exclusions) or 'No se dispone de la lista específica; consultar el registro de alcance.'
    scan_scope = ('Fue un análisis pasivo: no hubo Full Scan ni pruebas activas.' if complete else
                  'El alcance previsto es pasivo, sin Full Scan ni pruebas activas; esta ejecución no acredita un recorrido completo.')
    login_scope = ('El inicio de sesión se validó por separado; no se escaneó como operación.'
                   if complete and data.get('login_scanned') is False and any(r.get('role') == 'COMPRADOR' for r in coverage) else
                   'Los datos disponibles no acreditan aquí la validación separada del inicio de sesión.')
    revision = metadata.get('revision', 'No disponible')
    if revision == 'local-unverified-no-git':
        revision = 'Ejecución local; commit no verificado'
    trace = [('Fecha de inicio', _date_label(metadata.get('started_utc'))),
             ('Versión de ZAP', data.get('version', 'No disponible')),
             ('Commit analizado', revision), ('ID de GitHub Actions', metadata.get('github_run_id') or 'No disponible'),
             ('Duración del escáner', _duration_label(data.get('duration_seconds'))),
             ('Duración total de ejecución', _duration_label(metadata.get('duration_seconds'))),
             ('Política aplicada', result.get('policy', 'No disponible'))]
    trace_html = ''.join('<div><dt>' + _esc(k) + '</dt><dd>' + _esc(v) + '</dd></div>' for k, v in trace)
    warning = (f'{len(alerts)} alertas no equivalen necesariamente a {len(alerts)} vulnerabilidades distintas. '
               f'Se agrupan aquí en {len(groups)} tipos por regla, nombre y severidad. '
               'Una misma respuesta puede generar varias alertas; varias respuestas pueden repetir el mismo hallazgo.')
    if not complete:
        warning = 'Los datos disponibles son parciales o no verificables; sus conteos no acreditan un análisis completo. ' + warning
    result_class = 'blocked' if blocked or state == 'error' else ('success' if complete else 'unknown')
    page = f'''<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Resumen ejecutivo de seguridad — Transformers-AS</title>
<style>
:root{{--ink:#15283f;--muted:#526379;--line:#dce3eb;--paper:#fff;--bg:#eef3f8;--high:#b42332;--medium:#946000;--low:#2463a5;--info:#627085}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif}}
main{{max-width:1180px;margin:auto;padding:32px 28px 60px}}header{{background:#12283e;color:white;padding:34px;border-radius:18px}}
.eyebrow{{text-transform:uppercase;letter-spacing:.13em;font-size:12px;font-weight:750;color:#95dcd5}}h1{{font-size:clamp(28px,4vw,42px);line-height:1.18;margin:14px 0}}
.intro{{max-width:850px;color:#d6e3ef}}.status-row{{display:flex;gap:12px;flex-wrap:wrap;margin-top:24px}}.status{{background:#ffffff10;padding:12px 18px;border:1px solid #ffffff30;border-radius:10px;flex:1}}
.status span{{display:block;color:#c4d4e4;font-size:13px}}.status b{{font-size:17px}}.attention{{background:#f4f8fc;border-left:5px solid var(--low);padding:18px 24px;border-radius:8px;margin:20px 0}}
.attention.blocked{{border-color:var(--high);background:#fff1f0}}.attention.success{{border-color:#197165}}.attention.unknown{{border-color:var(--medium)}}
h2{{font-size:24px;margin:0 0 12px}}h3{{font-size:21px;line-height:1.35;margin:14px 0 8px}}h4{{font-size:15px;margin:0 0 6px}}p{{margin:8px 0 14px}}
section{{margin-top:32px}}.metrics{{display:grid;grid-template-columns:repeat(7,1fr);gap:10px;margin-top:20px}}.metric{{background:var(--paper);border:1px solid var(--line);border-top:4px solid #334c67;padding:18px 12px;border-radius:10px}}
.metric strong{{display:block;font-size:32px;line-height:1.15}}.metric span{{font-size:12px;color:var(--muted);display:block;margin-top:10px;line-height:1.4}}
.risk-3{{--risk:var(--high)}}.risk-2{{--risk:var(--medium)}}.risk-1{{--risk:var(--low)}}.risk-0{{--risk:var(--info)}}.metric[class*="risk-"]{{border-top-color:var(--risk)}}
.overview{{display:grid;grid-template-columns:1fr 1fr;gap:24px;background:white;border:1px solid var(--line);padding:24px;border-radius:12px;margin-top:16px}}
.bar-row{{display:grid;grid-template-columns:90px 1fr 28px;align-items:center;gap:12px;margin:12px 0;font-size:14px}}.track{{height:13px;border-radius:4px;background:#edf1f5;overflow:hidden}}.bar{{height:100%;background:var(--risk)}}
.subtle{{color:var(--muted);font-size:13px}}.finding{{background:white;border:1px solid var(--line);border-left:5px solid var(--risk);border-radius:12px;padding:24px;margin:18px 0;scroll-margin-top:20px}}
.finding-top{{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:13px;flex-wrap:wrap}}.badge{{border:1px solid var(--risk);color:var(--risk);padding:3px 10px;border-radius:20px;font-weight:700}}
.explanation{{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin:20px 0 8px}}.review{{font-size:14px;background:#f4f6f9;padding:12px;border-radius:6px}}
details{{border-top:1px solid var(--line);padding-top:14px;margin-top:16px}}summary{{cursor:pointer;font-weight:650;color:#244d72}}details[open] summary{{margin-bottom:18px}}pre,code{{font:12px/1.65 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere}}
pre{{padding:14px;background:#f4f6f9;border-radius:6px}}.actions{{background:white;padding:24px 24px 24px 48px;border:1px solid var(--line);border-radius:12px}}.actions li{{padding:8px 0 14px}}a{{color:#215b87;text-underline-offset:3px}}.actions a{{font-weight:700}}.action-risk{{font-size:12px;margin-left:12px;color:var(--muted)}}
.scope{{background:white;padding:24px;border:1px solid var(--line);border-radius:12px}}dl.trace{{display:grid;grid-template-columns:1fr 1fr;gap:16px 28px;padding:24px;background:white;border:1px solid var(--line);border-radius:12px}}
dt{{font-size:12px;color:var(--muted);font-weight:650}}dd{{margin:3px 0 0;overflow-wrap:anywhere}}footer{{margin-top:32px;font-size:13px;color:var(--muted)}}.empty{{background:white;border:1px solid var(--line);padding:24px;border-radius:12px}}
@media(max-width:900px){{.metrics{{grid-template-columns:repeat(4,1fr)}}}}@media(max-width:600px){{main{{padding:16px 12px}}header{{padding:24px}}.metrics{{grid-template-columns:repeat(2,1fr)}}.overview,.explanation,dl.trace{{grid-template-columns:1fr}}.finding{{padding:18px}}}}
@media print{{@page{{margin:16mm}}body{{background:white;font-size:10pt}}main{{max-width:none;padding:0}}header{{border-radius:0;background:white;color:var(--ink);padding:0 0 18px;border-bottom:2px solid var(--ink)}}.eyebrow,.intro,.status span{{color:var(--muted)}}.status{{border:1px solid var(--line)}}h1{{font-size:25pt}}h2{{font-size:17pt}}h3{{font-size:14pt}}.metrics{{grid-template-columns:repeat(7,1fr)}}.metric{{padding:10px 6px}}.metric strong{{font-size:22pt}}.finding,.overview,.scope,.actions,dl.trace{{break-inside:avoid}}details:not([open])>:not(summary){{display:block!important}}summary{{list-style:none}}a{{color:inherit}}.bar{{print-color-adjust:exact;-webkit-print-color-adjust:exact}}}}
@media print{{details::details-content{{content-visibility:visible;block-size:auto}}}}
</style></head><body><main>
<header><div class="eyebrow">Transformers-AS · Marketplace Integral</div><h1>Resumen ejecutivo de seguridad</h1>
<p class="intro">Análisis pasivo de OWASP ZAP sobre recursos públicos y lecturas autenticadas. Informe para revisión y sustentación académica.</p>
<div class="status-row"><div class="status"><span>Funcionamiento del análisis</span><b>{_esc(scan_label)}</b></div>
<div class="status"><span>Evaluación de la política</span><b>{_esc(policy_label)}</b></div></div></header>
<div class="attention {result_class}"><h2>{_esc(headline)}</h2><p>{_esc(summary)}</p><b>{_esc(attention)}</b></div>
<section aria-labelledby="indicadores"><h2 id="indicadores">La ejecución en cifras</h2><div class="metrics">{''.join(cards)}</div>
<div class="overview"><div><h3>Alertas por severidad</h3>{''.join(bars) if complete or alerts else '<p>Sin resultados verificados.</p>'}</div>
<div><h3>Cómo leer estos números</h3><p>{_esc(warning)}</p><p class="subtle">La severidad indica prioridad; la confianza expresa la certeza del detector. Ninguna sustituye la revisión humana.</p></div></div></section>
<section><h2>Hallazgos agrupados</h2>{''.join(findings) or ('<p class="empty">No se detectaron alertas en el alcance inspeccionado. Esto no demuestra ausencia de vulnerabilidades.</p>' if complete else '<p class="empty">No hay resultados verificables para describir hallazgos.</p>')}</section>
<section><h2>Qué hacer a continuación</h2>{'<ol class="actions">' + ''.join(actions) + '</ol>' if actions else '<p class="empty">' + ('Conservar los informes y revisar si el alcance es suficiente para la entrega.' if complete else 'Revisar el diagnóstico técnico, resolver la causa y repetir el análisis.') + '</p>'}
<p>No aceptar ni eliminar alertas para conseguir una ejecución verde. Corregir las vulnerabilidades confirmadas, repetir el análisis y documentar falsos positivos solo con justificación.</p></section>
<section><h2>Alcance y límites</h2><div class="scope"><p><b>Recorridos comprobados:</b> {_esc(role_summary)}</p>{scope_details}
<p><b>Fuera del alcance:</b> {_esc(excluded)}</p><p>{_esc(scan_scope)} {_esc(login_scope)}</p>
<p>ZAP no sustituye el análisis de dependencias (SCA) ni las pruebas funcionales de autorización. No demuestra ausencia de vulnerabilidades ni cubre automáticamente todo el sistema final.</p></div></section>
<section><h2>Trazabilidad de la evidencia</h2><dl class="trace">{trace_html}</dl><p><a href="zap-report.html">Abrir informe técnico HTML</a> · <a href="zap-report.json">Consultar datos técnicos JSON</a> · <a href="metadata.json">Consultar metadatos de ejecución</a></p></section>
<footer>Documento autocontenido y preparado para impresión. Generado a partir de datos sanitizados del escáner; no contiene mensajes HTTP ni cuerpos privados. Las explicaciones de reglas orientan la revisión y no certifican vulnerabilidades.</footer>
</main></body></html>'''
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'zap-resumen-ejecutivo.html').write_text(page, encoding='utf-8')
