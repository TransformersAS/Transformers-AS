#!/usr/bin/env python3
"""CU11: testigos y fallos en datos/recursos exclusivamente propios."""
import json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
 action,key=sys.argv[1:3];run=os.environ.get('SUST_RUN','')
 if os.environ.get('SUST_CU')!='cu11' or not re.fullmatch(r'sust-cu11-[0-9a-f]{12}',run) or os.environ.get('DOCKER_HOST'):raise RuntimeError()
 out=ROOT/'artifacts/sustentacion'/run
 if json.loads((out/'isolation.json').read_text())['project']!=run:raise RuntimeError()
 fixture=json.loads((out/'cu11-fixture.json').read_text())
 if key not in fixture['groups']:raise RuntimeError()
 g=fixture['groups'][key];alias=sys.argv[3] if action=='witness' and len(sys.argv)>3 else 'primary'
 if alias not in g['orders']:raise RuntimeError()
 order=int(g['orders'][alias]);buyer=int(g['buyerId']);store=int(g['storeId']);pa=int(g['productA']);pb=int(g['productB'])
 env={k:os.environ[k] for k in ['PATH','HOME','DOCKER_CONFIG','TMPDIR'] if k in os.environ}
 cmd=['docker','compose','--env-file','/dev/null','--project-directory',str(ROOT),'-f',str(ROOT/'compose.sustentacion.yaml'),'-p',run,'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"']
 def sql(text):
  r=subprocess.run(cmd,input=text,cwd=ROOT,env=env,text=True,capture_output=True,timeout=60)
  if r.returncode:raise RuntimeError()
  return r.stdout.strip()
 if sql('SELECT DATABASE();')!='sustentacion':raise RuntimeError()
 if action=='pdf':
  path=Path(sys.argv[3]).resolve()
  if not path.is_relative_to(out/'receipts') or path.suffix!='.pdf':raise RuntimeError()
  m2=Path.home()/'.m2/repository';jars=[]
  for directory in ['org/apache/pdfbox/pdfbox','org/apache/pdfbox/fontbox','org/apache/pdfbox/pdfbox-io','commons-logging/commons-logging']:
   found=[p for p in (m2/directory).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))]
   if not found:raise RuntimeError()
   jars.append(str(sorted(found,key=lambda p:tuple(int(n) for n in re.findall(r'\d+',p.parent.name)))[-1]))
  r=subprocess.run(['java','--class-path',os.pathsep.join(jars),str(ROOT/'scripts/demos/Cu11ReceiptText.java'),str(path)],text=True,encoding='utf-8',capture_output=True,timeout=60)
  if r.returncode:raise RuntimeError()
  print(json.dumps({'text':r.stdout}));return
 if action=='race' and key=='race':
  sql(f"UPDATE orders SET status='READY_FOR_DISPATCH' WHERE id={order} AND account_id={buyer}; INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,reason,correlation_id,created_at) VALUES({order},'CONFIRMED','READY_FOR_DISPATCH','SYSTEM','Fixture de carrera CU11; no despacho ejecutado','cu11-race',NOW(6));")
  print(json.dumps({'ownConcurrentStateFixture':'READY_FOR_DISPATCH'}));return
 if action in ['query-off','query-on'] and key=='query':
  sql('RENAME TABLE orders TO cu11_demo_orders_unavailable;' if action=='query-off' else 'RENAME TABLE cu11_demo_orders_unavailable TO orders;');print(json.dumps({'ownQueryUnavailable':action=='query-off'}));return
 if action!='witness':raise RuntimeError()
 ids=','.join(str(int(v)) for v in g['orders'].values());n=f"reference_type='ORDER' AND reference_id IN ({ids}) AND type='ORDER_CANCELLED'"
 query=f"""SELECT JSON_OBJECT('orderId',id,'status',status,'paymentStatus',payment_status,'stockA',(SELECT stock FROM products WHERE id={pa}),'stockB',(SELECT stock FROM products WHERE id={pb}),
 'cancellations',(SELECT COUNT(*) FROM order_cancellations WHERE order_id IN ({ids})),
 'reasonOther',(SELECT COUNT(*) FROM order_cancellations WHERE order_id={order} AND initiator='BUYER' AND reason_code='OTHER' AND LENGTH(TRIM(details))>0 AND created_at IS NOT NULL),
 'cancellationDigest',(SELECT SHA2(CONCAT_WS(':',reason_code,details,created_at,initiator),256) FROM order_cancellations WHERE order_id={order}),
 'historyCancelled',(SELECT COUNT(*) FROM order_status_history WHERE order_id={order} AND to_status='CANCELLED'),
 'refunds',(SELECT COUNT(*) FROM refunds WHERE order_id IN ({ids})),
 'refundStatus',(SELECT status FROM refunds WHERE order_id={order} ORDER BY id DESC LIMIT 1),
 'refundErrorRecorded',(SELECT COUNT(*) FROM refunds WHERE order_id={order} AND last_error IS NOT NULL),
 'simulatedRefundReference',(SELECT COUNT(*) FROM refunds WHERE order_id={order} AND provider_reference LIKE 'SIM-REFUND-%'),
 'buyerNotifications',(SELECT COUNT(*) FROM notifications WHERE {n} AND recipient_type='BUYER' AND recipient_id={buyer}),
 'sellerNotifications',(SELECT COUNT(*) FROM notifications WHERE {n} AND recipient_type='STORE' AND recipient_id={store}),
 'externalSent',(SELECT COUNT(*) FROM notifications WHERE {n} AND external_status='SENT' AND attempts>0),
 'externalFailed',(SELECT COUNT(*) FROM notifications WHERE {n} AND external_status='FAILED' AND attempts>0),
 'orderDataDigest',(SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':',id,status,payment_status,total,discount_amount,shipping_cost,seller_name_snapshot,delivery_street) ORDER BY id),256) FROM orders WHERE id IN ({ids})),
 'itemsDigest',(SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':',order_id,product_id,product_name,quantity,unit_price,subtotal) ORDER BY order_id,id),256) FROM order_items WHERE order_id IN ({ids})),
 'pendingCheckouts',(SELECT COUNT(*) FROM payment_checkouts WHERE account_id={buyer})) FROM orders WHERE id={order} AND account_id={buyer};"""
 print(json.dumps(json.loads(sql(query))))
if __name__=='__main__':
 try:main()
 except Exception:print('Instrumento CU11 rechazado o fallido; datos reservados omitidos.',file=sys.stderr);sys.exit(3)
