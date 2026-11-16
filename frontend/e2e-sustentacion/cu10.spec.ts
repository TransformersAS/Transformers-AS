import {expect} from '@playwright/test';
import {test,participants,chat,texts,start,send,capture,notice,detail,history,home,inbox,select,witness,evidence} from './cu10-helpers';
test('CU10 principal: comprador y vendedor, contexto, mensajes, avisos, lectura e historial',async({page,browser})=>{
 const pair=await test.step('01 — COMPRADOR y VENDEDOR: iniciar sesiones independientes',()=>participants(page,browser,'main'));const {g,buyer,seller,context}=pair;
 try{
  let id=0;
  await test.step('02 — COMPRADOR: consultar producto y contactar a su vendedor',async()=>{const c=await start(buyer,g);id=c.id;expect(c).toMatchObject({buyerId:g.buyerId,sellerId:g.sellerId,productId:g.productId,orderId:null});await capture(buyer,'main-01-comprador-contexto',chat(buyer).locator('main h3'));});
  await test.step('03 — COMPRADOR: enviar pregunta; VENDEDOR: identificar pendiente sin abrir',async()=>{
   await send(buyer,'¿Está disponible este producto de la demostración?');await expect(chat(buyer).locator('article time')).toBeVisible();await capture(buyer,'main-02-comprador-envia',texts(buyer));
   expect(await detail(seller,id)).toMatchObject({unread:1});await seller.bringToFront();await inbox(seller,true);await expect(chat(seller).locator('nav button').filter({hasText:g.productName})).toContainText('1 sin leer');await capture(seller,'main-03-vendedor-sin-leer',chat(seller).locator('nav'));
  });
  await test.step('04 — VENDEDOR: abrir desde su notificación, consultar contexto y marcar leído',async()=>{
   await home(seller);await notice(seller,g.productName,'main-04-vendedor-notificacion');await expect(texts(seller)).toHaveText(['¿Está disponible este producto de la demostración?']);await expect.poll(async()=>(await detail(seller,id)).unread).toBe(0);
   expect((await detail(seller,id)).readThrough).toBeGreaterThan(0);await capture(seller,'main-05-vendedor-lee',chat(seller).locator('main h3'));
  });
  await test.step('05 — VENDEDOR: responder; COMPRADOR: recibir aviso y respuesta en su contexto',async()=>{
   await home(buyer);await send(seller,'Sí, tenemos unidades disponibles para ti.');await capture(seller,'main-06-vendedor-responde',texts(seller).last());expect((await detail(buyer,id)).unread).toBe(1);
   await notice(buyer,g.productName,'main-07-comprador-notificacion');await expect(texts(buyer)).toHaveText(['¿Está disponible este producto de la demostración?','Sí, tenemos unidades disponibles para ti.']);await capture(buyer,'main-08-comprador-recibe',texts(buyer).last());
  });
  await test.step('06 — AMBOS: continuar, recargar y verificar historial íntegro en API y MySQL',async()=>{
   await send(buyer,'Gracias, conservaré esta información en nuestra conversación.');await seller.bringToFront();await expect(texts(seller)).toHaveCount(3,{timeout:20000});
   const rows=await history(buyer,id);const expected=rows.map((m:any)=>m.text);expect(rows.map((m:any)=>m.id)).toEqual(rows.map((m:any)=>m.id).sort((a:number,b:number)=>a-b));expect(rows.map((m:any)=>m.createdAt)).toEqual(rows.map((m:any)=>m.createdAt).sort());for(const [p,isSeller] of [[buyer,false],[seller,true]] as const){await home(p);await inbox(p,isSeller);await select(p,g.productName);await expect(texts(p)).toHaveText(expected);await expect(chat(p).getByRole('button',{name:/^(Editar mensaje|Eliminar mensaje)$/})).toHaveCount(0);await capture(p,isSeller?'main-10-vendedor-historial':'main-09-comprador-historial',chat(p).locator('main h3'));}
   await expect.poll(async()=>{const w=await witness('main','main-persisted');return w.externalSent;}).toBe(3);expect(await witness('main','main-final')).toMatchObject({threads:1,productThreads:1,orderThreads:0,messages:3,notifications:3,notificationLinksCorrect:3,externalSent:3});await evidence('main-history',{bothParticipantsSameHistory:true,messages:expected.length,chronologicalIds:(await history(buyer,id)).map((m:any)=>m.id)});
  });
 }finally{await context.close();}
});
