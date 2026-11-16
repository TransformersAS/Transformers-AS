#!/usr/bin/env python3
"""Una demo propia, una base nueva y un Chrome visible. No usa Git ni RNF runners."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import threading
import uuid

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = 'Cu08RealTestPassword!'


def safe(text):
    text = text.replace(str(ROOT), '[REPO]').replace(PASSWORD, '[REDACTED]')
    text = re.sub(r'\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', '[HASH REDACTED]', text)
    text = re.sub(r'(?i)(cookie|authorization|token|password)[=:][^\s,;]+', r'\1=[REDACTED]', text)
    return re.sub(r'(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])', '[CODE REDACTED]', text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cu', choices=['cu07', 'cu08', 'cu09', 'cu10', 'cu11'])
    parser.add_argument('--headless', action='store_true', help='Diagnóstico; no valida apertura visual')
    parser.add_argument('--slow-ms', type=int)
    parser.add_argument('--extras', action='store_true', help='CU07/CU08/CU09/CU10/CU11: escenarios complementarios')
    parser.add_argument('--scenario', choices=['validation','existing','verification','smtp','backend','password','recovery','access','restrictions','expiration','sessions','mail','email','preferences','blocked','closure','failure','contexts','images','states','restricted','report','external','concurrency','security','history','detail','race','consistency','refund-pending','refund-error','query'], help='CU07/CU08/CU09/CU10/CU11 extras: escenario independiente')
    parser.add_argument('--regression', action='store_true', help='Sólo CU09: prueba frontend real ya existente')
    parser.add_argument('--cu11-group-part',choices=['base','pending','error'],help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.cu11_group_part and (args.cu!='cu11' or not args.extras or args.scenario or args.regression):
        parser.error('--cu11-group-part sólo corresponde a CU11 extras sin scenario')
    started = time.monotonic()
    if args.regression and (args.cu != 'cu09' or args.extras or args.scenario):
        parser.error('--regression requiere CU09 sin extras')
    if args.scenario and (args.cu not in ['cu07','cu08','cu09','cu10','cu11'] or not args.extras):
        parser.error('--scenario requiere CU07/CU08/CU09/CU10/CU11 --extras')
    if args.extras and args.cu not in ['cu07','cu08','cu09','cu10','cu11']:
        parser.error('--extras sólo corresponde a CU07/CU08/CU09/CU10/CU11')
    allowed_scenarios={'cu07':{'validation','existing','verification','smtp','backend'},'cu08':{'password','recovery','access','restrictions','expiration','sessions','mail'},'cu09':{'validation','email','preferences','blocked','closure','failure'},'cu10':{'contexts','images','states','restricted','report','external','failure','security','concurrency'},'cu11':{'history','detail','states','race','consistency','refund-pending','refund-error','external','query'}}
    if args.scenario and args.scenario not in allowed_scenarios.get(args.cu,set()):
        parser.error('Escenario no disponible para el CU elegido')
    if args.slow_ms is None:
        args.slow_ms = int(os.environ.get('CU07_DEMO_SLOW_MS', '900')) if args.cu == 'cu07' else int(os.environ.get('CU08_DEMO_SLOW_MS', '900')) if args.cu == 'cu08' else int(os.environ.get('CU09_DEMO_SLOW_MS','900')) if args.cu == 'cu09' else int(os.environ.get('CU10_DEMO_SLOW_MS','900')) if args.cu == 'cu10' else int(os.environ.get('CU11_DEMO_SLOW_MS','900'))
    pause_ms = int(os.environ.get('CU07_DEMO_PAUSE_MS', '2500')) if args.cu == 'cu07' else 1200
    if args.cu == 'cu08':
        pause_ms=int(os.environ.get('CU08_DEMO_PAUSE_MS','2500'))
    if args.cu == 'cu09':
        pause_ms=int(os.environ.get('CU09_DEMO_PAUSE_MS','2500'))
    if args.cu == 'cu10':
        pause_ms=int(os.environ.get('CU10_DEMO_PAUSE_MS','2500'))
    if args.cu == 'cu11':
        pause_ms=int(os.environ.get('CU11_DEMO_PAUSE_MS','2500'))
    if not 0 <= pause_ms <= 10000:
        if args.cu == 'cu11':
            parser.error('CU11_DEMO_PAUSE_MS debe estar entre 0 y 10000')
        if args.cu == 'cu10':
            parser.error('CU10_DEMO_PAUSE_MS debe estar entre 0 y 10000')
        if args.cu == 'cu09':
            parser.error('CU09_DEMO_PAUSE_MS debe estar entre 0 y 10000')
        parser.error('CU07_DEMO_PAUSE_MS debe estar entre 0 y 10000' if args.cu != 'cu08' else 'CU08_DEMO_PAUSE_MS debe estar entre 0 y 10000')
    def interrupted(signum, _frame):
        raise RuntimeError('Demo interrumpida por señal ' + str(signum))
    for sig in [signal.SIGINT, signal.SIGTERM]:
        signal.signal(sig, interrupted)
    if not 0 <= args.slow_ms <= 2000:
        parser.error('--slow-ms debe estar entre 0 y 2000')
    if args.cu=='cu11' and args.extras and not args.scenario and not args.cu11_group_part:
        command=[sys.executable,str(ROOT/'scripts/demos/cu11-extras.py'),'--slow-ms',str(args.slow_ms)]
        if args.headless:command.append('--headless')
        return subprocess.run(command,cwd=ROOT).returncode
    # Exclusión entre nuestras demos; nunca detiene procesos de otros usuarios.
    lock_name = hashlib.sha256(str(ROOT).encode()).hexdigest()[:16]
    lock = open(Path(tempfile.gettempdir()) / ('marketplace-sustentacion-' + lock_name + '.lock'), 'a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('Ya hay una demo propia en ejecución; esperar a que termine')
    run = 'sust-' + args.cu + '-' + uuid.uuid4().hex[:12]
    out = ROOT / 'artifacts' / 'sustentacion' / run
    out.mkdir(parents=True, exist_ok=False)
    env = {k: os.environ[k] for k in ['PATH', 'HOME', 'DOCKER_CONFIG', 'TMPDIR'] if k in os.environ}
    env.update({'COMPOSE_ANSI': 'never', 'COMPOSE_MENU': 'false'})
    compose = ['docker', 'compose', '--env-file', '/dev/null', '--project-directory', str(ROOT), '-f', str(ROOT / 'compose.sustentacion.yaml'), '-p', run]
    if args.cu == 'cu08':
        compose[-2:-2]=['-f',str(ROOT/'compose.cu08-presentation.yaml')]
    if args.cu == 'cu10':
        compose[-2:-2]=['-f',str(ROOT/'compose.cu10-presentation.yaml')]
        env['CU10_NOTIFICATION_MODE']='UNAVAILABLE' if args.extras else 'OK'
    if args.cu == 'cu11':
        compose[-2:-2]=['-f',str(ROOT/'compose.cu11-presentation.yaml')]
        env['CU11_REFUND_MODE']='PENDING' if args.scenario=='refund-pending' or args.cu11_group_part=='pending' else 'UNAVAILABLE' if args.scenario=='refund-error' or args.cu11_group_part=='error' else 'OK'
        env['CU11_NOTIFICATION_MODE']='UNAVAILABLE' if args.extras else 'OK'
    owned = False
    code = 3

    def save(name, data):
        (out / name).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')

    def invoke(command, *, data=None, timeout=180, check=True):
        r = subprocess.run(command, input=data, cwd=ROOT, env=env, text=True, capture_output=True, timeout=timeout)
        if check and r.returncode:
            raise RuntimeError('Falló ' + command[0] + ': ' + safe(r.stderr[-1200:]))
        return r

    def resources(kind):
        command = {'containers': ['docker', 'ps', '-aq'], 'networks': ['docker', 'network', 'ls', '-q'], 'volumes': ['docker', 'volume', 'ls', '-q']}[kind]
        return invoke(command + ['--filter', 'label=com.docker.compose.project=' + run]).stdout.split()

    def sql(query):
        command = compose + ['exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
        if invoke(command, data='SELECT DATABASE();').stdout.strip() != 'sustentacion':
            raise RuntimeError('Base de datos ajena')
        return invoke(command, data=query).stdout.strip()

    try:
        if os.environ.get('DOCKER_HOST'):
            raise RuntimeError('No se permite DOCKER_HOST; usar Docker local')
        context = json.loads(invoke(['docker', 'context', 'inspect']).stdout)[0]
        if not context['Endpoints']['docker']['Host'].startswith('unix://'):
            raise RuntimeError('Se requiere un socket Docker local')
        if not (ROOT / 'frontend/node_modules/@playwright/test/cli.js').exists():
            raise RuntimeError('Primero ejecutar npm ci en frontend e instalar Chrome con Playwright')
        cfg = json.loads(invoke(compose + ['config', '--format', 'json']).stdout)
        if set(cfg['services']) != {'mysql', 'mailpit', 'backend', 'frontend'} or set(cfg['networks']) != {'database', 'target', 'edge'} or not all(cfg['networks'][n].get('internal') for n in ['database', 'target']):
            raise RuntimeError('Topología inesperada')
        for name, service in cfg['services'].items():
            if service.get('container_name') or service.get('volumes'):
                raise RuntimeError('No se permiten nombres fijos ni volúmenes persistentes')
            for port in service.get('ports', []):
                if name not in ['frontend', 'mailpit'] or port.get('host_ip') != '127.0.0.1' or port.get('published') not in ['', '0', None]:
                    raise RuntimeError('Puerto no exclusivo')
        if any(resources(k) for k in ['containers', 'networks', 'volumes']):
            raise RuntimeError('El proyecto aleatorio ya tiene recursos')
        owned = True
        print(f'{args.cu.upper()}: construyendo frontend/backend reales y base nueva. Evidencia: {out.relative_to(ROOT)}', flush=True)
        r = invoke(compose + ['build', 'frontend', 'backend'], timeout=1500, check=False)
        (out / 'build.log').write_text(safe(r.stdout + r.stderr))
        if r.returncode:
            raise RuntimeError('Falló construcción; ver build.log')
        r = invoke(compose + ['up', '-d', '--wait', '--wait-timeout', '300'], timeout=360, check=False)
        (out / 'startup.log').write_text(safe(r.stdout + r.stderr))
        if r.returncode:
            raise RuntimeError('El entorno no alcanzó readiness; ver startup.log')
        for network in invoke(['docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + run]).stdout.split():
            details = json.loads(invoke(['docker', 'network', 'inspect', network]).stdout)[0]
            if details['Labels'].get('com.docker.compose.network') != 'edge' and not details['Internal']:
                raise RuntimeError('Red no interna')
        expected = sorted(int(f.name.split('__')[0][1:]) for f in (ROOT / 'backend/demo/src/main/resources/db/migration').glob('V*__*.sql'))
        observed = [int(x) for x in sql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank;').splitlines()]
        if expected != observed or sql('SELECT COUNT(*) FROM user_accounts;') != '0':
            raise RuntimeError('La base no es nueva o Flyway no coincide')
        fixture = json.loads(sql((ROOT / ('scripts/demos/cu07-seed.sql' if args.cu == 'cu07' else 'scripts/demos/cu08-seed.sql' if args.cu == 'cu08' else 'scripts/demos/cu09-seed.sql' if args.cu == 'cu09' else 'scripts/demos/cu10-seed.sql' if args.cu == 'cu10' else 'scripts/demos/cu11-seed.sql')).read_text()))
        if args.cu == 'cu09':
            save('cu09-fixture.json',fixture)
        if args.cu == 'cu10':
            save('cu10-fixture.json',fixture)
        if args.cu=='cu11':
            save('cu11-fixture.json',fixture)
        base = 'http://' + invoke(compose + ['port', 'frontend', '80']).stdout.strip()
        mail = 'http://' + invoke(compose + ['port', 'mailpit', '8025']).stdout.strip()
        if not all(re.fullmatch(r'http://127\.0\.0\.1:[1-9][0-9]*', url) for url in [base, mail]):
            raise RuntimeError('Docker no publicó puertos loopback válidos')
        save('isolation.json', {'project': run, 'backend_database_internal': True, 'edge_loopback_only': True, 'database_new': True, 'flyway_versions': observed, 'frontend_loopback': base, 'mailpit_loopback': mail})
        test_env = dict(os.environ, CI='1', SUST_RUN=run, SUST_CU=args.cu, SUST_OUTPUT=str(out / 'screens'), SUST_REPORT_DIR=str(out), SUST_BASE_URL=base, SUST_MAIL_URL=mail, SUST_FIXTURE=json.dumps(fixture), SUST_PASSWORD=PASSWORD, SUST_HEADLESS='1' if args.headless else '0', SUST_SLOW_MS=str(args.slow_ms))
        if args.cu == 'cu07':
            test_env.update(SUST_CU07_EXTRAS='1' if args.extras else '0', CU07_DEMO_PAUSE_MS=str(pause_ms))
        if args.cu == 'cu08':
            test_env.update(SUST_CU08_EXTRAS='1' if args.extras else '0', CU08_DEMO_PAUSE_MS=str(pause_ms))
        if args.cu == 'cu09':
            test_env.update(SUST_CU09_EXTRAS='1' if args.extras else '0', SUST_CU09_REGRESSION='1' if args.regression else '0', CU09_DEMO_PAUSE_MS=str(pause_ms), CU09_MAIL_URL=mail)
        if args.cu == 'cu10':
            test_env.update(SUST_CU10_EXTRAS='1' if args.extras else '0',CU10_DEMO_PAUSE_MS=str(pause_ms))
        if args.cu=='cu11':
            test_env.update(SUST_CU11_EXTRAS='1' if args.extras else '0',CU11_DEMO_PAUSE_MS=str(pause_ms),CU11_REFUND_MODE=env['CU11_REFUND_MODE'])
        print(args.cu.upper() + (': Chrome visible, acciones automáticas.' if not args.headless else ': modo headless diagnóstico.'), flush=True)
        browser_started = time.monotonic()
        config = 'playwright.cu07-presentation.config.ts' if args.cu == 'cu07' else 'playwright.cu08-presentation.config.ts' if args.cu == 'cu08' else 'playwright.cu09-presentation.config.ts' if args.cu == 'cu09' else 'playwright.cu10-presentation.config.ts' if args.cu == 'cu10' else 'playwright.cu11-presentation.config.ts'
        command = ['node', 'node_modules/@playwright/test/cli.js', 'test', '--config=' + config]
        if args.cu in ['cu07','cu08','cu09','cu10','cu11']:
            if args.scenario:
                patterns={'validation':'CU07 A2 A3 A4:', 'existing':'CU07 A1 A8:', 'verification':'CU07 A5 A6 A7:', 'smtp':'CU07 A6 A8:', 'backend':'CU07 A8:'}
                if args.cu == 'cu08':
                    patterns={name:'CU08 '+name+':' for name in allowed_scenarios['cu08']}
                if args.cu == 'cu09':
                    patterns={name:'CU09 '+name+':' for name in allowed_scenarios['cu09']}
                if args.cu == 'cu10':
                    patterns={name:'CU10 '+name+':' for name in allowed_scenarios['cu10']}
                if args.cu=='cu11':
                    patterns={name:'CU11 '+name+':' for name in allowed_scenarios['cu11']}
                command += ['--grep',patterns[args.scenario]]
            if args.cu11_group_part:
                pattern='CU11 (history|detail|states|race|consistency|external|query):' if args.cu11_group_part=='base' else 'CU11 refund-'+('pending' if args.cu11_group_part=='pending' else 'error')+':'
                command += ['--grep',pattern]
            lines = []
            with subprocess.Popen(command, cwd=ROOT / 'frontend', env=test_env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1) as child:
                def collect_steps():
                    with (out / 'playwright.log').open('w') as log:
                        for line in child.stdout:
                            line = safe(line)
                            lines.append(line)
                            log.write(line)
                            log.flush()
                            print(line, end='', flush=True)
                reader = threading.Thread(target=collect_steps)
                reader.start()
                try:
                    child.wait(timeout=(2400 if args.cu in ['cu08','cu09','cu10','cu11'] else 1500) if args.extras else 420)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                    raise
                finally:
                    reader.join(timeout=15)
                r = subprocess.CompletedProcess(command, child.returncode, ''.join(lines), '')
        else:
            r = subprocess.run(command, cwd=ROOT / 'frontend', env=test_env, text=True, capture_output=True, timeout=240)
            (out / 'playwright.log').write_text(safe(r.stdout + r.stderr))
            print(safe(r.stdout), flush=True)
        code = r.returncode
        if args.cu == 'cu07':
            if args.extras:
                witness = json.loads(sql("SELECT JSON_OBJECT('accounts',COUNT(*),'verifiedAccounts',SUM(email_verified_at IS NOT NULL),'buyerRoles',(SELECT COUNT(*) FROM user_account_roles WHERE role='COMPRADOR'),'existingRoles',(SELECT COUNT(*) FROM user_account_roles r JOIN user_accounts a ON a.id=r.account_id WHERE a.email='cu07-existing@example.test'),'consents',(SELECT COUNT(*) FROM buyer_consents)) FROM user_accounts;"))
                expected = {'accounts':4,'verifiedAccounts':4,'buyerRoles':4,'existingRoles':2,'consents':4}
                if args.scenario:
                    if args.scenario == 'validation':
                        expected = {'accounts':1,'verifiedAccounts':1,'buyerRoles':0,'existingRoles':1,'consents':0}
                    elif args.scenario == 'existing':
                        expected = {'accounts':1,'verifiedAccounts':1,'buyerRoles':1,'existingRoles':2,'consents':1}
                    else:
                        expected = {'accounts':2,'verifiedAccounts':2,'buyerRoles':1,'existingRoles':1,'consents':1}
                witness['passed'] = witness == expected
            else:
                witness = json.loads(sql("SELECT JSON_OBJECT('verifiedBuyer',COUNT(*)=1) FROM user_accounts a JOIN user_account_roles r ON r.account_id=a.id WHERE a.email='registration@example.test' AND a.email_verified_at IS NOT NULL AND r.role='COMPRADOR';"))
                witness['passed'] = witness.get('verifiedBuyer') == 1
            save('database-witness.json', witness)
            if not witness['passed']:
                code = code or 1
            save('cu07-presentation.json', {'mode':'extras' if args.extras else 'main','scenario':args.scenario or 'all','slow_ms':args.slow_ms,'pause_ms':pause_ms,'viewport':{'width':1440,'height':1000},'browser_process_seconds':round(time.monotonic()-browser_started,3)})
        elif args.cu == 'cu08':
            witness=json.loads(sql("SELECT JSON_OBJECT('accounts',COUNT(*),'roles',(SELECT COUNT(*) FROM user_account_roles),'restrictions',(SELECT COUNT(*) FROM account_restrictions)) FROM user_accounts;"))
            witness['passed']=witness == {'accounts':13,'roles':16,'restrictions':2}
            save('database-witness.json',witness)
            if not witness['passed']:code=code or 1
            save('cu08-presentation.json',{'mode':'extras' if args.extras else 'main','scenario':args.scenario or 'all','slow_ms':args.slow_ms,'pause_ms':pause_ms,'viewport':{'width':1440,'height':1000},'browser_process_seconds':round(time.monotonic()-browser_started,3)})
        elif args.cu == 'cu09':
            witness=json.loads(sql("SELECT JSON_OBJECT('accounts',COUNT(*),'profiles',(SELECT COUNT(*) FROM account_profiles),'orders',(SELECT COUNT(*) FROM orders),'claims',(SELECT COUNT(*) FROM claims),'returns',(SELECT COUNT(*) FROM return_requests),'refunds',(SELECT COUNT(*) FROM refunds)) FROM user_accounts;"))
            witness['passed']=witness=={'accounts':9,'profiles':9,'orders':4,'claims':2,'returns':2,'refunds':1}
            save('database-witness.json',witness)
            if not witness['passed']:code=code or 1
            save('cu09-presentation.json',{'mode':'regression' if args.regression else 'extras' if args.extras else 'main','scenario':args.scenario or 'all','slow_ms':args.slow_ms,'pause_ms':pause_ms,'viewport':{'width':1440,'height':1000},'browser_process_seconds':round(time.monotonic()-browser_started,3)})
        elif args.cu == 'cu10':
            witness=json.loads(sql("SELECT JSON_OBJECT('accounts',COUNT(*),'products',(SELECT COUNT(*) FROM products),'orders',(SELECT COUNT(*) FROM orders),'messages',(SELECT COUNT(*) FROM conversation_messages),'notifications',(SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION')) FROM user_accounts;"))
            witness['passed']=witness['accounts']==22 and witness['products']==20 and witness['orders']==1 and witness['messages']==witness['notifications']
            save('database-witness.json',witness)
            if not witness['passed']:code=code or 1
            save('cu10-presentation.json',{'mode':'extras' if args.extras else 'main','scenario':args.scenario or 'all','slow_ms':args.slow_ms,'pause_ms':pause_ms,'viewport':{'width':1440,'height':1000},'external_provider':'existing simulated adapter','external_mode':env['CU10_NOTIFICATION_MODE'],'browser_process_seconds':round(time.monotonic()-browser_started,3)})
        elif args.cu=='cu11':
            witness=json.loads(sql("SELECT JSON_OBJECT('accounts',COUNT(*),'orders',(SELECT COUNT(*) FROM orders),'products',(SELECT COUNT(*) FROM products),'cancellations',(SELECT COUNT(*) FROM order_cancellations),'refunds',(SELECT COUNT(*) FROM refunds)) FROM user_accounts;"))
            witness['passed']=witness['accounts']==22 and witness['orders']==fixture['expectedOrders'] and witness['products']==20
            save('database-witness.json',witness)
            if not witness['passed']:code=code or 1
            save('cu11-presentation.json',{'mode':'extras' if args.extras else 'main','scenario':args.scenario or args.cu11_group_part or 'all','slow_ms':args.slow_ms,'pause_ms':pause_ms,'viewport':{'width':1440,'height':1000},'refund_mode':env['CU11_REFUND_MODE'],'notification_mode':env['CU11_NOTIFICATION_MODE'],'providers':'existing simulated adapters; no banking settlement','browser_process_seconds':round(time.monotonic()-browser_started,3)})
        else:
            queries = {
                'cu07': "SELECT JSON_OBJECT('verifiedBuyer',COUNT(*)=1) FROM user_accounts a JOIN user_account_roles r ON r.account_id=a.id WHERE a.email='registration@example.test' AND a.email_verified_at IS NOT NULL AND r.role='COMPRADOR';",
                'cu08': f"SELECT JSON_OBJECT('rolesPreserved',COUNT(*)=2) FROM user_account_roles WHERE account_id={fixture['rolesId']};",
                'cu09': f"SELECT JSON_OBJECT('profilePersisted',p.full_name='Persona Actualizada','preferencesPersisted',s.promotions=TRUE) FROM account_profiles p JOIN account_profile_settings s ON p.account_id=s.account_id WHERE p.account_id={fixture['buyerId']};",
                'cu10': "SELECT JSON_OBJECT('productConversation',SUM(order_id IS NULL)>0,'orderConversation',SUM(order_id IS NOT NULL)>0,'messages',(SELECT COUNT(*) FROM conversation_messages),'notifications',(SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION')) FROM conversations;",
                'cu11': f"SELECT JSON_OBJECT('status',o.status,'cancellations',(SELECT COUNT(*) FROM order_cancellations WHERE order_id=o.id),'refundStatus',(SELECT status FROM refunds WHERE order_id=o.id)) FROM orders o WHERE o.id={fixture['orderId']};",
            }
            witness = json.loads(sql(queries[args.cu]))
            expected_witness = {
                'cu07': lambda w: w.get('verifiedBuyer') == 1,
                'cu08': lambda w: w.get('rolesPreserved') == 1,
                'cu09': lambda w: w.get('profilePersisted') == 1 and w.get('preferencesPersisted') == 1,
                'cu10': lambda w: w.get('productConversation') == 1 and w.get('orderConversation') == 1 and w.get('messages', 0) >= 3 and w.get('notifications', 0) >= 3,
                'cu11': lambda w: w.get('status') == 'CANCELLED' and w.get('cancellations') == 1 and w.get('refundStatus') == 'COMPLETED',
            }
            witness['passed'] = expected_witness[args.cu](witness)
            save('database-witness.json', witness)
            if not witness['passed']:
                code = code or 1
        save('run.json', {'cu': args.cu, 'exit_code': code, 'headed': not args.headless, 'slow_ms': args.slow_ms, 'real_frontend_backend_mysql': True, 'http_mocking': False, 'run': run})
    except Exception as error:
        save('error.json', {'type': type(error).__name__, 'message': safe(str(error))})
        print('Fallo de demo: ' + safe(str(error)), flush=True)
        code = 3
    finally:
        if owned:
            r = invoke(compose + ['down', '--volumes', '--remove-orphans'], timeout=180, check=False)
            remaining = {k: resources(k) for k in ['containers', 'networks', 'volumes']}
            passed = r.returncode == 0 and not any(remaining.values())
            save('cleanup.json', {'passed': passed, 'remaining': remaining})
            if not passed:
                code = 3
        if args.cu == 'cu07':
            save('cu07-total.json', {'exit_code':code,'total_seconds':round(time.monotonic()-started,3)})
        if args.cu == 'cu08':
            save('cu08-total.json', {'exit_code':code,'total_seconds':round(time.monotonic()-started,3)})
        if args.cu == 'cu09':
            save('cu09-total.json', {'exit_code':code,'total_seconds':round(time.monotonic()-started,3)})
        if args.cu == 'cu10':
            save('cu10-total.json',{'exit_code':code,'total_seconds':round(time.monotonic()-started,3)})
        if args.cu=='cu11':
            save('cu11-total.json',{'exit_code':code,'total_seconds':round(time.monotonic()-started,3)})
        print(f'Resultado {args.cu.upper()}: exit {code}. Evidencia: {out.relative_to(ROOT)}', flush=True)
    return code


if __name__ == '__main__':
    sys.exit(main())
