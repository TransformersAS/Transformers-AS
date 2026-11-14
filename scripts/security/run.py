#!/usr/bin/env python3
"""Construye, verifica, analiza y destruye SOLO un proyecto efímero nuevo. No usa Git."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'rnf-zap-' + uuid.uuid4().hex[:12]
OUT = ROOT / 'artifacts/security-zap' / PROJECT
ENV = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'DOCKER_CONFIG', 'TMPDIR')}
ENV['COMPOSE_DISABLE_ENV_FILE'] = '1'
COMPOSE = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(ROOT / 'compose.security.yaml'), '-p', PROJECT]


def invoke(args, *, input=None, check=True, timeout=120):
    result = subprocess.run(args, cwd=ROOT, env=ENV, input=input, text=True,
                            capture_output=True, timeout=timeout)
    if check and result.returncode:
        # No volcar logs de aplicación/configuración: podrían contener datos sensibles.
        raise RuntimeError('Comando fallido: ' + ' '.join(args[:4]) + '; exit=' + str(result.returncode))
    return result


def write(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def resources(kind):
    args = ['docker', kind, 'ls', '--filter', 'label=com.docker.compose.project=' + PROJECT, '-q']
    if kind == 'container':
        args.append('--all')
    return invoke(args).stdout.split()


def assert_config(config):
    if set(config['services']) != {'mysql', 'backend', 'frontend', 'zap'}:
        raise RuntimeError('Servicios inesperados')
    if not all(n.get('internal') is True for n in config['networks'].values()):
        raise RuntimeError('Las redes deben impedir egreso')
    if config.get('volumes'):
        raise RuntimeError('No se permiten volúmenes persistentes')
    if '/var/lib/mysql' not in config['services']['mysql'].get('tmpfs', []):
        raise RuntimeError('MySQL debe usar tmpfs')
    for name, service in config['services'].items():
        if service.get('ports') or service.get('privileged') or service.get('network_mode'):
            raise RuntimeError('Puertos/privilegios/red no permitidos')
        for volume in service.get('volumes', []):
            if name != 'zap' or volume.get('type') != 'bind' or not volume.get('read_only') or volume['target'] != '/security':
                raise RuntimeError('Montaje fuera de alcance')
    image = config['services']['zap']['image']
    if not re.fullmatch(r'ghcr.io/zaproxy/zaproxy@sha256:[a-f0-9]{64}', image):
        raise RuntimeError('ZAP debe estar fijado por digest')
    environment = config['services']['backend']['environment']
    required = {'MARKETPLACE_DEMO_DATA': 'false', 'PAYMENTS_MOCK_ENABLED': 'false',
                'PAYMENTS_RECOVERY_ENABLED': 'false', 'NOTIFICATIONS_QUEUE_ENABLED': 'false',
                'NOTIFICATIONS_PUSH_ENABLED': 'false', 'LOGISTICS_TRACKING_POLLING_ENABLED': 'false',
                'MERCADOPAGO_ACCESS_TOKEN': '', 'GEMINI_API_KEY': '', 'SPRING_MAIL_HOST': ''}
    if any(str(environment.get(k)) != v for k, v in required.items()):
        raise RuntimeError('Configuración externa no segura')
    return image


def verify_runtime():
    ids = resources('container')
    if len(ids) != 3:
        raise RuntimeError('No están disponibles los tres servicios')
    details = json.loads(invoke(['docker', 'inspect', *ids]).stdout)
    rows = []
    for item in details:
        if item['HostConfig'].get('PortBindings'):
            raise RuntimeError('Puerto publicado inesperado')
        if item['State'].get('Health', {}).get('Status') != 'healthy':
            raise RuntimeError('Servicio sin healthcheck aprobado')
        service = item['Config']['Labels']['com.docker.compose.service']
        if service == 'mysql' and '/var/lib/mysql' not in item['HostConfig'].get('Tmpfs', {}):
            raise RuntimeError('MySQL no está usando almacenamiento tmpfs')
        for net_id in (n['NetworkID'] for n in item['NetworkSettings']['Networks'].values()):
            network = json.loads(invoke(['docker', 'network', 'inspect', net_id]).stdout)[0]
            if not network['Internal'] or network['Labels'].get('com.docker.compose.project') != PROJECT:
                raise RuntimeError('Red ajena o con egreso')
        rows.append({'service': service,
                     'healthy': True, 'published_ports': [], 'networks_internal': True,
                     'image_id': item['Image']})
    write('isolation.json', {'project': PROJECT, 'services': rows, 'database_storage': 'tmpfs'})


def mysql(sql):
    return invoke(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE"'],
                  input=sql).stdout.strip()


def source_manifest():
    files = [ROOT / 'compose.security.yaml', ROOT / '.github/workflows/security-zap.yml']
    for root in ('backend/demo/src/main', 'backend/demo/.mvn', 'frontend/src', 'frontend/public', 'scripts/security'):
        directory = ROOT / root
        if directory.exists():
            files.extend(p for p in directory.rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    files.extend(ROOT / p for p in ('backend/demo/pom.xml', 'backend/demo/Dockerfile', 'backend/demo/mvnw',
                                   'frontend/Dockerfile', 'frontend/package.json', 'frontend/package-lock.json',
                                   'frontend/angular.json', 'frontend/nginx.conf', 'frontend/tsconfig.json', 'frontend/tsconfig.app.json'))
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(files)) if p.exists()}


def main():
    OUT.mkdir(parents=True, exist_ok=False)
    active_marker = OUT / '.runner-active'
    active_marker.write_text(str(os.getpid()))
    started = time.monotonic()
    log, owned, code = [], False, 3
    metadata = {'project': PROJECT, 'started_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
                'revision': os.environ.get('GITHUB_SHA', 'local-unverified-no-git'),
                'github_run_id': os.environ.get('GITHUB_RUN_ID'),
                'mode': 'passive-only', 'github_actions_environment': os.environ.get('GITHUB_ACTIONS') == 'true'}

    def event(message):
        log.append(message)
        print(message, flush=True)

    try:
        # Evitar contextos remotos: no contactar un daemon de producción.
        context = json.loads(invoke(['docker', 'context', 'inspect']).stdout)[0]
        if not context['Endpoints']['docker']['Host'].startswith('unix://'):
            raise RuntimeError('Se exige un daemon Docker local por socket Unix')
        metadata['docker_version'] = invoke(['docker', 'version', '--format', '{{.Server.Version}}']).stdout.strip()
        metadata['compose_version'] = invoke(['docker', 'compose', 'version', '--short']).stdout.strip()
        config = json.loads(invoke(COMPOSE + ['--profile', 'scan', 'config', '--format', 'json']).stdout)
        metadata['zap_image'] = assert_config(config)
        if any(resources(kind) for kind in ('container', 'network', 'volume')):
            raise RuntimeError('El proyecto ya tiene recursos; no se reutilizará')
        write('source-sha256.json', source_manifest())
        write('metadata.json', metadata)
        owned = True
        metadata['exclusive_resources_authorized'] = True
        write('metadata.json', metadata)
        event('Aislamiento estático aprobado; proyecto nuevo ' + PROJECT)
        event('Construyendo frontend y backend reales')
        invoke(COMPOSE + ['build', 'backend', 'frontend'], timeout=1500)
        invoke(COMPOSE + ['--profile', 'scan', 'pull', 'zap'], timeout=600)
        image = json.loads(invoke(['docker', 'image', 'inspect', metadata['zap_image']]).stdout)[0]
        metadata['zap_image_id'] = image['Id']
        metadata['zap_repo_digests'] = image['RepoDigests']
        event('Levantando servicios sin puertos; esperando readiness')
        invoke(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '300', 'mysql', 'backend', 'frontend'], timeout=360)
        verify_runtime()
        expected = sorted(int(p.name.split('__')[0][1:]) for p in
                          (ROOT / 'backend/demo/src/main/resources/db/migration').glob('V*__*.sql'))
        applied = [int(v) for v in mysql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank;').splitlines()]
        if applied != expected:
            raise RuntimeError('Flyway no coincide con las migraciones del código')
        if mysql('SELECT COUNT(*) FROM user_accounts;') != '0' or mysql('SELECT COUNT(*) FROM products;') != '0':
            raise RuntimeError('La base no está vacía antes de fixtures')
        mysql((ROOT / 'scripts/security/seed.sql').read_text())
        counts = mysql('SELECT (SELECT COUNT(*) FROM user_accounts),(SELECT COUNT(*) FROM products),(SELECT COUNT(*) FROM addresses);')
        if counts != '1\t1\t1':
            raise RuntimeError('Conteos de fixtures inesperados')
        write('fixtures.json', {'flyway_versions': applied, 'accounts': 1, 'products': 1, 'addresses': 1,
                                'role': 'COMPRADOR', 'reviews': 0, 'business_writes_scanned': False})
        event('Flyway y fixtures comprobados; iniciando ZAP pasivo')
        scanner = invoke(COMPOSE + ['--profile', 'scan', 'run', '--no-deps', '--name', PROJECT + '-zap', 'zap'],
                         check=False, timeout=600)
        metadata['scanner_exit_code'] = scanner.returncode
        invoke(['docker', 'cp', PROJECT + '-zap:/tmp/security-output/.', str(OUT)])
        status = json.loads((OUT / 'scanner-status.json').read_text())
        event('Scanner exit=' + str(scanner.returncode) + '; technical_status=' + status['technical_status'])
        if status['technical_status'] != 'ok' or status.get('coverage') != 'passed':
            raise RuntimeError('Scanner o cobertura falló; consultar scanner-status.json/execution.log')
        data = json.loads((OUT / 'zap-report.json').read_text())
        if not (OUT / 'zap-report.html').read_text().startswith('<!doctype html>'):
            raise RuntimeError('HTML inválido')
        if scanner.returncode not in (0, 1) or scanner.returncode != data['result']['exit_code']:
            raise RuntimeError('Código del scanner inconsistente')
        metadata['result'] = data['result']
        code = scanner.returncode
        event('Reportes HTML/JSON válidos; ' + data['result']['status'])
    except (Exception, KeyboardInterrupt) as error:
        metadata['technical_error'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        event('ERROR técnico: ' + metadata['technical_error'])
        code = 3
    finally:
        if owned:
            event('Limpiando exclusivamente ' + PROJECT)
            try:
                cleanup = invoke(COMPOSE + ['--profile', 'scan', 'down', '--volumes', '--remove-orphans', '--rmi', 'local'],
                                 check=False, timeout=180)
                remaining = {kind: resources(kind) for kind in ('container', 'network', 'volume')}
                cleaned = cleanup.returncode == 0 and not any(remaining.values())
                write('cleanup.json', {'project': PROJECT, 'command_exit_code': cleanup.returncode,
                                       'remaining': remaining, 'passed': cleaned})
            except Exception as error:
                cleaned = False
                write('cleanup.json', {'project': PROJECT, 'passed': False, 'error_type': type(error).__name__})
            if not cleaned:
                code = 3
            event('Limpieza comprobada=' + str(cleaned))
        else:
            write('cleanup.json', {'project': PROJECT, 'not_started': True, 'reason': 'Sin recursos propios creados'})
        metadata['duration_seconds'] = round(time.monotonic() - started, 3)
        metadata['finished_utc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        metadata['exit_code'] = code
        write('metadata.json', metadata)
        (OUT / 'runner.log').write_text('\n'.join(log) + '\n')
        active_marker.unlink(missing_ok=True)
        print('Evidencia: ' + str(OUT), flush=True)
    return code


if __name__ == '__main__':
    def terminate(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, terminate)
    raise SystemExit(main())
