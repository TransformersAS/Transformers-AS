"""Comprueba que un cluster no apto falla ANTES de cualquier mutación Docker."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class DeployGuardTests(unittest.TestCase):
    def test_one_node_rejected_before_pull_secret_or_deploy(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            docker = folder / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['DOCKER_CALLS'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
a = sys.argv[1:]
if a == ['info']:
    pass
elif a[:2] == ['info', '--format']:
    print('active' if 'LocalNodeState' in a[2] else 'true')
elif a == ['node', 'ls', '-q']:
    print('single-node')
elif a[:2] == ['node', 'inspect']:
    print('ready active linux')
else:
    raise SystemExit('Unexpected Docker command: ' + repr(a))
''')
            docker.chmod(0o755)
            calls = folder / 'calls.jsonl'
            env = os.environ.copy()
            env.update(PATH=str(folder) + os.pathsep + env['PATH'], DOCKER_CALLS=str(calls))
            result = subprocess.run(['bash', str(ROOT / 'deploy.sh')], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('al menos dos nodos', result.stderr)
            log = calls.read_text()
            for forbidden in ('"pull"', '"secret"', '"stack"', '"build"', '"swarm"'):
                self.assertNotIn(forbidden, log)
