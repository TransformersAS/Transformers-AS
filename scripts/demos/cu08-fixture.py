#!/usr/bin/env python3
"""Testigos y vencimientos CU08; sólo sobre recursos propios ya validados por el launcher."""
import json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
    action=sys.argv[1];email=sys.argv[2] if len(sys.argv)>2 else ''
    run=os.environ.get('SUST_RUN','')
    if os.environ.get('SUST_CU')!='cu08' or not re.fullmatch(r'sust-cu08-[0-9a-f]{12}',run) or os.environ.get('DOCKER_HOST'):raise RuntimeError()
    out=ROOT/'artifacts/sustentacion'/run
    if json.loads((out/'isolation.json').read_text())['project']!=run:raise RuntimeError()
    env={k:os.environ[k] for k in ['PATH','HOME','DOCKER_CONFIG','TMPDIR'] if k in os.environ}
    compose=['docker','compose','--env-file','/dev/null','--project-directory',str(ROOT),'-f',str(ROOT/'compose.sustentacion.yaml'),'-f',str(ROOT/'compose.cu08-presentation.yaml'),'-p',run]
    def call(cmd,data=None):
        r=subprocess.run(cmd,cwd=ROOT,env=env,input=data,text=True,capture_output=True,timeout=90)
        if r.returncode:raise RuntimeError()
        return r.stdout.strip()
    if action in ['smtp-stop','smtp-start']:
        call(compose+(['stop','--timeout','10','mailpit'] if action.endswith('stop') else ['start','mailpit']))
        result={'ownMailpit':True,'action':action}
        if action.endswith('start'):
            address=call(compose+['port','mailpit','8025'])
            if not re.fullmatch(r'127\.0\.0\.1:[1-9][0-9]*',address):raise RuntimeError()
            result['mailUrl']='http://'+address
        with (out/'cu08-services.jsonl').open('a') as f:f.write(json.dumps(result)+'\n')
        print(json.dumps(result));return
    if email!='all' and not re.fullmatch(r'cu08-(main|control|password|recovery|pending|mail|inactive|restricted|global|expiry|persistent|sessions)@example\.test',email):raise RuntimeError()
    cmd=compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
    if call(cmd,'SELECT DATABASE();')!='sustentacion':raise RuntimeError()
    if action=='expire-session' and email in ['cu08-expiry@example.test','cu08-persistent@example.test']:
        call(cmd,f"UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=UNIX_TIMESTAMP(NOW(3)-INTERVAL 8 DAY)*1000,EXPIRY_TIME=UNIX_TIMESTAMP(NOW(3)-INTERVAL 1 MINUTE)*1000 WHERE PRINCIPAL_NAME='{email}';")
        print(json.dumps({'ownSessionAged':True}));return
    if action=='expire-recovery' and email=='cu08-recovery@example.test':
        call(cmd,f"UPDATE password_recovery_tokens t JOIN user_accounts a ON a.id=t.account_id SET t.expires_at=NOW(6)-INTERVAL 1 MINUTE WHERE a.email='{email}' AND t.used_at IS NULL;")
        print(json.dumps({'ownRecoveryMechanismExpired':True}));return
    if action!='witness':raise RuntimeError()
    old='$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m'
    if email=='all':
        q=f"SELECT JSON_OBJECT('accounts',COUNT(*),'changedCredentials',SUM(password_hash<>'{old}'),'roles',(SELECT COUNT(*) FROM user_account_roles),'restrictions',(SELECT COUNT(*) FROM account_restrictions)) FROM user_accounts;"
    else:
        q=f"""SELECT JSON_OBJECT('accounts',COUNT(*),'verified',COALESCE(MAX(email_verified_at IS NOT NULL),0),'credentialChanged',COALESCE(MAX(password_hash<>'{old}'),0),
        'sessions',(SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='{email}' AND EXPIRY_TIME>UNIX_TIMESTAMP(NOW(3))*1000),
        'maxIdleSeconds',COALESCE((SELECT MAX(MAX_INACTIVE_INTERVAL) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='{email}' AND EXPIRY_TIME>UNIX_TIMESTAMP(NOW(3))*1000),0),
        'recoveryMechanisms',(SELECT COUNT(*) FROM password_recovery_tokens t JOIN user_accounts a ON a.id=t.account_id WHERE a.email='{email}')) FROM user_accounts WHERE email='{email}';"""
    data=json.loads(call(cmd,q));print(json.dumps({k:int(v) if isinstance(v,bool) else v for k,v in data.items()}))
if __name__=='__main__':
    try:main()
    except Exception:print('Fixture CU08 rechazada o fallida; datos sensibles omitidos.',file=sys.stderr);sys.exit(3)
