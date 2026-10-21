#!/usr/bin/env python3
"""Mediciones en el cliente/manager. Solo `deployability` ejecuta deploy.sh; ningún modo
registra cuentas ni mata contenedores."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def command(*args):
    return subprocess.check_output(args, text=True, timeout=15).strip()


def save(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def tasks(stack):
    ids = command('docker', 'service', 'ps', '--filter', 'desired-state=running',
                  '-q', stack + '_backend').split()
    if not ids:
        return []
    raw = json.loads(command('docker', 'inspect', '--type', 'task', *ids))
    return [{'id': t['ID'], 'node': t['NodeID'], 'state': t['Status']['State']}
            for t in raw]


def distributed(rows):
    running = [t for t in rows if t['state'] == 'running']
    return len(running) == 2 and len({t['node'] for t in running}) == 2


def readiness(base_url):
    try:
        with urlopen(base_url + '/api/actuator/health/readiness', timeout=3) as response:
            return response.status == 200 and json.load(response).get('status') == 'UP'
    except (OSError, ValueError):
        return False


def metrics(summary):
    """--summary-export clásico y estructura values: conservar métricas, no reinterpretar Rates."""
    data = summary.get('metrics', {})
    names = ('http_reqs', 'http_req_failed', 'http_req_duration', 'checks',
             'catalog_list_duration', 'catalog_detail_duration', 'catalog_error_rate',
             'catalog_throughput', 'catalog_requests', 'catalog_successes', 'catalog_failures')
    return {name: data[name].get('values', data[name]) for name in names if name in data}


class Recovery:
    """T0 observado por polling; no equivale al instante exacto del kill."""
    def __init__(self, baseline):
        self.original = {t['id'] for t in baseline}
        self.lost = None
        self.t0 = None
        self.t1 = None
        self.stable = 0
        self.invalid = None

    def observe(self, rows, up, elapsed):
        running = {t['id'] for t in rows if t['state'] == 'running'}
        missing = self.original - running
        if self.t0 is None and missing:
            self.t0 = elapsed
            if len(missing) != 1:
                self.invalid = 'Desapareció más de una task original; no es la prueba de UNA réplica.'
            self.lost = missing
        if self.t0 is not None and self.t1 is None:
            survivor = self.original - self.lost
            if not survivor.issubset(running):
                self.invalid = 'También desapareció la otra task original.'
            recovered = distributed(rows) and up and bool(running - self.original)
            self.stable = self.stable + 1 if recovered else 0
            if self.stable >= 3:
                self.t1 = elapsed

    def result(self):
        return {'failure_observed': self.t0 is not None,
                'replacement_observed': self.t1 is not None,
                'first_missing_task_seconds': self.t0,
                'stable_replacement_seconds': self.t1,
                'observed_recovery_seconds': None if self.t1 is None else round(self.t1 - self.t0, 3),
                'invalid_reason': self.invalid,
                'definition': 'Tres muestras: dos tasks Running en nodos distintos y readiness proxy UP. '
                              'No consulta health individual ni prueba por sí sola que hubo docker kill.'}


def run_k6(args, output, label, vus, duration, monitor=False):
    summary = output / (label + '.json')
    samples_path = output / (label + '-samples.jsonl')
    baseline = tasks(args.stack) if monitor else None
    if monitor and (not distributed(baseline) or not readiness(args.base_url)):
        raise RuntimeError('Availability exige dos backends Running en nodos distintos y readiness UP.')
    recovery = Recovery(baseline) if monitor else None
    invocation = ['k6', 'run', '-e', 'BASE_URL=' + args.base_url, '-e', 'VUS=' + str(vus),
                  '-e', 'DURATION=' + duration, '--summary-export', str(summary),
                  str(ROOT / 'scripts/k6/catalog-browse.js')]
    started = time.monotonic()
    print(f'{label}: {vus} VUs, {duration}, {args.base_url}', flush=True)
    if monitor:
        print('PC MANAGER: observación activa. PC WORKER: después de 30 s de tráfico, '
              'matar UNA task backend identificada según la guía. Este script NO la mata.', flush=True)
    with (output / (label + '.log')).open('w') as log:
        process = subprocess.Popen(invocation, stdout=log, stderr=subprocess.STDOUT)
        try:
            with samples_path.open('w') as sample_file:
                while process.poll() is None:
                    if monitor:
                        rows = tasks(args.stack)
                        up = readiness(args.base_url)
                        elapsed = round(time.monotonic() - started, 3)
                        recovery.observe(rows, up, elapsed)
                        sample = {'utc': timestamp(), 'elapsed_seconds': elapsed,
                                  'tasks': rows, 'readiness': up}
                        sample_file.write(json.dumps(sample) + '\n')
                        sample_file.flush()
                        print(f'{elapsed}s: readiness={up}, tasks={rows}', flush=True)
                    time.sleep(2)
        except BaseException:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise
    print((output / (label + '.log')).read_text())
    result = {'label': label, 'vus': vus, 'duration': duration,
              'k6_exit_code': process.returncode, 'base_url': args.base_url,
              'metrics': metrics(json.loads(summary.read_text())) if summary.exists() else {},
              'recovery': recovery.result() if recovery else None}
    valid = process.returncode == 0 and summary.exists()
    if recovery:
        valid = valid and recovery.t1 is not None and recovery.invalid is None
    result['measurement_passed'] = valid
    result['physical_computers_verified'] = False
    save(output / (label + '-result.json'), result)
    return valid


# Réplicas que stack.yml declara; el despliegue no está completo con menos.
EXPECTED_REPLICAS = {'frontend': 2, 'backend': 2, 'mysql': 1}
# Mismo límite que deploy.sh aplica a la convergencia (DEPLOY_TIMEOUT_SECONDS por defecto).
CONVERGENCE_LIMIT_SECONDS = 600
STACK_DEPLOY_LINE = re.compile(r'^(Creating|Updating) (service|network) ')
CONVERGED_LINE = 'Frontend y API disponibles'


def deploy_ports(local):
    """Los mismos puertos por defecto que deploy.sh, para sondear lo que acaba de publicar."""
    backend = os.environ.get('BACKEND_HOST_PORT') or ('18080' if local else '8080')
    frontend = os.environ.get('FRONTEND_HOST_PORT') or ('18000' if local else '80')
    return int(backend), int(frontend)


def probe(url):
    started = time.monotonic()
    try:
        with urlopen(url, timeout=5) as response:
            up = response.status == 200 and json.load(response).get('status') == 'UP'
            status = response.status
    except HTTPError as error:
        up, status = False, error.code
    except (OSError, ValueError):
        up, status = False, None
    return {'url': url, 'up': up, 'http_status': status,
            'latency_ms': round((time.monotonic() - started) * 1000, 1)}


def optional(*args):
    """Consulta que puede fallar legítimamente (Swarm inactivo, stack o volumen aún inexistente)."""
    try:
        return subprocess.check_output(args, text=True, timeout=15, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.SubprocessError):
        return None


def stack_state(stack):
    services = {}
    for name in EXPECTED_REPLICAS:
        service = f'{stack}_{name}'
        ids = (optional('docker', 'service', 'ps', '--filter', 'desired-state=running', '-q', service) or '').split()
        raw = json.loads(command('docker', 'inspect', '--type', 'task', *ids)) if ids else []
        running = [t for t in raw if t['Status']['State'] == 'running']
        services[name] = {'running': len(running), 'expected': EXPECTED_REPLICAS[name],
                          'nodes': sorted({t['NodeID'] for t in running}),
                          'tasks': [{'id': t['ID'], 'node': t['NodeID'], 'state': t['Status']['State'],
                                     'since': t['Status']['Timestamp']} for t in raw],
                          'image': optional('docker', 'service', 'inspect', '--format',
                                            '{{.Spec.TaskTemplate.ContainerSpec.Image}}', service)}
    return services


def persistent_state(stack):
    """Lo que un redespliegue no debe recrear: volumen MySQL y secrets del stack."""
    secrets = optional('docker', 'secret', 'ls', '--filter', 'name=' + stack + '_',
                       '--format', '{{.ID}} {{.Name}}') or ''
    return {'mysql_volume_created': optional('docker', 'volume', 'inspect', '--format',
                                             '{{.CreatedAt}}', stack + '_mysql_data'),
            'secrets': sorted(secrets.splitlines())}


def secret_files(stack, folder):
    """Solo --local: contraseñas aleatorias de un uso para que deploy.sh no pida nada por teclado.
    Se borran al terminar y nunca se escriben en la evidencia; secrets existentes se reutilizan."""
    files = {}
    for variable, suffix in (('DB_PASSWORD_SECRET_FILE', 'db_password_v1'),
                             ('MYSQL_ROOT_PASSWORD_SECRET_FILE', 'mysql_root_password_v1')):
        if os.environ.get(variable) or optional('docker', 'secret', 'inspect', f'{stack}_{suffix}'):
            continue
        path = Path(folder) / suffix
        path.write_text(os.urandom(18).hex())
        files[variable] = str(path)
    return files


class AvailabilityDuringDeploy:
    """Sondea el readiness del proxy cada segundo mientras corre deploy.sh."""
    def __init__(self, url):
        self.url = url
        self.samples = []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            self.samples.append(probe(self.url)['up'])
            self.stop.wait(1)

    def result(self):
        ok = sum(self.samples)
        return {'url': self.url, 'samples': len(self.samples), 'up_samples': ok,
                'up_ratio': round(ok / len(self.samples), 4) if self.samples else None}


def run_deploy(args, output, label, local):
    backend_port, frontend_port = deploy_ports(local)
    before = persistent_state(args.stack)
    stack_existed = optional('docker', 'stack', 'services', '-q', args.stack) not in (None, '')
    bash = shutil.which('bash')
    if not bash:
        raise RuntimeError('Se requiere bash (Git Bash en Windows) para ejecutar deploy.sh.')
    # Exactamente un comando, el mismo que se ejecutaría a mano; sin pasos intermedios.
    invocation = [bash, str(ROOT / 'deploy.sh')] + (['--local'] if local else [])
    env = os.environ.copy()
    env['STACK_NAME'] = args.stack
    watcher = AvailabilityDuringDeploy(f'http://127.0.0.1:{frontend_port}/api/actuator/health/readiness')
    marks = {}
    print(f'{label}: {" ".join(invocation[1:])} (stack {args.stack})', flush=True)
    with tempfile.TemporaryDirectory() as folder:
        if local:
            env.update(secret_files(args.stack, folder))
        started = time.monotonic()
        watcher.thread.start()
        with (output / (label + '.log')).open('w', encoding='utf-8') as log:
            process = subprocess.Popen(invocation, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding='utf-8', errors='replace')
            try:
                for line in process.stdout:
                    elapsed = round(time.monotonic() - started, 3)
                    log.write(f'[{elapsed:9.3f}s] {line}')
                    log.flush()
                    print(f'[{elapsed:9.3f}s] {line}', end='', flush=True)
                    if STACK_DEPLOY_LINE.match(line):
                        marks.setdefault('stack_deploy_started', elapsed)
                    if CONVERGED_LINE in line:
                        marks.setdefault('converged', elapsed)
                process.wait()
            except BaseException:
                process.kill()
                process.wait()
                raise
            finally:
                watcher.stop.set()
                watcher.thread.join()
        total = round(time.monotonic() - started, 3)
    services = stack_state(args.stack)
    probes = [probe(f'http://127.0.0.1:{backend_port}/actuator/health/readiness'),
              probe(f'http://127.0.0.1:{frontend_port}/healthz'),
              probe(f'http://127.0.0.1:{frontend_port}/api/actuator/health/readiness')]
    after = persistent_state(args.stack)
    deployed = marks.get('stack_deploy_started')
    converged = marks.get('converged')
    checks = {
        'exit_code_zero': process.returncode == 0,
        'converged_message': converged is not None,
        'replicas_complete': all(s['running'] == s['expected'] for s in services.values()),
        'health_endpoints_up': all(p['up'] for p in probes),
        'convergence_within_limit': deployed is not None and converged is not None
                                    and converged - deployed <= CONVERGENCE_LIMIT_SECONDS,
    }
    if not local:
        checks['app_replicas_on_distinct_nodes'] = all(
            len(services[name]['nodes']) >= 2 for name in ('frontend', 'backend'))
    if stack_existed:
        # Redespliegue: no debe recrear el volumen de datos ni rotar secrets en silencio.
        checks['mysql_volume_preserved'] = (before['mysql_volume_created'] is not None
                                            and before['mysql_volume_created'] == after['mysql_volume_created'])
        checks['secrets_preserved'] = bool(before['secrets']) and before['secrets'] == after['secrets']
    result = {
        'label': label, 'mode': 'local' if local else 'cluster', 'stack': args.stack,
        'redeploy_over_existing_stack': stack_existed,
        'command': ' '.join(['bash', 'deploy.sh'] + (['--local'] if local else [])),
        'manual_commands': 1, 'interactive_prompts': 0,
        'deploy_exit_code': process.returncode,
        'timings_seconds': {
            'total': total,
            'preparation_until_stack_deploy': deployed,
            'stack_deploy_to_converged': None if None in (deployed, converged) else round(converged - deployed, 3),
            'convergence_limit': CONVERGENCE_LIMIT_SECONDS,
        },
        'services': services, 'health_probes': probes,
        'availability_during_deploy': watcher.result(),
        'persistent_state_before': before, 'persistent_state_after': after,
        'checks': checks,
        'measurement_passed': all(checks.values()),
        'physical_computers_verified': False,
    }
    save(output / (label + '-result.json'), result)
    times = result['timings_seconds']
    print(f'{label}: exit={process.returncode} total={total}s preparación={deployed}s '
          f'convergencia={times["stack_deploy_to_converged"]}s '
          f'disponibilidad durante deploy={watcher.result()["up_ratio"]} '
          f'checks={checks}', flush=True)
    return result['measurement_passed']


def deployability(args, parser):
    if not 1 <= args.runs <= 5:
        parser.error('--runs debe estar entre 1 y 5')
    if not re.fullmatch(r'[a-z][a-z0-9_-]*', args.stack):
        parser.error('--stack no válido')
    output = args.output.resolve() / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + str(os.getpid()))
    output.mkdir(parents=True, exist_ok=False)
    metadata = {'utc': timestamp(), 'mode': 'deployability', 'stack': args.stack,
                'deploy_mode': 'local' if args.local else 'cluster', 'runs': args.runs,
                'sha': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
                'working_tree': command('git', '-C', str(ROOT), 'status', '--short'),
                'docker_version': optional('docker', 'version', '--format', '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'),
                'swarm_nodes_before': optional('docker', 'node', 'ls', '--format',
                                               '{{.ID}} {{.Hostname}} {{.Status}} {{.Availability}} {{.ManagerStatus}}'),
                'thresholds': {'manual_commands': 1, 'interactive_prompts': 0,
                               'stack_deploy_to_converged_max_seconds': CONVERGENCE_LIMIT_SECONDS,
                               'replicas': EXPECTED_REPLICAS},
                'physical_computers_verified': False}
    save(output / 'context.json', metadata)
    print(f'Evidencia: {output}', flush=True)
    results = []
    try:
        for index in range(1, args.runs + 1):
            results.append(run_deploy(args, output, f'deploy-{index}', args.local))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        save(output / 'error.json', {'utc': timestamp(), 'error': str(error), 'measurement_passed': False})
        print(f'Prueba incompleta: {error}', file=sys.stderr)
        return 1
    passed = bool(results) and all(results)
    save(output / 'summary.json', {
        'runs': [json.loads((output / f'deploy-{i}-result.json').read_text(encoding='utf-8'))['timings_seconds'] | {
            'passed': ok} for i, ok in enumerate(results, 1)],
        'swarm_nodes_after': optional('docker', 'node', 'ls', '--format',
                                      '{{.ID}} {{.Hostname}} {{.Status}} {{.Availability}} {{.ManagerStatus}}'),
        'measurement_passed': passed, 'physical_computers_verified': False})
    print('Medición PASS' if passed else 'Medición FAIL; revisar evidencia, no cambiar umbrales.')
    return 0 if passed else 1


def main():
    # En Windows la consola/redirección usa cp1252: la salida de docker build y npm trae Unicode.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['performance', 'availability', 'deployability'])
    parser.add_argument('--base-url', help='URL frontend; sin /api ni contraseña')
    parser.add_argument('--stack', default='transformers')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/quality')
    parser.add_argument('--local', action='store_true',
                        help='deployability: ensayo de un nodo (deploy.sh --local); no acredita dos PCs')
    parser.add_argument('--runs', type=int, default=2,
                        help='deployability: ejecuciones seguidas; la 2.ª mide el redespliegue idempotente')
    args = parser.parse_args()
    if args.mode == 'deployability':
        return deployability(args, parser)
    if not args.base_url:
        parser.error('--base-url es obligatorio en performance y availability')
    args.base_url = args.base_url.rstrip('/')
    from urllib.parse import urlsplit
    url = urlsplit(args.base_url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.path or url.query or url.fragment:
        parser.error('--base-url debe ser origen frontend http(s), sin path, credenciales ni query')
    if not os.environ.get('CATALOG_EMAIL') or not os.environ.get('CATALOG_PASSWORD'):
        parser.error('Exportar CATALOG_EMAIL y CATALOG_PASSWORD de cuenta de prueba verificada.')
    # No heredar umbrales relajados que falsearían la comparación del ASR existente.
    os.environ.pop('P95_LIMIT_MS', None)
    os.environ.pop('ERROR_RATE_LIMIT', None)
    output = args.output.resolve() / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + str(os.getpid()))
    output.mkdir(parents=True, exist_ok=False)
    metadata = {'utc': timestamp(), 'mode': args.mode, 'base_url': args.base_url,
                'stack': args.stack, 'sha': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
                'working_tree': command('git', '-C', str(ROOT), 'status', '--short'),
                'k6_version': command('k6', 'version'),
                'thresholds': {'catalog_p95_ms': 3000, 'catalog_error_rate_less_than': 0.02},
                'physical_computers_verified': False}
    save(output / 'context.json', metadata)
    print(f'Evidencia: {output}', flush=True)
    try:
        if args.mode == 'performance':
            passed50 = run_k6(args, output, 'catalogo-50', 50, '1m')
            passed100 = run_k6(args, output, 'catalogo-100', 100, '1m')
            passed = passed50 and passed100
        else:
            passed = run_k6(args, output, 'availability', 50, '3m', monitor=True)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        save(output / 'error.json', {'utc': timestamp(), 'error': str(error), 'measurement_passed': False})
        print(f'Prueba incompleta: {error}', file=sys.stderr)
        return 1
    print('Medición PASS' if passed else 'Medición FAIL; revisar evidencia, no cambiar umbrales.')
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
