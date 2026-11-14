#!/usr/bin/env python3
"""RNF-020: entorno exclusivo, navegador real y reporte. No ejecuta Git ni k6."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/performance'))
import run as infra
from seed import HASH,PASSWORD,assets

PROJECT='rnf020-'+uuid.uuid4().hex[:12]
OUT=ROOT/'artifacts/performance'/PROJECT
# Reutilizar helpers de aislamiento sin ejecutar ni modificar el runner k6.
infra.PROJECT=PROJECT;infra.OUT=OUT
infra.ENV.update(PERF_ASSETS_DIR=str(OUT/'assets'),PERF_RESULTS_DIR=str(OUT/'results'))
BASE=['docker','compose','--env-file','/dev/null','-f',str(ROOT/'compose.performance.yaml'),'-p',PROJECT]
infra.COMPOSE=BASE+['-f',str(ROOT/'compose.rnf020.yaml')]
COMPOSE=infra.COMPOSE
cmd=infra.cmd

def now():return dt.datetime.now(dt.timezone.utc).isoformat()
def save(name,data):(OUT/name).write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')
def fixture():
    statements=['START TRANSACTION;']
    for i,name,role in [(1,'buyer','COMPRADOR'),(2,'seller','VENDEDOR'),(3,'other','COMPRADOR')]:
        statements += [f"INSERT INTO user_accounts(id,email,password_hash,status,email_verified_at) VALUES({i},'rnf020-{name}@example.test','{HASH}','ACTIVA',CURRENT_TIMESTAMP(6));",
                       f"INSERT INTO user_account_roles VALUES({i},'{role}');",
                       f"INSERT INTO account_profiles VALUES({i},'Fixture RNF020 {i}','2000-01-01','3000000000');",
                       f"INSERT INTO account_profile_settings(account_id) VALUES({i});"]
    statements += ["UPDATE stores SET owner_account_id=2,name='Tienda RNF020',status='ACTIVE' WHERE id=1;",
                   "INSERT INTO products(id,name,description,price,stock,category,active,store_id,status) VALUES(1,'Producto RNF020','Fixture de mensajería',15000,100,'Pruebas',TRUE,1,'ACTIVE');",
                   "INSERT INTO product_images VALUES(1,0,'/performance/small.png');",'COMMIT;']
    return '\n'.join(statements)

def backing():
    rows=infra.mysql('SELECT id,conversation_id,sender_id,text FROM conversation_messages ORDER BY id;')
    messages=[dict(zip(['message_id','conversation_id','sender_id','text'],[int(v) if i<3 else v for i,v in enumerate(line.split('\t'))])) for line in rows.splitlines() if line]
    rows=infra.mysql("SELECT id,recipient_type,recipient_id,event_key FROM notifications WHERE type='CONVERSATION_MESSAGE' ORDER BY id;")
    notices=[dict(zip(['notification_id','recipient_type','recipient_id','event_key'],[int(v) if i in (0,2) else v for i,v in enumerate(line.split('\t'))])) for line in rows.splitlines() if line]
    save('backing.json',{'messages':messages,'notifications':notices})

def final_report():
    response=cmd(['node',str(ROOT/'scripts/rnf020/finalize.cjs'),str(OUT)])
    return json.loads(response.stdout)

def browser(stage,count):
    args=COMPOSE+['--profile','e2e','run','--rm','--no-deps','-e','BASE_URL=https://frontend','-e',f'EVENTS={count}',
                  '-e','STAGE='+stage,'-e','SEND_MODE='+SEND_MODE,'-e','FIXTURE_PASSWORD='+PASSWORD,'browser','/scripts/measure.cjs']
    start=time.monotonic();samples=[]
    with tempfile.TemporaryFile(mode='w+') as log:
        p=subprocess.Popen(args,cwd=ROOT,env=infra.ENV,stdout=log,stderr=subprocess.STDOUT,text=True)
        try:
            while p.poll() is None:
                sample=infra.capacity();sample['elapsed_seconds']=time.monotonic()-start;sample['utc']=now();samples.append(sample)
                if not infra.safe_capacity(sample) or time.monotonic()-start>count*25+180:raise RuntimeError('RESOURCE_PRESSURE_OR_TIMEOUT')
                time.sleep(4)
            return p.returncode
        finally:
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=25)
                except subprocess.TimeoutExpired:p.kill();p.wait(timeout=10)
            log.seek(0);text=log.read().replace(PASSWORD,'[REDACTED]').replace(HASH,'[REDACTED]')
            (OUT/(stage+'.log')).write_text(text);save(stage+'-resources.json',samples)
            print(stage+': proceso terminado; consultar '+str(OUT/(stage+'.log')),flush=True)


def cleanup():
    clean=cmd(COMPOSE+['--profile','e2e','down','--volumes','--remove-orphans','--rmi','local'],timeout=180,check=False)
    remaining={k:infra.ids(k) for k in ('container','network','volume')}
    return {'exit_code':clean.returncode,'remaining':remaining,'passed':clean.returncode==0 and not any(remaining.values())}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--smoke-only',action='store_true');parser.add_argument('--events',type=int,default=100)
    parser.add_argument('--simultaneous',action='store_true',help='reproducir envío bidireccional simultáneo; el smoke bloquea la medición si falla')
    args=parser.parse_args()
    global SEND_MODE
    SEND_MODE='simultaneous' if args.simultaneous else 'alternating'
    if args.events<100 or args.events>1000:parser.error('La medición principal exige entre 100 y 1000 eventos por dirección')
    OUT.mkdir(parents=True);(OUT/'results').mkdir(mode=0o777);os.chmod(OUT/'results',0o777);assets(OUT/'assets')
    metadata={'project':PROJECT,'started_utc':now(),'expected_per_direction':args.events,'complete':False,'problems':[],
              'send_mode':SEND_MODE,'known_concurrent_issue':'En el smoke simultáneo rnf020-0806b5c9a17d se detectaron mensajes persistidos omitidos por el cursor de recepción. El resultado con turnos alternos no acredita envío simultáneo; requiere corrección funcional fuera de este alcance.',
              'revision':'local-no-verificada-sin-git','measurement':'acción real del emisor a elemento visible en panel del destinatario',
              'polling_ms':{'messages':3000,'notifications':2000},'resource_model':'backend 1.5CPU/1GiB, MySQL 1CPU/1GiB tmpfs, nginx .25CPU/128MiB, Chromium 1.5CPU/1GiB; shm 256MiB',
              'push':'No evaluado','smoke_only':args.smoke_only,'browser_origin':'https://frontend con TLS local y certificado autofirmado efímero; ignoreHTTPSErrors en contextos aislados sin salida de red externa'}
    tls=tempfile.TemporaryDirectory(prefix=PROJECT+'-tls-')
    infra.ENV['RNF020_TLS_DIR']=tls.name
    owned=False;code=3
    try:
        context=json.loads(cmd(['docker','context','inspect']).stdout)[0]
        if not context['Endpoints']['docker']['Host'].startswith('unix://'):raise RuntimeError('REMOTE_DOCKER_FORBIDDEN')
        cap=infra.capacity();save('capacity-before.json',cap)
        if not infra.safe_capacity(cap,True):raise RuntimeError('INSUFFICIENT_RESOURCES')
        if any(infra.ids(k) for k in ('container','network','volume')):raise RuntimeError('PROJECT_ALREADY_EXISTS')
        cmd(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=frontend',
             '-addext','subjectAltName=DNS:frontend','-keyout',tls.name+'/key.pem','-out',tls.name+'/cert.pem'])
        metadata['tls_certificate_sha256']=hashlib.sha256(Path(tls.name+'/cert.pem').read_bytes()).hexdigest()
        Path(tls.name+'/frontend.conf').write_text('server { listen 443 ssl; server_name frontend; ssl_certificate /etc/nginx/rnf020tls/cert.pem; ssl_certificate_key /etc/nginx/rnf020tls/key.pem; location / { proxy_pass http://127.0.0.1:80; proxy_http_version 1.1; proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto https; } }')
        base=json.loads(cmd(BASE+['--profile','load','config','--format','json']).stdout);infra.isolation(base)
        config=json.loads(cmd(COMPOSE+['--profile','e2e','config','--format','json']).stdout)
        b=config['services'].pop('browser')
        if config['services']['backend']['environment'].get('APP_CORS_ALLOWED_ORIGINS')!='https://frontend':raise RuntimeError('CORS_ORIGIN_INVALID')
        frontend=config['services']['frontend']
        extra=[v for v in frontend['volumes'] if v['target'].startswith('/etc/nginx/')]
        if {(v['source'],v['target'],v.get('read_only',False)) for v in extra}!={(tls.name+'/frontend.conf','/etc/nginx/conf.d/rnf020-tls.conf',True),(tls.name,'/etc/nginx/rnf020tls',True)}:raise RuntimeError('TLS_MOUNTS_INVALID')
        frontend['volumes']=[v for v in frontend['volumes'] if v not in extra]
        infra.isolation(config)
        if b.get('ports') or b.get('privileged') or b.get('network_mode') or b.get('networks')!={'target':None} or not b.get('cpus') or not b.get('mem_limit'):raise RuntimeError('BROWSER_ISOLATION_INVALID')
        if {(v['source'],v['target'],v.get('read_only',False)) for v in b['volumes']}!={(str(ROOT/'scripts/rnf020'),'/scripts',True),(str(OUT/'results'),'/results',False)}:raise RuntimeError('BROWSER_MOUNTS_INVALID')
        metadata['docker_version']=cmd(['docker','version','--format','{{.Server.Version}}']).stdout.strip()
        metadata['compose_version']=cmd(['docker','compose','version','--short']).stdout.strip()
        sources=[ROOT/'compose.performance.yaml',ROOT/'compose.rnf020.yaml']+[f for base in ['scripts/rnf020','scripts/performance','backend/demo/src','frontend/src'] for f in (ROOT/base).rglob('*') if f.is_file() and '__pycache__' not in str(f) and 'node_modules' not in str(f)]
        sources += [ROOT/'frontend/package-lock.json',ROOT/'backend/demo/pom.xml',ROOT/'frontend/Dockerfile',ROOT/'backend/demo/Dockerfile']
        save('source-sha256.json',{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sources})
        owned=True;print('Construyendo '+PROJECT,flush=True)
        cmd(COMPOSE+['--profile','e2e','build','backend','frontend','browser'],timeout=1800)
        cmd(COMPOSE+['up','-d','--wait','--wait-timeout','300','mysql','backend','frontend'],timeout=360);infra.verify_runtime()
        versions=[int(v) for v in infra.mysql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank;').splitlines()]
        expected=sorted(int(p.name.split('__')[0][1:]) for p in (ROOT/'backend/demo/src/main/resources/db/migration').glob('V*__*.sql'))
        if versions!=expected or infra.mysql('SELECT COUNT(*) FROM user_accounts;')!='0' or infra.mysql('SELECT COUNT(*) FROM products;')!='0':raise RuntimeError('FRESH_DATABASE_OR_FLYWAY_INVALID')
        infra.mysql(fixture())
        counts=infra.mysql('SELECT (SELECT COUNT(*) FROM user_accounts),(SELECT COUNT(*) FROM products),(SELECT owner_account_id FROM stores WHERE id=1),(SELECT COUNT(*) FROM conversation_messages),(SELECT COUNT(*) FROM notifications);')
        if counts!='3\t1\t2\t0\t0':raise RuntimeError('FIXTURE_INVALID')
        metadata['fixtures']={'verified':True,'accounts':3,'buyers':2,'sellers':1,'products':1,'store_owner':2,'flyway_versions':versions};save('metadata.json',metadata)
        # Tests del instrumento usan DOM sintético; no son evidencia principal.
        test=cmd(COMPOSE+['--profile','e2e','run','--rm','--no-deps','browser','--test','/scripts/tests/instrument.test.cjs'],timeout=120,check=False)
        (OUT/'instrument-tests.log').write_text(test.stdout+test.stderr)
        if test.returncode:raise RuntimeError('INSTRUMENT_TESTS_FAILED')
        print('Smoke: 5 eventos por dirección',flush=True)
        metadata['smoke_exit_code']=browser('smoke',5);backing();save('metadata.json',metadata)
        if metadata['smoke_exit_code'] or not final_report()['smoke_ok']:raise RuntimeError('SMOKE_INVALID_FULL_RUN_BLOCKED')
        if not args.smoke_only:
            print(f'Medición real: {args.events} eventos por dirección',flush=True)
            metadata['browser_exit_code']=browser('measurement',args.events);backing()
        code=0
    except (Exception,KeyboardInterrupt) as e:
        metadata['problems'].append(str(e).replace(str(ROOT),'[WORKSPACE]') if isinstance(e,RuntimeError) else type(e).__name__);print('Ejecución detenida: '+metadata['problems'][-1],flush=True)
    finally:
        if owned:
            if metadata.get('fixtures',{}).get('verified'):
                try:backing()
                except Exception:metadata['problems'].append('FINAL_PERSISTENCE_BACKUP_UNAVAILABLE')
            try:
                diagnostic=cmd(COMPOSE+['logs','--no-color','--tail','120','backend','mysql'],check=False).stdout
                for secret in (PASSWORD,HASH,'performance_db_only','performance_root_only'):diagnostic=diagnostic.replace(secret,'[REDACTED]')
                import re
                diagnostic=re.sub(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}','[CUENTA]',diagnostic)
                (OUT/'environment.log').write_text(diagnostic)
            except Exception:metadata['problems'].append('DIAGNOSTICS_UNAVAILABLE')
            try:
                metadata['cleanup']=cleanup()
            except Exception:
                metadata['cleanup']={'passed':False};metadata['problems'].append('CLEANUP_NOT_VERIFIED');code=3
        tls.cleanup()
        metadata['finished_utc']=now();metadata['duration_seconds']=(dt.datetime.fromisoformat(metadata['finished_utc'])-dt.datetime.fromisoformat(metadata['started_utc'])).total_seconds();save('metadata.json',metadata)
        try:
            result=final_report()
            if not args.smoke_only and result['state']!='Cumple':code=1 if result['state']=='No cumple' else 3
            if not metadata.get('cleanup',{}).get('passed'):code=3
        except Exception:code=3
        metadata['exit_code']=code;save('metadata.json',metadata)
        try:final_report()
        except Exception:print('No se pudo generar el informe final',flush=True)
        print('Evidencias: '+str(OUT),flush=True)
    return code

if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    raise SystemExit(main())
