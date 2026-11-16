#!/usr/bin/env python3
"""Operaciones permitidas únicamente sobre un proyecto aleatorio CU07 propio activo."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[2]

def main():
    action = sys.argv[1]
    run = os.environ.get('SUST_RUN', '')
    if not re.fullmatch(r'sust-cu07-[0-9a-f]{12}', run) or os.environ.get('SUST_CU') != 'cu07':
        raise RuntimeError('Requiere launcher CU07 aislado')
    evidence = ROOT / 'artifacts/sustentacion' / run / 'isolation.json'
    if json.loads(evidence.read_text())['project'] != run:
        raise RuntimeError('Proyecto ajeno')
    env = {k:os.environ[k] for k in ['PATH','HOME','DOCKER_CONFIG','TMPDIR'] if k in os.environ}
    if os.environ.get('DOCKER_HOST'):
        raise RuntimeError('Docker remoto no permitido')
    compose = ['docker','compose','--env-file','/dev/null','--project-directory',str(ROOT),'-f',str(ROOT/'compose.sustentacion.yaml'),'-p',run]
    def call(args, data=None):
        r = subprocess.run(args,input=data,text=True,capture_output=True,env=env,cwd=ROOT,timeout=90)
        if r.returncode: raise RuntimeError('Falló fixture CU07 (detalle sensible omitido)')
        return r.stdout.strip()
    if action in ['smtp-stop','smtp-start','backend-stop','backend-start']:
        service='mailpit' if action.startswith('smtp-') else 'backend'
        call(compose + (['stop','--timeout','10',service] if action.endswith('-stop') else ['start',service]))
        result={'ownService':service,'action':action}
        if action=='smtp-start':
            address=call(compose+['port','mailpit','8025'])
            if not re.fullmatch(r'127\.0\.0\.1:[1-9][0-9]*',address):raise RuntimeError('Puerto no loopback')
            result['mailUrl']='http://'+address
        with (evidence.parent/'cu07-service-restorations.jsonl').open('a') as log:
            log.write(json.dumps(result)+'\n')
        print(json.dumps(result));return
    email = sys.argv[2]
    if not re.fullmatch(r'(registration|cu07-(existing|validation|verification|recovery|error))@example\.test',email):
        raise RuntimeError('Cuenta no autorizada')
    command = compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
    if call(command,'SELECT DATABASE();')!='sustentacion':raise RuntimeError('Base ajena')
    if action=='expire':
        # Sólo adelanta el vencimiento del mecanismo REAL de una cuenta de pruebas aún no verificada.
        count=call(command,f"SELECT COUNT(*) FROM user_accounts WHERE email='{email}' AND email_verified_at IS NULL;")
        if count!='1':raise RuntimeError('No es cuenta pendiente propia')
        call(command,f"UPDATE email_verification_tokens t JOIN user_accounts a ON a.id=t.account_id SET t.expires_at=NOW(6)-INTERVAL 1 MINUTE WHERE a.email='{email}' AND t.used_at IS NULL;")
        print(json.dumps({'expiredOwnPendingToken':True}));return
    if action!='witness':raise RuntimeError('Acción no permitida')
    query=f"""SELECT JSON_OBJECT(
      'accounts',COUNT(*),
      'verified',COALESCE(MAX(email_verified_at IS NOT NULL),0),
      'statusActive',COALESCE(MAX(status='ACTIVA'),0),
      'buyerRoles',(SELECT COUNT(*) FROM user_account_roles r JOIN user_accounts a ON a.id=r.account_id WHERE a.email='{email}' AND r.role='COMPRADOR'),
      'roles',(SELECT COUNT(*) FROM user_account_roles r JOIN user_accounts a ON a.id=r.account_id WHERE a.email='{email}'),
      'profiles',(SELECT COUNT(*) FROM account_profiles p JOIN user_accounts a ON a.id=p.account_id WHERE a.email='{email}'),
      'consents',(SELECT COUNT(*) FROM buyer_consents c JOIN user_accounts a ON a.id=c.account_id WHERE a.email='{email}'),
      'profileMatches',COALESCE((SELECT full_name='Persona Registro' AND birth_date='2000-05-20' AND phone='3000000000' FROM account_profiles p JOIN user_accounts a ON a.id=p.account_id WHERE a.email='{email}'),FALSE),
      'existingProfilePreserved',COALESCE((SELECT full_name='Persona Existente' AND birth_date='1990-01-01' AND phone='3000000000' FROM account_profiles p JOIN user_accounts a ON a.id=p.account_id WHERE a.email='{email}'),FALSE),
      'existingCredentialsPreserved',COALESCE(MAX(password_hash='$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m'),0),
      'tokens',(SELECT COUNT(*) FROM email_verification_tokens t JOIN user_accounts a ON a.id=t.account_id WHERE a.email='{email}'),
      'consumedTokens',(SELECT COUNT(*) FROM email_verification_tokens t JOIN user_accounts a ON a.id=t.account_id WHERE a.email='{email}' AND t.used_at IS NOT NULL)
    ) FROM user_accounts WHERE email='{email}';"""
    # Sólo contadores/booleanos; nunca hashes, identificadores de sesión ni mecanismos.
    data=json.loads(call(command,query))
    print(json.dumps({k:int(v) if isinstance(v,bool) else v for k,v in data.items()}))

if __name__=='__main__':
    try:main()
    except Exception:
        print('Fixture CU07 rechazada o fallida; no se imprimen datos sensibles.',file=sys.stderr);sys.exit(3)
