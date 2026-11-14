"""Análisis pasivo controlado. Se ejecuta SOLO dentro de compose.security.yaml.

No spider, navegador, activeScan ni seguimiento de enlaces externos. El login
directo solo prepara la sesión; ZAP recibe exclusivamente GET de la allowlist.
"""
import http.client
from http.cookies import SimpleCookie
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.parse
import urllib.request

from report import CoverageError, evaluate, redact, sanitize_alerts, validate_coverage, write_reports

OUT = Path('/tmp/security-output')
TARGET = 'http://frontend'
PASSWORD = 'Cu08RealTestPassword!'  # fixture pública, no credencial personal
EMAIL = 'zap-buyer@example.test'
EXPECTED = {'/': 'anonymous', '/api/products': 'anonymous', '/api/products/1': 'anonymous',
            '/api/reviews/products/1': 'anonymous', '/api/reviews/products/1/summary': 'anonymous',
            '/api/auth/me': 'COMPRADOR', '/api/cart': 'COMPRADOR', '/api/favorites': 'COMPRADOR',
            '/api/addresses': 'COMPRADOR', '/api/account/profile': 'COMPRADOR',
            '/api/notifications': 'COMPRADOR'}
EXCLUSIONS = ['otros dominios', 'pagos y webhooks', 'cancelaciones/reembolsos', 'administración',
              'importaciones', 'registro CU-12', 'cambios de cuenta/contraseña/correo',
              'proveedores, IA, SMTP y push', 'escrituras de negocio', 'roles vendedor/soporte/admin']


def canonical_url(value):
    """ZAP puede serializar el puerto HTTP implícito; nunca aceptar otro origen."""
    parsed = urllib.parse.urlsplit(value if value.startswith(('http://', 'https://')) else TARGET + value)
    if (parsed.scheme != 'http' or parsed.hostname != 'frontend' or parsed.port not in (None, 80)
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise CoverageError('Origen o parámetros fuera del alcance')
    return TARGET + (parsed.path or '/')


def summarize_messages(raw_messages):
    messages, structural = [], []
    if len(raw_messages) >= 1000:
        raise CoverageError('Se alcanzó el límite de mensajes; no se acepta evidencia truncada')
    for raw in raw_messages:
        first = raw['requestHeader'].splitlines()[0].split()
        url = canonical_url(first[1])
        # SiteMap.createReference clona solicitudes para nodos padres.
        kind = int(raw['type'])
        if kind == 0:  # HistoryReference.TYPE_TEMPORARY: no fue enviado.
            structural.append(url)
        elif kind == 1:  # TYPE_PROXIED; no spider/active scan/user-generated.
            messages.append({'url': url, 'method': first[0], 'status': int(raw['responseHeader'].split()[1])})
        else:
            raise CoverageError('Tipo de historial no autorizado: ' + str(kind))
    return messages, structural


class Assets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'script':
            value = attrs.get('src', '')
        elif tag == 'link' and attrs.get('rel') in ('stylesheet', 'icon', 'modulepreload'):
            value = attrs.get('href', '')
        else:
            return
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
            return
        path = '/' + parsed.path.lstrip('/')
        # Solo archivos estáticos del build, nunca rutas de API ni navegación.
        if path.endswith(('.js', '.css', '.png', '.ico')) and '..' not in path and not path.startswith('/api/'):
            self.paths.add(path)


def main():
    OUT.mkdir(exist_ok=True)
    started = time.monotonic()
    logs, observed, cookies, sensitive = [], [], {}, [PASSWORD, EMAIL, '3000000000']
    api_key = secrets.token_hex(24)
    sensitive.append(api_key)
    # El log interno puede contener información reservada: no se copia ni publica.
    private_log = open('/tmp/zap-private.log', 'w')
    process = subprocess.Popen(['zap.sh', '-daemon', '-host', '127.0.0.1', '-port', '8090',
                                '-config', 'api.key=' + api_key,
                                '-config', 'autoupdate.checkOnStart=false',
                                '-config', 'autoupdate.downloadNewRelease=false',
                                '-config', 'autoupdate.downloadNewAddons=false'],
                               stdout=private_log, stderr=subprocess.STDOUT)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(component, kind, name, **params):
        params['apikey'] = api_key
        url = 'http://127.0.0.1:8090/JSON/' + component + '/' + kind + '/' + name + '/?'
        with opener.open(url + urllib.parse.urlencode(params), timeout=20) as response:
            result = json.load(response)
        if isinstance(result, dict) and 'code' in result and 'message' in result:
            raise RuntimeError('Error de API ZAP: ' + str(result['code']))
        return result

    def event(message):
        logs.append(message)
        print(message, flush=True)

    def request(path, role='anonymous', proxy=True, method='GET', body=None, headers=None):
        if proxy and (method != 'GET' or path not in allowed):
            raise CoverageError('Solicitud fuera de allowlist')
        if not proxy and path not in ('/api/auth/csrf', '/api/auth/login', '/api/auth/me'):
            raise CoverageError('Preparación fuera del contrato de autenticación')
        headers = dict(headers or {})
        if role != 'anonymous' and cookies:
            headers['Cookie'] = '; '.join(k + '=' + v for k, v in cookies.items())
        connection = http.client.HTTPConnection('127.0.0.1' if proxy else 'frontend', 8090 if proxy else 80, timeout=30)
        try:
            connection.request(method, TARGET + path if proxy else path, body, headers)
            response = connection.getresponse()
            payload = response.read()
            if not proxy:
                for key, value in response.getheaders():
                    if key.lower() == 'set-cookie':
                        parsed = SimpleCookie(value)
                        for name, morsel in parsed.items():
                            cookies[name] = morsel.value
                            sensitive.append(morsel.value)
            # No se siguen redirects: un 3xx no cuenta como cobertura válida.
            return response.status, payload, dict(response.getheaders())
        finally:
            connection.close()

    def visit(path, role, predicate):
        status, body, headers = request(path, role)
        valid = status == 200 and predicate(body, headers)
        observed.append({'path': path, 'role': role, 'status': status, 'contract_valid': bool(valid)})
        if not valid:
            raise CoverageError('Contrato inválido: ' + path + ' status=' + str(status))
        event('GET ' + path + ' 200 contrato válido (' + role + ')')
        return body

    allowed = set(EXPECTED)
    try:
        deadline = time.monotonic() + 150
        while True:
            try:
                version = api('core', 'view', 'version')['version']
                break
            except (OSError, ValueError):
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('ZAP no inició dentro del límite')
                time.sleep(2)
        api('core', 'action', 'setMode', mode='safe')
        if api('core', 'view', 'mode')['mode'] != 'safe':
            raise RuntimeError('No se comprobó modo safe')
        event('ZAP ' + version + '; modo safe; sin spider ni active scan')
        # Consulta efectiva del ejecutable fijado; ayuda pública, sin ejecución de carga.
        help_result = subprocess.run(['zap-baseline.py', '-h'], capture_output=True, text=True, timeout=30)
        if '-J' not in help_result.stdout + help_result.stderr:
            raise RuntimeError('No se pudo consultar la ayuda de Baseline')
        (OUT / 'baseline-help.txt').write_text(help_result.stdout + help_result.stderr)
        page = visit('/', 'anonymous', lambda b, h: b'<app-root' in b and 'text/html' in h.get('Content-Type', ''))
        parser = Assets()
        parser.feed(page.decode())
        if not any(path.endswith('.js') for path in parser.paths):
            raise CoverageError('No se encontraron recursos JS propios')
        allowed.update(parser.paths)
        for path in sorted(parser.paths):
            visit(path, 'anonymous', lambda b, h: len(b) > 0 and 'text/html' not in h.get('Content-Type', ''))
        js = lambda b: json.loads(b)
        visit('/api/products', 'anonymous', lambda b, h: isinstance(js(b), list) and any(p.get('id') == 1 for p in js(b)))
        visit('/api/products/1', 'anonymous', lambda b, h: js(b).get('id') == 1 and bool(js(b).get('variants')))
        visit('/api/reviews/products/1', 'anonymous', lambda b, h: isinstance(js(b), list))
        visit('/api/reviews/products/1/summary', 'anonymous', lambda b, h: js(b).get('productId') == 1)
        # Controles negativos: no se presentan GET públicos como autenticados.
        if request('/api/auth/me', proxy=False)[0] != 401:
            raise CoverageError('La prueba sin sesión no recibió 401')
        status, payload, _ = request('/api/auth/csrf', proxy=False)
        csrf = js(payload)
        if status != 200 or not csrf.get('token') or not csrf.get('headerName'):
            raise CoverageError('CSRF no disponible')
        sensitive.append(csrf['token'])
        status, _, _ = request('/api/auth/login', role='COMPRADOR', proxy=False, method='POST',
                               body=urllib.parse.urlencode({'email': EMAIL, 'password': PASSWORD}),
                               headers={csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
        if status != 204:
            raise CoverageError('Login no devolvió 204: ' + str(status))
        status, payload, _ = request('/api/auth/me', role='COMPRADOR', proxy=False)
        if status != 200 or js(payload).get('activeRole') != 'COMPRADOR' or js(payload).get('email') != EMAIL:
            raise CoverageError('Sesión comprador no comprobada')
        event('Preparación directa: sin sesión 401; CSRF válido; login 204; /me COMPRADOR 200')
        visit('/api/auth/me', 'COMPRADOR', lambda b, h: js(b).get('activeRole') == 'COMPRADOR' and js(b).get('email') == EMAIL)
        visit('/api/cart', 'COMPRADOR', lambda b, h: isinstance(js(b).get('items'), list))
        visit('/api/favorites', 'COMPRADOR', lambda b, h: isinstance(js(b), list))
        visit('/api/addresses', 'COMPRADOR', lambda b, h: isinstance(js(b), list) and len(js(b)) == 1)
        visit('/api/account/profile', 'COMPRADOR', lambda b, h: js(b).get('email') == EMAIL)
        visit('/api/notifications', 'COMPRADOR', lambda b, h: isinstance(js(b).get('items'), list))
        # Esperar vaciado de la cola; no se exportan mensajes ni cabeceras.
        deadline = time.monotonic() + 180
        stable = 0
        while stable < 3:
            remaining = int(api('pscan', 'view', 'recordsToScan')['recordsToScan'])
            stable = stable + 1 if remaining == 0 else 0
            if time.monotonic() >= deadline:
                raise RuntimeError('Timeout de análisis pasivo')
            time.sleep(2)
        urls = [canonical_url(u) for u in api('core', 'view', 'urls')['urls']]
        messages, structural_nodes = summarize_messages(api('core', 'view', 'messages', start=0, count=1000)['messages'])
        if any(m['method'] != 'GET' or m['url'] not in {TARGET + p for p in allowed} for m in messages):
            unexpected = [m['method'] + ' ' + m['url'] for m in messages
                          if m['method'] != 'GET' or m['url'] not in {TARGET + p for p in allowed}]
            raise CoverageError('ZAP registró tráfico fuera de allowlist: ' + ', '.join(unexpected))
        validate_coverage(EXPECTED, observed, urls, messages)
        # También exigir recursos estáticos, no únicamente APIs.
        validate_coverage({p: 'anonymous' for p in parser.paths}, observed, urls, messages)
        raw_alerts = api('alert', 'view', 'alerts', start=0, count=1000)['alerts']
        if len(raw_alerts) >= 1000:
            raise CoverageError('Se alcanzó el límite de alertas; no se acepta reporte truncado')
        for alert in raw_alerts:
            alert['url'] = canonical_url(alert['url'])
        alerts = sanitize_alerts(raw_alerts,
                                 {TARGET + p for p in allowed}, sensitive)
        policy = json.loads(Path('/security/policy.json').read_text())
        result = evaluate(alerts, policy)
        data = {'scanner': 'OWASP ZAP', 'version': version, 'mode': 'safe / passive-only controlled traffic',
                'packaged_baseline_executed': False, 'login_scanned': False,
                'scope': sorted(allowed), 'exclusions': EXCLUSIONS, 'roles': ['anonymous', 'COMPRADOR'],
                'coverage': observed, 'zap_urls': sorted({m['url'] for m in messages}),
                'structural_nodes_not_counted_as_coverage': sorted(set(structural_nodes)),
                'messages_count': len(messages),
                'duration_seconds': round(time.monotonic() - started, 3),
                'result': result, 'alerts': alerts}
        write_reports(OUT, data)
        (OUT / 'scanner-status.json').write_text(json.dumps({'technical_status': 'ok', 'coverage': 'passed',
                                                           'zap_process_exit_code': None, 'evaluation': result}, indent=2))
        event('Cobertura comprobada; alertas=' + str(len(alerts)) + '; estado=' + result['status'])
        return result['exit_code']
    except Exception as error:
        category = 'coverage_failure' if isinstance(error, CoverageError) else 'technical_error'
        # No imprimir errores arbitrarios: pueden incluir headers o cuerpos reservados.
        event(category + ': ' + type(error).__name__ + ': ' + redact(str(error), sensitive))
        (OUT / 'scanner-status.json').write_text(json.dumps({'technical_status': category, 'coverage': 'not_passed',
                                                           'error_type': type(error).__name__, 'exit_code': 3}))
        return 3
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            code = process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            code = process.wait(timeout=10)
        status_file = OUT / 'scanner-status.json'
        if status_file.exists():
            status = json.loads(status_file.read_text())
            status['zap_process_exit_code'] = code
            status_file.write_text(json.dumps(status, indent=2))
        private_log.close()
        (OUT / 'execution.log').write_text('\n'.join(logs) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
