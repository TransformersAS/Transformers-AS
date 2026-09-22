"""Prueba del launcher portable en macOS/Linux; Docker falso solo para fallos de infraestructura.
Uso: python3 launcher/tests/smoke.py /ruta/a/dotnet
No forma parte del launcher ni de sus prerrequisitos. No levanta ni altera contenedores.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile

repo = Path(__file__).resolve().parents[2]
dotnet = str(Path(sys.argv[1]).resolve())
subprocess.run([dotnet, 'build', str(repo / 'launcher/Marketplace/Marketplace.csproj'), '-c', 'Release'], check=True)
with tempfile.TemporaryDirectory(prefix='Marketplace entrega con espacios ') as temp:
    root = Path(temp).resolve()
    (root / 'frontend').mkdir()
    (root / 'backend/demo').mkdir(parents=True)
    for name in ['compose.yaml', 'compose.demo.yaml']:
        shutil.copy(repo / name, root / name)
    output = repo / 'launcher/Marketplace/bin/Release/net8.0'
    for name in ['Marketplace.dll', 'Marketplace.runtimeconfig.json', 'Marketplace.deps.json']:
        shutil.copy(output / name, root / name)
    fake = root / 'bin'
    fake.mkdir()
    docker = fake / 'docker'
    docker.write_text('#!' + sys.executable + '\n' + '''
import os, sys, json
from pathlib import Path
args = sys.argv[1:]
mode = os.environ.get('TEST_MODE')
if args == ['--version']: print('Docker test'); sys.exit(0)
if args[0] == 'info':
    if mode == 'engine-off': sys.exit(1)
    print('linux'); sys.exit(0)
if args == ['compose', 'version']:
    if mode == 'compose-missing': sys.exit(1)
    print('Docker Compose v2'); sys.exit(0)
Path('captured.json').write_text(json.dumps({'args': args, 'password': os.environ.get('DB_PASSWORD')}))
sys.exit(17)
''')
    docker.chmod(0o755)
    env = os.environ.copy()
    def run(mode, path=None):
        return subprocess.run([dotnet, str(root / 'Marketplace.dll')], cwd='/tmp',
            env={**env, 'PATH': str(fake) if path is None else path, 'TEST_MODE': mode,
                 'DB_PASSWORD': 'inherited-value-must-not-win'}, capture_output=True, text=True, timeout=30)
    assert 'No se encontró Docker' in run('missing', '').stdout
    assert 'Docker Desktop no está listo' in run('engine-off').stdout
    assert 'se necesita Docker Compose' in run('compose-missing').stdout
    assert not (root / '.env.demo').exists()
    (root / '.env').write_text('DEVELOPMENT_FILE=preserve\n')
    result = run('up-fails')
    assert result.returncode == 1 and 'No se pudo iniciar MySQL' in result.stdout, result.stdout
    original = (root / '.env.demo').read_text()
    values = dict(line.split('=', 1) for line in original.splitlines())
    assert len(values['DB_PASSWORD']) == 64
    assert values['DB_PASSWORD'] != values['MYSQL_ROOT_PASSWORD']
    assert (root / '.env').read_text() == 'DEVELOPMENT_FILE=preserve\n'
    captured = json.loads((root / 'captured.json').read_text())
    assert captured['password'] == values['DB_PASSWORD']
    assert str(root / 'compose.yaml') in captured['args']
    assert captured['args'][captured['args'].index('-p') + 1] == 'marketplace-demo'
    assert values['DB_PASSWORD'] not in (root / 'launcher-logs/marketplace.log').read_text()
    run('up-fails')
    assert (root / '.env.demo').read_text() == original
    (root / '.env').unlink()
    run('up-fails')
    assert (root / '.env').read_text() == original
    (root / 'compose.demo.yaml').unlink()
    assert 'Descomprima la entrega completa' in run('up-fails').stdout
print('OK: rutas con espacios y cwd ajeno, Docker ausente/apagado/sin Compose, error visible, secretos aleatorios, entorno heredado, conservación de .env y reinicios idempotentes.')
