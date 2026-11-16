#!/usr/bin/env python3
"""Instrumento CU10: conteos y fallos sólo sobre la base nueva del proyecto propio."""
import json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
 action,key=sys.argv[1:3];run=os.environ.get('SUST_RUN','')
 if os.environ.get('SUST_CU')!='cu10' or not re.fullmatch(r'sust-cu10-[0-9a-f]{12}',run) or os.environ.get('DOCKER_HOST'):raise RuntimeError()
 out=ROOT/'artifacts/sustentacion'/run
 if json.loads((out/'isolation.json').read_text())['project']!=run:raise RuntimeError()
 fixture=json.loads((out/'cu10-fixture.json').read_text())
 if key not in fixture['groups']:raise RuntimeError()
 g=fixture['groups'][key];buyer=int(g['buyerId']);seller=int(g['sellerId']);store=int(g['storeId'])
 env={k:os.environ[k] for k in ['PATH','HOME','DOCKER_CONFIG','TMPDIR'] if k in os.environ}
 cmd=['docker','compose','--env-file','/dev/null','--project-directory',str(ROOT),'-f',str(ROOT/'compose.sustentacion.yaml'),'-p',run,'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
 def sql(text):
  r=subprocess.run(cmd,input=text,text=True,cwd=ROOT,env=env,capture_output=True,timeout=60)
  if r.returncode:raise RuntimeError()
  return r.stdout.strip()
 if sql('SELECT DATABASE();')!='sustentacion':raise RuntimeError()
 if action in ['restrict','unrestrict'] and key=='restricted':
  value=1 if action=='restrict' else 0;sql(f'UPDATE user_accounts SET communications_restricted={value} WHERE id={buyer};');print(json.dumps({'ownCommunicationRestriction':bool(value)}));return
 if action in ['fail-on','fail-off'] and key=='failure':
  sql("ALTER TABLE conversation_messages ADD CONSTRAINT cu10_demo_failure CHECK(text<>'CU10 fallo de persistencia');" if action=='fail-on' else 'ALTER TABLE conversation_messages DROP CHECK cu10_demo_failure;')
  print(json.dumps({'ownPersistenceConstraint':action=='fail-on'}));return
 if action!='witness':raise RuntimeError()
 threads=f'SELECT id FROM conversations WHERE buyer_id={buyer} AND seller_id={seller}'
 messages=f'SELECT id FROM conversation_messages WHERE conversation_id IN ({threads})'
 query=f"""SELECT JSON_OBJECT('threads',COUNT(*),'productThreads',COALESCE(SUM(order_id IS NULL),0),'orderThreads',COALESCE(SUM(order_id IS NOT NULL),0),'closed',COALESCE(SUM(closed),0),
 'messages',(SELECT COUNT(*) FROM conversation_messages WHERE conversation_id IN ({threads})),
 'historyDigest',(SELECT SHA2(COALESCE(GROUP_CONCAT(CONCAT_WS(':',id,sender_id,text,created_at) ORDER BY id SEPARATOR '|'),''),256) FROM conversation_messages WHERE conversation_id IN ({threads})),
 'images',(SELECT COUNT(*) FROM conversation_images WHERE message_id IN ({messages})),
 'notifications',(SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id IN ({threads})),
 'externalSent',(SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id IN ({threads}) AND external_status='SENT' AND attempts>0),
 'externalFailed',(SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id IN ({threads}) AND external_status='FAILED' AND attempts>0),
 'notificationLinksCorrect',(SELECT COUNT(*) FROM notifications n JOIN conversation_messages m ON n.event_key=CONCAT('conversation-message-',m.id) AND n.reference_id=CAST(m.conversation_id AS CHAR) WHERE m.conversation_id IN ({threads}) AND ((m.sender_id={buyer} AND n.recipient_type='STORE' AND n.recipient_id={store}) OR (m.sender_id={seller} AND n.recipient_type='BUYER' AND n.recipient_id={buyer}))),
 'buyerArchived',(SELECT COALESCE(SUM(archived),0) FROM conversation_participants WHERE conversation_id IN ({threads}) AND account_id={buyer}),
 'sellerReadThrough',(SELECT COALESCE(MAX(read_through),0) FROM conversation_participants WHERE conversation_id IN ({threads}) AND account_id={seller}),
 'reports',(SELECT COUNT(*) FROM reports WHERE content_type='MENSAJE' AND content_id IN ({messages}))) FROM conversations WHERE buyer_id={buyer} AND seller_id={seller};"""
 print(json.dumps(json.loads(sql(query))))
if __name__=='__main__':
 try:main()
 except Exception:print('Instrumento CU10 rechazado o fallido; datos reservados omitidos.',file=sys.stderr);sys.exit(3)
