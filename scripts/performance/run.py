#!/usr/bin/env python3
"""Runner local reproducible. No Git; solo proyecto efímero nuevo y daemon local."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time
import uuid
from seed import assets, sql, PASSWORD, HASH
from report import assess, write_report

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'rnf-perf-' + uuid.uuid4().hex[:12]
OUT = ROOT/'artifacts/performance'/PROJECT
ENV = {k:v for k,v in os.environ.items() if k in ('PATH','HOME','TMPDIR','DOCKER_CONFIG')}
ENV.update(COMPOSE_DISABLE_ENV_FILE='1', PERF_ASSETS_DIR=str(OUT/'assets'), PERF_RESULTS_DIR=str(OUT/'results'))
COMPOSE = ['docker','compose','--env-file','/dev/null','-f',str(ROOT/'compose.performance.yaml'),'-p',PROJECT]


def cmd(args, input=None, timeout=120, check=True):
    r = subprocess.run(args,cwd=ROOT,env=ENV,input=input,text=True,capture_output=True,timeout=timeout)
    if check and r.returncode:
        detail=(r.stderr or r.stdout)[-1800:]
        for secret in (PASSWORD,HASH,'performance_db_only','performance_root_only'):
            detail=detail.replace(secret,'[REDACTED]')
        raise RuntimeError('Comando fallido '+ ' '.join(args[:3])+' exit='+str(r.returncode)+' '+detail)
    return r


def save(name, data):
    (OUT/name).write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')


def ids(kind):
    return cmd(['docker',kind,'ls','-q','--filter','label=com.docker.compose.project='+PROJECT]
               + (['--all'] if kind=='container' else [])).stdout.split()


def byte_size(text):
    number,unit = re.fullmatch(r'([\d.]+)\s*([A-Za-z]+)',text.strip()).groups()
    return float(number)*{'B':1,'kB':1000,'MB':10**6,'GB':10**9,'KiB':1024,'MiB':1024**2,'GiB':1024**3}[unit]


def capacity():
    info=json.loads(cmd(['docker','info','--format','{{json .}}']).stdout)
    rows=[json.loads(line) for line in cmd(['docker','stats','--no-stream','--format','{{json .}}'],timeout=30).stdout.splitlines()]
    used=sum(byte_size(r['MemUsage'].split('/')[0]) for r in rows)
    cpu=sum(float(r['CPUPerc'].strip('%')) for r in rows)
    return {'docker_memory_bytes':info['MemTotal'],'docker_cpus':info['NCPU'],
            'estimated_free_bytes':info['MemTotal']-used,'cpu_percent_of_one_core':cpu,
            'own_services':[{'service':r['Name'].replace(PROJECT,'test'), 'cpu_percent':r['CPUPerc'],
                             'memory':r['MemUsage']} for r in rows if r['Name'].startswith(PROJECT)]}


def safe_capacity(sample, initial=False):
    required=(3000 if initial else 700)*1024**2
    return sample['estimated_free_bytes']>=required and sample['cpu_percent_of_one_core']<sample['docker_cpus']*70


def mysql(statement):
    return cmd(COMPOSE+['exec','-T','mysql','sh','-c',
               'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE"'],
               input=statement).stdout.strip()


def isolation(config):
    if config.get('volumes') or not all(n['internal'] for n in config['networks'].values()):
        raise RuntimeError('Almacenamiento/red no desechables')
    for name,service in config['services'].items():
        if service.get('ports') or service.get('network_mode') or service.get('privileged'):
            raise RuntimeError('Puerto/red/privilegio fuera del alcance')
        if not service.get('mem_limit') or not service.get('cpus'):
            raise RuntimeError('Faltan límites de recursos')
        for volume in service.get('volumes',[]):
            if volume['type']!='bind' or volume['target'] not in ('/scripts','/results','/usr/share/nginx/html/performance'):
                raise RuntimeError('Montaje ajeno')
    if '/var/lib/mysql' not in config['services']['mysql'].get('tmpfs',[]):
        raise RuntimeError('MySQL requiere tmpfs')
    backend=config['services']['backend']['environment']
    expected={'DB_NAME':'performance_fixture','MARKETPLACE_DEMO_DATA':'false','PAYMENTS_MOCK_ENABLED':'false',
              'PAYMENTS_RECOVERY_ENABLED':'false','MERCADOPAGO_ACCESS_TOKEN':'','GEMINI_API_KEY':'',
              'SPRING_MAIL_HOST':'','NOTIFICATIONS_QUEUE_ENABLED':'false','NOTIFICATIONS_PUSH_ENABLED':'false',
              'LOGISTICS_TRACKING_POLLING_ENABLED':'false','LOGISTICS_PROVIDER':'simulated','NOTIFICATIONS_PROVIDER':'simulated'}
    if any(str(backend.get(k))!=v for k,v in expected.items()):raise RuntimeError('Proveedor/BD fuera del alcance')
    for service in config['services'].values():
        for volume in service.get('volumes',[]):
            expected_source={'/scripts':str(ROOT/'scripts/k6'),'/results':str(OUT/'results'),
                             '/usr/share/nginx/html/performance':str(OUT/'assets')}[volume['target']]
            if volume['source']!=expected_source:raise RuntimeError('Directorio ajeno al proyecto')


def verify_runtime():
    containers=json.loads(cmd(['docker','inspect',*ids('container')]).stdout)
    if len(containers)!=3:raise RuntimeError('Se requieren tres servicios reales')
    safe=[]
    for c in containers:
        if c['State'].get('Health',{}).get('Status')!='healthy' or c['HostConfig'].get('PortBindings'):
            raise RuntimeError('Readiness o puertos no válidos')
        if c['Config']['Labels']['com.docker.compose.service']=='mysql' and '/var/lib/mysql' not in c['HostConfig'].get('Tmpfs',{}):
            raise RuntimeError('MySQL no usa tmpfs')
        for network in c['NetworkSettings']['Networks'].values():
            n=json.loads(cmd(['docker','network','inspect',network['NetworkID']]).stdout)[0]
            if not n['Internal'] or n['Labels'].get('com.docker.compose.project')!=PROJECT:raise RuntimeError('Red ajena')
        safe.append({'service':c['Config']['Labels']['com.docker.compose.service'],'healthy':True,
                     'image_id':c['Image'],'memory_limit':c['HostConfig']['Memory'],
                     'nano_cpus':c['HostConfig']['NanoCpus'],'published_ports':[]})
    save('isolation.json',safe)


def reset():
    # Solo después de demostrar una BD exclusiva y sembrada por este runner.
    mysql('DELETE FROM buyer_favorites; DELETE FROM cart_items; DELETE FROM addresses WHERE id>100; '
          'UPDATE account_profile_settings SET promotions=FALSE,marketplace_news=FALSE,version=0; '
          'DELETE FROM SPRING_SESSION;')
    counts=mysql('SELECT (SELECT COUNT(*) FROM products WHERE active=1),(SELECT COUNT(*) FROM user_accounts),'
                 '(SELECT COUNT(*) FROM addresses),(SELECT COUNT(*) FROM product_reviews);')
    if counts!='1000\t100\t100\t26':raise RuntimeError('Datos de comparación no equivalentes')


def run_load(kind, vus, smoke, duration, warmup):
    reset()
    label=f'{"smoke" if smoke else "measurement"}-{kind}-{vus}'
    arguments=COMPOSE+['--profile','load','run','--rm','--no-deps','k6','run','--no-usage-report',
        '-e','BASE_URL=http://frontend','-e','DISPOSABLE_PERFORMANCE=1',
        '-e','SCENARIO='+kind,'-e','VUS='+str(vus),'-e','MEASURE_SECONDS='+str(duration),
        '-e','WARMUP_SECONDS='+str(warmup),'-e','RUN_KIND='+('smoke' if smoke else 'measurement'),
        '-e','ACCOUNT_PATTERN=perf{n}@example.test','-e','ACCOUNT_POOL=100',
        '-e','ACCOUNT_PASSWORD='+PASSWORD,'-e',f'RESULT_PATH=/results/{label}.json','/scripts/performance.js']
    print(f'{label}: preparación fuera de medición, calentamiento={warmup}s, medición={duration}s',flush=True)
    samples=[]
    started=time.monotonic()
    with tempfile.TemporaryFile(mode='w+') as log:
        process=subprocess.Popen(arguments,cwd=ROOT,env=ENV,stdout=log,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+duration+warmup+300
            while process.poll() is None:
                sample=capacity(); sample['elapsed_seconds']=round(time.monotonic()-started,3)
                sample['utc']=dt.datetime.now(dt.timezone.utc).isoformat();samples.append(sample)
                if not safe_capacity(sample) or time.monotonic()>deadline:
                    process.terminate()
                    raise RuntimeError('Carga detenida por presión de recursos o timeout')
                time.sleep(4)
            code=process.returncode
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
            log.seek(0)
            safe=log.read().replace(PASSWORD,'[REDACTED]')
            safe=re.sub(r'perf\d+@example\.test','[CUENTA_FICTICIA]',safe)
            (OUT/(label+'.log')).write_text(safe)
    save(label+'-resources.json',samples)
    path=OUT/'results'/(label+'.json')
    if not path.exists():raise RuntimeError('k6 no produjo resumen: '+label)
    result=assess(json.loads(path.read_text()),code)
    save(label+'-result.json',result)
    print(label+': '+result['state']+f' (exit {code})',flush=True)
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--smoke-only',action='store_true')
    parser.add_argument('--duration',type=int,default=60,help='segundos de medición, no de calentamiento')
    parser.add_argument('--warmup',type=int,default=10)
    args=parser.parse_args()
    if args.duration<30 or args.warmup<5:parser.error('Medición >=30s y calentamiento >=5s; no alteran límites RNF')
    OUT.mkdir(parents=True);(OUT/'results').mkdir(mode=0o777);os.chmod(OUT/'results',0o777)
    assets(OUT/'assets')
    metadata={'project':PROJECT,'started_utc':dt.datetime.now(dt.timezone.utc).isoformat(),
              'revision':os.environ.get('GITHUB_SHA','local-no-verificada-sin-git'),
              'dataset':{'active_products':1000,'independent_buyers':100,'reviews':26,
                         'detail_profiles':[[1,1,1,1],[2,3,3,5],[3,5,5,20]]},
              'risks':[],'resource_model':'backend 2 CPU/1GiB; MySQL 1 CPU/1GiB; nginx .25 CPU/128MiB; k6 1.5 CPU/512MiB'}
    runs=[];owned=False;code=3
    try:
        context=json.loads(cmd(['docker','context','inspect']).stdout)[0]
        if not context['Endpoints']['docker']['Host'].startswith('unix://'):raise RuntimeError('Docker remoto no permitido')
        first=capacity();save('capacity-before.json',first)
        if not safe_capacity(first,True):raise RuntimeError('Recursos insuficientes para un entorno adicional; no se lanzó carga')
        if any(ids(k) for k in ('container','network','volume')):raise RuntimeError('Proyecto ya existente')
        config=json.loads(cmd(COMPOSE+['--profile','load','config','--format','json']).stdout);isolation(config)
        metadata['docker_version']=cmd(['docker','version','--format','{{.Server.Version}}']).stdout.strip()
        metadata['compose_version']=cmd(['docker','compose','version','--short']).stdout.strip()
        metadata['k6_image']=config['services']['k6']['image']
        save('metadata.json',metadata)
        sources=[ROOT/'compose.performance.yaml']+list((ROOT/'scripts/performance').rglob('*.py'))+list((ROOT/'scripts/k6').rglob('*.js'))
        sources += [p for base in ('backend/demo/src','frontend/src') for p in (ROOT/base).rglob('*') if p.is_file()]
        sources += [p for base in ('backend/demo','frontend') for p in (ROOT/base).iterdir()
                    if p.is_file() and (p.name in ('Dockerfile','pom.xml','nginx.conf','docker-entrypoint.sh')
                                        or p.suffix in ('.json','.xml'))]
        save('source-sha256.json',{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources})
        owned=True
        print('Construyendo ambiente exclusivo '+PROJECT,flush=True)
        cmd(COMPOSE+['build','backend','frontend'],timeout=1500)
        cmd(COMPOSE+['--profile','load','pull','k6'],timeout=300)
        cmd(COMPOSE+['up','-d','--wait','--wait-timeout','300','mysql','backend','frontend'],timeout=360)
        verify_runtime()
        applied=[int(v) for v in mysql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank;').splitlines()]
        expected=sorted(int(p.name.split('__')[0][1:]) for p in (ROOT/'backend/demo/src/main/resources/db/migration').glob('V*__*.sql'))
        if applied!=expected or mysql('SELECT COUNT(*) FROM user_accounts;')!='0' or mysql('SELECT COUNT(*) FROM products;')!='0':
            raise RuntimeError('Flyway/base nueva no comprobados')
        mysql(sql());reset();metadata['dataset_verified']=True
        save('fixtures.json',dict(metadata['dataset'],flyway_versions=applied,verified=True))
        metadata['k6_version']=cmd(COMPOSE+['--profile','load','run','--rm','--no-deps','k6','version']).stdout.strip()
        for kind in ('catalog','resources'):
            result=run_load(kind,2,True,10,2);runs.append(result)
            if result['state']!='Cumple':raise RuntimeError('Smoke no válido; se impide aumentar carga')
        if not args.smoke_only:
            for vus in (50,100):
                if not safe_capacity(capacity()):raise RuntimeError('No se aumenta carga: recursos insuficientes')
                for kind in ('catalog','resources'):
                    result=run_load(kind,vus,False,args.duration,args.warmup);runs.append(result)
                    write_report(OUT,metadata,runs)
                    if result['state']=='No evaluado':raise RuntimeError('Evidencia inválida; no se aumenta carga')
        code=0 if all(r['state']=='Cumple' for r in runs) else 1
    except (Exception,KeyboardInterrupt) as error:
        metadata['risks'].append(str(error) if isinstance(error,RuntimeError) else type(error).__name__)
        print('Validación detenida: '+metadata['risks'][-1],flush=True)
    finally:
        if owned:
            if code==3:
                diagnostics=cmd(COMPOSE+['logs','--no-color','--tail','70','mysql'],check=False).stdout
                for secret in (PASSWORD,HASH,'performance_db_only','performance_root_only'):
                    diagnostics=diagnostics.replace(secret,'[REDACTED]')
                (OUT/'environment-failure.log').write_text(diagnostics)
            clean=cmd(COMPOSE+['--profile','load','down','--volumes','--remove-orphans','--rmi','local'],check=False,timeout=180)
            remaining={k:ids(k) for k in ('container','network','volume')}
            metadata['cleanup']={'exit_code':clean.returncode,'remaining':remaining,'passed':clean.returncode==0 and not any(remaining.values())}
            if not metadata['cleanup']['passed']:code=3
        metadata['exit_code']=code
        metadata['finished_utc']=dt.datetime.now(dt.timezone.utc).isoformat()
        save('metadata.json',metadata);write_report(OUT,metadata,runs)
        print('Reportes: '+str(OUT),flush=True)
    return code


if __name__=='__main__':
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    raise SystemExit(main())
