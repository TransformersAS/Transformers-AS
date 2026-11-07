#!/usr/bin/env python3
"""Allowlisted Compose procedures. No default environment or project; no automatic cleanup."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DOCKER = ['docker', '--context', 'desktop-linux']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('variant', choices=['normal', 'demo'])
parser.add_argument('action', choices=['config', 'preflight', 'build', 'start', 'inspect', 'seed-local', 'clean'])
args = parser.parse_args()
if not __debug__:
    parser.error('Python optimization is not allowed: isolation assertions must remain enabled')
demo = args.variant == 'demo'
if demo and args.action == 'seed-local':
    parser.error('seed-local is exclusive to the normal laboratory')
project = 'cu08-topology-' + args.variant + '-20261107'
database = 'cu08_topology_' + args.variant
ports = {'frontend': 14431 if demo else 14430,
         'backend': 18431 if demo else 18430, 'mysql': 13431 if demo else 13430}
proxy = '172.30.239.10' if demo else '172.30.238.10'
# These are public disposable fixtures, not inherited or production credentials.
env = {'PATH': os.environ['PATH'], 'HOME': os.environ['HOME'],
       'TOPOLOGY_PROJECT': project, 'DB_NAME': database, 'DB_USER': 'topology_fixture',
       'DB_PASSWORD': 'topology_' + args.variant + '_disposable_only',
       'MYSQL_ROOT_PASSWORD': 'topology_' + args.variant + '_root_disposable_only',
       'FRONTEND_PORT': str(ports['frontend']), 'BACKEND_HOST_PORT': str(ports['backend']),
       'MYSQL_HOST_PORT': str(ports['mysql'])}
if not demo:
    # Match desplegar-local.ps1; Compose includes compose-local independently.
    env['SPRING_PROFILES_ACTIVE'] = 'local'
compose = DOCKER + ['compose', '--env-file', '/dev/null', '--project-directory', str(REPO),
                    '-p', project, '-f', str(REPO / 'compose.yaml')]
if demo:
    compose += ['-f', str(REPO / 'compose.demo.yaml')]
compose += ['-f', str(HERE / 'limits.override.yaml')]
if not demo:
    compose += ['-f', str(HERE / 'normal-cors.override.yaml')]


def run(command, capture=True, input_text=None):
    result = subprocess.run(command, env=env, cwd=REPO, text=True,
                            capture_output=capture, input=input_text)
    if result.returncode:
        # Config output may contain fixtures: never dump it on errors.
        raise RuntimeError('Command failed; stopped without fallback or cleanup')
    return result.stdout if capture else ''


def local_catalog_sql():
    # Pin reviewed originals; changes require a new review, never execute arbitrary SQL.
    sources = [('catalogo-local.sql', '2f6fe14ea5d51420f0d1616222a80919916473df4c74423f88758032c74504c2'),
               ('fichas-productos-local.sql', '186a82f0c31a8c97d8371468b2aa031158f5e8318ab1f4b167af10538c810f34')]
    chunks = []
    for name, expected in sources:
        data = (REPO / 'scripts' / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == expected, 'SQL changed: review required before catalog preparation'
        chunks.append(data.decode('utf-8'))
    return '\n'.join(chunks)


def audit():
    endpoint = json.loads(run(DOCKER + ['context', 'inspect', 'desktop-linux']))[0]
    assert endpoint['Endpoints']['docker']['Host'].startswith('unix://'), 'Nonlocal daemon refused'
    run(DOCKER + ['info', '--format', '{{.ServerVersion}}'])
    c = json.loads(run(compose + ['config', '--format', 'json']))
    assert c['name'] == project
    assert set(c['services']) == {'frontend', 'backend', 'mysql'}
    assert set(c['networks']) == {'web_entry', 'login_proxy', 'database', 'mysql_host'}
    assert set(c['volumes']) == {'mysql_data'}
    assert c['volumes']['mysql_data']['name'] == project + '_mysql_data'
    assert not c['volumes']['mysql_data'].get('external')
    assert not c.get('secrets') and not c.get('configs')
    memberships = {'frontend': {'web_entry', 'login_proxy'},
                   'backend': {'web_entry', 'login_proxy', 'database'},
                   'mysql': {'database', 'mysql_host'}}
    for name, n in c['networks'].items():
        assert n['name'] == project + '_' + name and not n.get('external')
        assert bool(n.get('internal')) == (name in ('login_proxy', 'database'))
    for name, s in c['services'].items():
        assert not s.get('container_name') and not s.get('network_mode')
        assert not s.get('env_file') and not s.get('external_links')
        assert set(s['networks']) == memberships[name]
        assert float(s['cpus']) > 0 and int(s['mem_limit']) > 0
        assert len(s['ports']) == 1
        p = s['ports'][0]
        assert p['host_ip'] == '127.0.0.1' and int(p['published']) == ports[name]
        volumes = s.get('volumes', [])
        if name == 'mysql':
            assert len(volumes) == 1 and volumes[0]['type'] == 'volume'
            assert volumes[0]['source'] == 'mysql_data' and volumes[0]['target'] == '/var/lib/mysql'
        elif name == 'frontend':
            assert len(volumes) == 1 and volumes[0]['read_only']
            assert volumes[0]['type'] == 'bind'
            assert Path(volumes[0]['source']).resolve() == REPO / 'frontend/nginx.compose.conf'
            assert volumes[0]['target'] == '/etc/nginx/templates/default.conf.template'
        else:
            assert not volumes
        if name != 'mysql':
            assert s['image'] == project + '-' + name + ':validation'
            assert Path(s['build']['context']).resolve() == REPO / ('frontend' if name == 'frontend' else 'backend/demo')
    b = c['services']['backend']['environment']
    m = c['services']['mysql']['environment']
    assert b['DB_HOST'] == 'mysql' and str(b['DB_PORT']) == '3306'
    assert b['DB_NAME'] == m['MYSQL_DATABASE'] == database
    assert b['DB_USER'] == m['MYSQL_USER'] == env['DB_USER']
    assert b['DB_PASSWORD'] == m['MYSQL_PASSWORD'] == env['DB_PASSWORD']
    assert m['MYSQL_ROOT_PASSWORD'] == env['MYSQL_ROOT_PASSWORD']
    assert b['APP_AUTH_LOGIN_ATTEMPTS_ENABLED'] == b['APP_AUTH_LOGIN_ATTEMPTS_ORIGIN_VERIFIED'] == 'false'
    assert b['SPRING_PROFILES_INCLUDE'] == 'compose-local'
    assert b['SPRING_PROFILES_ACTIVE'] == ('demo' if demo else 'local')
    if demo:
        assert 'APP_CORS_ALLOWED_ORIGINS' not in b, 'Normal laboratory CORS must not affect demo'
    else:
        assert b['APP_CORS_ALLOWED_ORIGINS'].split(',') == [
            'http://localhost:4300', 'http://localhost:4200', 'http://localhost:8100',
            'http://127.0.0.1:14430'], 'Unexpected CORS origin in normal laboratory'
    for key in ('MERCADOPAGO_ACCESS_TOKEN', 'MERCADOPAGO_WEBHOOK_SECRET', 'SPRING_MAIL_PASSWORD'):
        assert not b.get(key)
    assert c['services']['frontend']['environment']['BACKEND_UPSTREAM_HOST'] == 'backend-login-proxy'
    assert c['services']['frontend']['networks']['login_proxy']['ipv4_address'] == proxy
    assert b['APP_AUTH_LOGIN_ATTEMPTS_TRUSTED_PROXY_ADDRESSES'] == proxy
    assert b['SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES'] == '\\Q' + proxy + '\\E'
    assert b['SERVER_FORWARD_HEADERS_STRATEGY'] == 'native'
    for net, settings in c['services']['backend']['networks'].items():
        assert (settings or {}).get('aliases', []) == (['backend-login-proxy'] if net == 'login_proxy' else [])
    if not demo:
        local_catalog_sql()  # Static hash/UTF-8 review only, no SQL execution.
    return c


def free_resources(c):
    assert not run(DOCKER + ['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).strip(), 'Project already exists'
    for kind, names in [('network', [n['name'] for n in c['networks'].values()]),
                        ('volume', [project + '_mysql_data'])]:
        present = run(DOCKER + [kind, 'ls', '--format', '{{.Name}}']).splitlines()
        assert not set(names).intersection(present), 'Refusing existing resources'
    for p in ports.values():
        with socket.socket() as s:
            assert s.connect_ex(('127.0.0.1', p)) != 0, 'Host port occupied'
    ids = run(DOCKER + ['network', 'ls', '-q']).split()
    networks = json.loads(run(DOCKER + ['network', 'inspect'] + ids))
    wanted = ipaddress.ip_network('172.30.239.0/24' if demo else '172.30.238.0/24')
    for n in networks:
        for ipam in n['IPAM'].get('Config') or []:
            if 'Subnet' in ipam:
                net = ipaddress.ip_network(ipam['Subnet'])
                assert net.version != 4 or not net.overlaps(wanted), 'Proxy subnet occupied'


def owned_resources(c):
    ids = run(DOCKER + ['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).split()
    if ids:
        for x in json.loads(run(DOCKER + ['inspect'] + ids)):
            assert x['Config']['Labels']['com.docker.compose.service'] in c['services']
            assert set(x['NetworkSettings']['Networks']).issubset({n['name'] for n in c['networks'].values()})
            for mount in x['Mounts']:
                assert (mount['Type'] == 'volume' and mount.get('Name') == project + '_mysql_data') or (
                    mount['Type'] == 'bind' and Path(mount['Source']).resolve() == REPO / 'frontend/nginx.compose.conf' and not mount['RW'])
    for kind, names in [('network', [n['name'] for n in c['networks'].values()]),
                        ('volume', [project + '_mysql_data'])]:
        present = run(DOCKER + [kind, 'ls', '--format', '{{.Name}}']).splitlines()
        for name in set(names).intersection(present):
            obj = json.loads(run(DOCKER + [kind, 'inspect', name]))[0]
            assert obj.get('Labels', {}).get('com.docker.compose.project') == project, 'Foreign resource refused'


def seed_local(c):
    assert not demo and project == 'cu08-topology-normal-20261107'
    assert database == 'cu08_topology_normal'
    payload = local_catalog_sql()
    owned_resources(c)
    services = {}
    for service in ('backend', 'mysql'):
        ids = run(compose + ['ps', '-q', service]).split()
        assert len(ids) == 1, 'Exactly one laboratory service required'
        obj = json.loads(run(DOCKER + ['inspect', ids[0]]))[0]
        labels = obj['Config']['Labels']
        assert labels['com.docker.compose.project'] == project
        assert labels['com.docker.compose.service'] == service
        assert obj['State']['Status'] == 'running'
        assert obj['State'].get('Health', {}).get('Status') == 'healthy'
        actual = dict(v.split('=', 1) for v in obj['Config']['Env'] if '=' in v)
        expected = c['services'][service]['environment']
        keys = ('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'SPRING_PROFILES_ACTIVE') if service == 'backend' else ('MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')
        assert all(actual.get(k) == str(expected[k]) for k in keys), 'Runtime database/profile mismatch'
        if service == 'mysql':
            data_mounts = [m for m in obj['Mounts'] if m['Destination'] == '/var/lib/mysql']
            assert len(data_mounts) == 1 and data_mounts[0].get('Name') == project + '_mysql_data'
        services[service] = obj
    # Same readiness condition/retries as PowerShell, scoped to the inspected backend ID.
    ready = json.loads(run(DOCKER + ['exec', services['backend']['Id'], 'curl', '--fail',
        '--silent', '--show-error', '--retry', '12', '--retry-delay', '5', '--retry-all-errors',
        '--max-time', '5', 'http://localhost:8080/actuator/health/readiness']))
    assert ready['status'] == 'UP', 'Readiness must include MySQL before SQL'
    # Explicit local endpoint, user and DB. Ignore client option files; no target arguments from user.
    mysql = DOCKER + ['exec', '-i', services['mysql']['Id'], 'sh', '-c',
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 '
        '--default-character-set=utf8mb4 --user=topology_fixture --database=cu08_topology_normal '
        '--batch --skip-column-names']
    identity = run(mysql, input_text='SELECT DATABASE(), @@hostname;\n').strip().split('\t')
    assert identity == [database, services['mysql']['Config']['Hostname']], 'Database/server identity mismatch'
    run(mysql, input_text=payload)  # No --force: first SQL error stops the client.
    print(project + ': original catalog and product-detail SQL completed; SQL output suppressed')


try:
    config = audit()  # Always audit the effective root + override configuration first.
    if args.action == 'config':
        print(project + ': effective configuration and isolation PASS; no resources created')
    elif args.action == 'preflight':
        free_resources(config)
        print(project + ': names, volume, networks, ports and proxy subnet available; LAN/VPN review still required')
    elif args.action == 'build':
        free_resources(config)
        for service in ('backend', 'frontend'):
            assert config['services'][service]['image'] not in run(DOCKER + ['image', 'ls', '--format', '{{.Repository}}:{{.Tag}}']).splitlines(), 'Image tag already exists'
        for service in ('backend', 'frontend'):
            run(compose + ['build', service], capture=False)
    elif args.action == 'start':
        free_resources(config)
        for service in ('backend', 'frontend'):
            run(DOCKER + ['image', 'inspect', config['services'][service]['image']])
        run(compose + ['up', '-d', '--no-build', '--wait', '--wait-timeout', '600'], capture=False)
    elif args.action == 'inspect':
        owned_resources(config)
        # No environment, healthcheck output, HTTP bodies or credentials are printed.
        ids = run(DOCKER + ['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).split()
        for x in json.loads(run(DOCKER + ['inspect'] + ids)) if ids else []:
            print(json.dumps({'name': x['Name'], 'id': x['Id'], 'state': x['State']['Status'],
                'health': x['State'].get('Health', {}).get('Status'), 'ports': x['NetworkSettings']['Ports'],
                'networks': {n: v['IPAddress'] for n, v in x['NetworkSettings']['Networks'].items()}}))
    elif args.action == 'seed-local':
        seed_local(config)
    elif args.action == 'clean':
        owned_resources(config)
        run(compose + ['down', '--volumes'], capture=False)
except (AssertionError, RuntimeError, KeyError, ValueError, OSError) as error:
    print('STOP: ' + (str(error) if isinstance(error, (AssertionError, RuntimeError)) else type(error).__name__), file=sys.stderr)
    sys.exit(1)
