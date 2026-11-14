"""Respaldo de limpieza para Actions: solo proyectos anotados por este runner."""
import json
import os
import re

from run import ROOT, invoke


def runner_active(directory):
    marker = directory / '.runner-active'
    if not marker.exists():
        return False
    pid = int(marker.read_text())
    if pid <= 0:
        raise RuntimeError('Marcador de runner inválido')
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def main():
    context = json.loads(invoke(['docker', 'context', 'inspect']).stdout)[0]
    if not context['Endpoints']['docker']['Host'].startswith('unix://'):
        raise RuntimeError('No se limpia un daemon remoto')
    failed = False
    for metadata_path in (ROOT / 'artifacts/security-zap').glob('rnf-zap-*/metadata.json'):
        metadata = json.loads(metadata_path.read_text())
        project = metadata.get('project', '')
        if not metadata.get('exclusive_resources_authorized'):
            continue
        if not re.fullmatch(r'rnf-zap-[a-f0-9]{12}', project) or metadata_path.parent.name != project:
            raise RuntimeError('Identidad de proyecto no válida')
        if runner_active(metadata_path.parent):
            print(project + ': runner activo; no se interrumpe')
            continue
        cleanup_path = metadata_path.parent / 'cleanup.json'
        if cleanup_path.exists() and json.loads(cleanup_path.read_text()).get('passed'):
            continue
        command = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(ROOT / 'compose.security.yaml'),
                   '-p', project, '--profile', 'scan', 'down', '--volumes', '--remove-orphans', '--rmi', 'local']
        result = invoke(command, check=False, timeout=180)
        remaining = {}
        for kind in ('container', 'network', 'volume'):
            args = ['docker', kind, 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]
            if kind == 'container':
                args.append('--all')
            remaining[kind] = invoke(args).stdout.split()
        passed = result.returncode == 0 and not any(remaining.values())
        cleanup_path.write_text(json.dumps({'project': project, 'fallback': True,
                                           'command_exit_code': result.returncode, 'remaining': remaining, 'passed': passed}, indent=2))
        failed |= not passed
        print(project + ': limpieza de respaldo=' + str(passed))
    return 3 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
