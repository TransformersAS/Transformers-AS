#!/usr/bin/env python3
"""Mediciones en el cliente/manager. No despliega, registra cuentas ni mata contenedores."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from datetime import datetime, timezone
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def command(*args):
    return subprocess.check_output(args, text=True, timeout=15).strip()


def save(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['performance', 'availability'])
    parser.add_argument('--base-url', required=True, help='URL frontend; sin /api ni contraseña')
    parser.add_argument('--stack', default='transformers')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/quality')
    args = parser.parse_args()
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
