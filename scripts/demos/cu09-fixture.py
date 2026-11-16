#!/usr/bin/env python3
"""CU09: testigos y preparación de fallos/vencimiento sólo en el proyecto propio activo."""
import json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
 action=sys.argv[1];key=sys.argv[2] if len(sys.argv)>2 else 'all';run=os.environ.get('SUST_RUN','')
 if os.environ.get('SUST_CU')!='cu09' or not re.fullmatch(r'sust-cu09-[0-9a-f]{12}',run) or os.environ.get('DOCKER_HOST'):raise RuntimeError()
 out=ROOT/'artifacts/sustentacion'/run
 if json.loads((out/'isolation.json').read_text())['project']!=run:raise RuntimeError()
 fixture=json.loads((out/'cu09-fixture.json').read_text());env={k:os.environ[k] for k in ['PATH','HOME','DOCKER_CONFIG','TMPDIR'] if k in os.environ}
 compose=['docker','compose','--env-file','/dev/null','--project-directory',str(ROOT),'-f',str(ROOT/'compose.sustentacion.yaml'),'-p',run]
 def call(cmd,data=None):
  r=subprocess.run(cmd,cwd=ROOT,env=env,input=data,text=True,capture_output=True,timeout=90)
  if r.returncode:raise RuntimeError()
  return r.stdout.strip()
 if action in ['backend-stop','backend-start']:
  call(compose+(['stop','--timeout','10','backend'] if action.endswith('stop') else ['start','backend']))
  value={'ownBackend':True,'action':action}
  with (out/'cu09-services.jsonl').open('a') as f:f.write(json.dumps(value)+'\n')
  print(json.dumps(value));return
 if key not in ['main','validation','email','preferences','blocked','closure','failure','control','legacy']:raise RuntimeError()
 prefix='cu09-real' if key=='legacy' else 'cu09-'+key
 id=int(fixture['accounts'][prefix]);old=prefix+'@example.test'
 command=compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
 if call(command,'SELECT DATABASE();')!='sustentacion':raise RuntimeError()
 if action=='expire-email' and key=='email':
  call(command,f"UPDATE account_email_changes SET expires_at=NOW(6)-INTERVAL 1 MINUTE WHERE account_id={id} AND used_at IS NULL;");print(json.dumps({'expiredOwnEmailMechanism':True}));return
 if action!='witness':raise RuntimeError()
 original='Ana Perfil' if key=='legacy' else 'Persona Demostración'
 query=f"""SELECT JSON_OBJECT('accounts',COUNT(*),'active',COALESCE(MAX(a.status='ACTIVA'),0),'inactive',COALESCE(MAX(a.status='INACTIVA'),0),
 'oldEmail',COALESCE(MAX(a.email='{old}'),0),'originalData',COALESCE(MAX(p.full_name='{original}' AND p.birth_date='2000-01-01' AND p.phone='3000000000'),0),
 'updatedData',COALESCE(MAX(p.full_name='Persona Actualizada' AND p.birth_date='1995-06-15' AND p.phone='3100000000'),0),
 'promotions',COALESCE(MAX(s.promotions),0),'news',COALESCE(MAX(s.marketplace_news),0),'version',COALESCE(MAX(s.version),0),
 'sessions',(SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=(SELECT email FROM user_accounts WHERE id={id}) AND EXPIRY_TIME>UNIX_TIMESTAMP(NOW(3))*1000),
 'pendingEmail',(SELECT COUNT(*) FROM account_email_changes WHERE account_id={id} AND used_at IS NULL),
 'profiles',(SELECT COUNT(*) FROM account_profiles WHERE account_id={id}),
 'orders',(SELECT COUNT(*) FROM orders WHERE account_id={id}),
 'items',(SELECT COUNT(*) FROM order_items i JOIN orders o ON i.order_id=o.id WHERE o.account_id={id}),
 'history',(SELECT COUNT(*) FROM order_status_history h JOIN orders o ON h.order_id=o.id WHERE o.account_id={id}),
 'claims',(SELECT COUNT(*) FROM claims WHERE buyer_account_id={id}),
 'returns',(SELECT COUNT(*) FROM return_requests WHERE buyer_account_id={id}),
 'refunds',(SELECT COUNT(*) FROM refunds r JOIN orders o ON r.order_id=o.id WHERE o.account_id={id}),
 'suspendedStores',(SELECT COUNT(*) FROM stores WHERE owner_account_id={id} AND status='SUSPENDED'),
 'activeProducts',(SELECT COUNT(*) FROM products WHERE store_id IN (SELECT id FROM stores WHERE owner_account_id={id}) AND active=TRUE))
 FROM user_accounts a LEFT JOIN account_profiles p ON p.account_id=a.id LEFT JOIN account_profile_settings s ON s.account_id=a.id WHERE a.id={id};"""
 value=json.loads(call(command,query));print(json.dumps({k:int(v) if isinstance(v,bool) else v for k,v in value.items()}))
if __name__=='__main__':
 try:main()
 except Exception:print('Fixture CU09 rechazada o fallida; datos reservados omitidos.',file=sys.stderr);sys.exit(3)
