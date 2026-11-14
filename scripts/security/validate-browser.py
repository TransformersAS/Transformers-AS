#!/usr/bin/env python3
"""Validación funcional/CSP real independiente de la base del escaneo. Sin Git."""
import hashlib,json,os,subprocess,sys,uuid
from pathlib import Path
import run as runner
ROOT=runner.ROOT
PROJECT='rnf-zap-'+uuid.uuid4().hex[:12]
OUT=ROOT/'artifacts/security-remediation'/('browser-'+PROJECT)
runner.PROJECT=PROJECT;runner.OUT=OUT;runner.COMPOSE=['docker','compose','--env-file','/dev/null','-f',str(ROOT/'compose.security.yaml'),'-p',PROJECT]
C=runner.COMPOSE
IMAGE='mcr.microsoft.com/playwright@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27'
PASSWORD='Cu08RealTestPassword!'
OUT.mkdir(parents=True);os.chmod(OUT,0o777)
def save(n,d):(OUT/n).write_text(json.dumps(d,indent=2)+'\n')
owned=False;code=3
try:
 context=json.loads(runner.invoke(['docker','context','inspect']).stdout)[0]
 if not context['Endpoints']['docker']['Host'].startswith('unix://'):raise RuntimeError('Docker remoto no permitido')
 config=json.loads(runner.invoke(C+['--profile','scan','config','--format','json']).stdout);runner.assert_config(config)
 if any(runner.resources(k) for k in ['container','network','volume']):raise RuntimeError('Proyecto existente')
 save('source-sha256.json',runner.source_manifest());owned=True
 print('Construyendo validación real '+PROJECT,flush=True)
 r=runner.invoke(C+['build','backend','frontend'],timeout=1500,check=False)
 (OUT/'build.log').write_text((r.stdout+r.stderr).replace(str(ROOT),'[WORKSPACE]'))
 if r.returncode:raise RuntimeError('Build falló')
 runner.invoke(C+['up','-d','--wait','--wait-timeout','300','mysql','backend','frontend'],timeout=360);runner.verify_runtime()
 versions=runner.mysql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank;').splitlines()
 expected=sorted(int(p.name.split('__')[0][1:]) for p in (ROOT/'backend/demo/src/main/resources/db/migration').glob('V*__*.sql'))
 if list(map(int,versions))!=expected or runner.mysql('SELECT COUNT(*) FROM user_accounts')!='0':raise RuntimeError('Base no nueva/migrada')
 runner.mysql((ROOT/'scripts/security/seed.sql').read_text())
 runner.mysql("INSERT INTO user_accounts(id,email,password_hash,status,email_verified_at) SELECT 2,'zap-seller@example.test',password_hash,'ACTIVA',CURRENT_TIMESTAMP(6) FROM user_accounts WHERE id=1; INSERT INTO user_account_roles VALUES(2,'VENDEDOR'); UPDATE stores SET owner_account_id=2 WHERE id=1;")
 runner.invoke(['docker','pull',IMAGE],timeout=600)
 target=PROJECT+'_target'
 args=['docker','run','--rm','--name',PROJECT+'-browser','--label','com.docker.compose.project='+PROJECT,'--label','com.docker.compose.service=browser','--network',target,'--memory','1g','--cpus','1','--shm-size','256m',
 '-v',str(ROOT/'frontend/node_modules/playwright')+':/opt/node_modules/playwright:ro','-v',str(ROOT/'frontend/node_modules/playwright-core')+':/opt/node_modules/playwright-core:ro','-v',str(ROOT/'scripts/security/browser-check.cjs')+':/browser-check.cjs:ro','-v',str(OUT)+':/results','-e','NODE_PATH=/opt/node_modules','-e','FIXTURE_PASSWORD='+PASSWORD,IMAGE,'node','/browser-check.cjs']
 r=runner.invoke(args,timeout=300,check=False);(OUT/'browser.log').write_text((r.stdout+r.stderr).replace(PASSWORD,'[REDACTED]'))
 code=r.returncode
except Exception as e:save('error.json',{'type':type(e).__name__,'message':str(e).replace(str(ROOT),'[WORKSPACE]')});print(type(e).__name__,flush=True)
finally:
 if owned:
  r=runner.invoke(C+['--profile','scan','down','--volumes','--remove-orphans','--rmi','local'],timeout=180,check=False);remaining={k:runner.resources(k) for k in ['container','network','volume']};save('cleanup.json',{'passed':r.returncode==0 and not any(remaining.values()),'remaining':remaining})
  if r.returncode or any(remaining.values()):code=3
 print('Evidencia de navegador: '+str(OUT),flush=True)
raise SystemExit(code)
