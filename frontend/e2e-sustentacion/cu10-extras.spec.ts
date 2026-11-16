import {expect} from '@playwright/test';
import {test,fixture,participants,chat,texts,start,send,capture,detail,history,home,inbox,select,state,witness,evidence,action,write,login,png} from './cu10-helpers';

test('CU10 contexts: reutilizar y distinguir productos y dos líneas de pedido',async({page,browser})=>{
 const {g,buyer,context}=await participants(page,browser,'contexts');try{
  let first=0;
  await test.step('A1 — Reabrir el mismo producto conserva ID e historia sin duplicar',async()=>{
   first=(await start(buyer,g)).id;await send(buyer,'Historia del primer producto');await home(buyer);expect((await start(buyer,g)).id).toBe(first);await expect(texts(buyer)).toHaveText(['Historia del primer producto']);expect(await witness('contexts','contexts-reused')).toMatchObject({threads:1,messages:1});await capture(buyer,'contexts-01-reutilizada',chat(buyer).locator('main'));
  });
  await test.step('A2 — Otro producto del mismo vendedor crea un contexto independiente',async()=>{await home(buyer);const c=await start(buyer,g,true);expect(c.id).not.toBe(first);await expect(texts(buyer)).toHaveCount(0);await send(buyer,'Pregunta del segundo producto');await capture(buyer,'contexts-02-otro-producto',chat(buyer).locator('main'));});
  await test.step('RF037/RF101 — Elegir cada producto de un pedido de dos líneas',async()=>{
   const ids=[];for(let i=0;i<2;i++){
    await home(buyer);await buyer.getByRole('button',{name:'Mis pedidos',exact:true}).click();await buyer.getByRole('button',{name:`Ver detalle del pedido ${g.orderId}`,exact:true}).click();
    const contacts=buyer.locator('app-mis-pedidos app-contactar-vendedor');await expect(contacts).toHaveCount(2);await capture(buyer,'contexts-03-pedido-linea-'+i,contacts.nth(i));
    const response=buyer.waitForResponse(r=>new URL(r.url()).pathname==='/api/conversations'&&r.request().method()==='POST');await contacts.nth(i).getByRole('button').click();const c=await(await response).json();expect(c).toMatchObject({buyerId:g.buyerId,sellerId:g.sellerId,orderId:g.orderId,productId:i===0?g.productId:g.otherProductId});ids.push(c.id);
    await expect(chat(buyer).locator('main')).toContainText(`Pedido #${g.orderId}`);await send(buyer,'Consulta del pedido sobre la línea '+(i+1));await capture(buyer,'contexts-04-contexto-pedido-'+i,chat(buyer).locator('main h3'));
   }
   expect(new Set([first,...ids]).size).toBe(3);expect(await witness('contexts','contexts-independent')).toMatchObject({threads:4,productThreads:2,orderThreads:2,messages:4,notifications:4});
  });
 }finally{await context.close();}
});

test('CU10 images: vacío, formato, tamaño y contenido; imágenes de ambos participantes',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'images');try{
  const c=await start(buyer,g);
  await test.step('A4 — Vacío o sólo espacios no habilita el envío ni crea mensaje',async()=>{await expect(chat(buyer).getByRole('button',{name:'Enviar mensaje',exact:true})).toBeDisabled();await chat(buyer).getByLabel('Mensaje',{exact:true}).fill('   ');await expect(chat(buyer).getByRole('button',{name:'Enviar mensaje',exact:true})).toBeDisabled();expect(await witness('images','images-empty')).toMatchObject({messages:0,notifications:0});await capture(buyer,'images-01-vacio',chat(buyer).locator('form'));});
  await test.step('A3 — Formato y tamaño excesivo se rechazan antes del envío, sin perder borrador',async()=>{
   await chat(buyer).getByLabel('Mensaje',{exact:true}).fill('Borrador válido conservado');
   for(const [name,mimeType,buffer,label] of [['no-permitido.txt','text/plain',Buffer.from('texto'),'formato'],['excesiva.png','image/png',Buffer.alloc(5242881),'tamano']] as const){
    await chat(buyer).locator('input[type=file]').setInputFiles({name,mimeType,buffer});await expect(chat(buyer).getByRole('alert')).toContainText('Selecciona JPG, PNG o WebP de hasta 5 MiB.');await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toHaveValue('Borrador válido conservado');await expect(chat(buyer).getByRole('button',{name:/^Quitar /})).toHaveCount(0);await capture(buyer,'images-02-'+label,chat(buyer).getByRole('alert'));
   }
   expect(await witness('images','images-invalid-client')).toMatchObject({messages:0,images:0,notifications:0});
  });
  await test.step('A3 — Archivo renombrado PNG con contenido corrupto falla en el backend real',async()=>{
   await chat(buyer).locator('input[type=file]').setInputFiles({name:'corrupta.png',mimeType:'image/png',buffer:Buffer.from('contenido no es imagen')});await send(buyer,'Borrador válido conservado',400);await expect(chat(buyer).getByRole('alert')).toBeVisible();await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toHaveValue('Borrador válido conservado');expect(await witness('images','images-invalid-server')).toMatchObject({messages:0,images:0});await capture(buyer,'images-03-corrupta',chat(buyer).getByRole('alert'));
  });
  await test.step('RF038/RF102 — Comprador y vendedor adjuntan PNG válida visible en ambos historiales',async()=>{
   await chat(buyer).locator('input[type=file]').setInputFiles({name:'comprador.png',mimeType:'image/png',buffer:png});await send(buyer,'Imagen válida del comprador');await inbox(seller,true);await select(seller,g.productName);await expect(chat(seller).locator('article img')).toBeVisible();
   await chat(seller).locator('input[type=file]').setInputFiles({name:'vendedor.png',mimeType:'image/png',buffer:png});await send(seller,'Imagen válida del vendedor');
   for(const [p,role] of [[buyer,'comprador'],[seller,'vendedor']] as const){await p.bringToFront();await expect(chat(p).locator('article img')).toHaveCount(2,{timeout:20000});for(const img of await chat(p).locator('article img').all())await expect.poll(()=>img.evaluate((e:HTMLImageElement)=>e.complete&&e.naturalWidth>0)).toBe(true);await capture(p,'images-04-validas-'+role,chat(p).locator('main'));}
   expect(await witness('images','images-valid-persisted')).toMatchObject({messages:2,images:2,notifications:2});expect((await history(buyer,c.id)).every((m:any)=>m.image)).toBe(true);
  });
 }finally{await context.close();}
});

test('CU10 states: archivo reversible permite conversar; cierre bloquea hasta reabrir',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'states');try{
  const c=await start(buyer,g);await send(buyer,'Historia que debe conservarse');const before=await witness('states','states-before');
  await test.step('A7 — Archivar retira sólo de principal y permite enviar desde Archivadas',async()=>{
   await state(buyer,'Archivar','archive');await expect(chat(buyer).locator('nav button').filter({hasText:g.productName})).toHaveCount(0);expect(await detail(buyer,c.id)).toMatchObject({archived:true,closed:false});
   await chat(buyer).getByLabel('Archivadas',{exact:true}).check();await select(buyer,g.productName);await expect(texts(buyer)).toHaveText(['Historia que debe conservarse']);expect((await witness('states','states-archived')).historyDigest).toBe(before.historyDigest);await capture(buyer,'states-01-archivada',chat(buyer).locator('main'));
   await send(buyer,'Se puede conversar mientras está archivada');expect((await detail(buyer,c.id)).closed).toBe(false);await state(buyer,'Restaurar','archive');await chat(buyer).getByLabel('Archivadas',{exact:true}).uncheck();await expect(chat(buyer).locator('nav button').filter({hasText:g.productName})).toBeVisible();await capture(buyer,'states-02-restaurada',chat(buyer).locator('nav'));
  });
  await test.step('A6 — Cerrar conserva historia y bloquea envío para ambos hasta reapertura',async()=>{
   await inbox(seller,true);await select(seller,g.productName);await state(buyer,'Cerrar conversación','closed');await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toBeDisabled();await expect(chat(buyer).getByRole('button',{name:'Enviar mensaje',exact:true})).toBeDisabled();
   await seller.bringToFront();await expect(chat(seller).getByRole('button',{name:'Reabrir conversación',exact:true})).toBeVisible({timeout:20000});await expect(chat(seller).getByRole('button',{name:'Enviar mensaje',exact:true})).toBeDisabled();await expect(texts(seller)).toHaveCount(2);await capture(seller,'states-03-cerrada',chat(seller).locator('main'));
   await state(seller,'Reabrir conversación','closed');await buyer.bringToFront();await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toBeEnabled({timeout:20000});await send(buyer,'Mensaje posterior a la reapertura');await capture(buyer,'states-04-reabierta',chat(buyer).locator('main'));
   expect(await witness('states','states-final')).toMatchObject({threads:1,messages:3,closed:0,buyerArchived:0,notifications:3});
  });
 }finally{await context.close();}
});

test('CU10 restricted: cuenta activa restringida consulta historial pero no envía',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'restricted');try{
  const c=await start(buyer,g);await send(buyer,'Historial previo a la restricción');await inbox(seller,true);await select(seller,g.productName);await send(seller,'Respuesta previa disponible');const before=await witness('restricted','restricted-before');action('restrict','restricted');
  try{await test.step('A5/RF103 — Restricción propia mantiene sesión e historia y rechaza envío con403',async()=>{
   await home(buyer);await inbox(buyer);await select(buyer,g.productName);await expect(texts(buyer)).toHaveCount(2);const result=await send(buyer,'Este envío debe bloquearse',403);expect(await result.json()).toMatchObject({code:'MESSAGE_RESTRICTED'});await expect(chat(buyer).getByRole('alert')).toContainText('Puedes consultar el historial');await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toHaveValue('Este envío debe bloquearse');expect((await history(buyer,c.id)).length).toBe(2);const after=await witness('restricted','restricted-denied');expect(after).toMatchObject({messages:2,notifications:2});expect(after.historyDigest).toBe(before.historyDigest);await capture(buyer,'restricted-01-historial-y-rechazo',chat(buyer).getByRole('alert'));await evidence('restricted-scope',{activeAccountCommunicationRestricted:true,historyReadable:true,sendRejected403:true,inactiveAccountReadNotClaimed:true,proactiveDisabledButtonNotClaimed:true});
  });}finally{action('unrestrict','restricted');}
 }finally{await context.close();}
});

test('CU10 report: reporte de mensaje con original preservado y evidencia para soporte',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'report');try{
  const c=await start(buyer,g);await inbox(seller,true);await select(seller,g.productName);await send(seller,'Mensaje sintético que se reportará como evidencia');await buyer.bringToFront();await expect(texts(buyer)).toHaveCount(1,{timeout:20000});
  await test.step('A8/RF104 — Radicar reporte mediante el formulario existente sin modificar CU20',async()=>{
   const report=chat(buyer).locator('article app-reportar-contenido');await report.getByRole('button',{name:'Reportar mensaje',exact:true}).click();await report.locator('select').selectOption('CONTENIDO_INAPROPIADO');await report.locator('textarea').fill('Descripción sintética del contenido inapropiado para auditoría CU10.');
   const response=buyer.waitForResponse(r=>new URL(r.url()).pathname==='/api/reports'&&r.request().method()==='POST');await report.getByRole('button',{name:'Enviar reporte',exact:true}).click();const result=await response;expect([200,201]).toContain(result.status());const saved=await result.json();await expect(report.getByRole('status')).toContainText('Reporte radicado');await capture(buyer,'report-01-radicado',report.getByRole('status'));
   const support=await browser.newContext({baseURL:process.env['SUST_BASE_URL'],viewport:{width:1440,height:1000},serviceWorkers:'block'});try{
    const p=await support.newPage();await login(p,fixture.support,'SOPORTE');const res=await p.request.get(`/api/support/moderation/cases/${saved.caseId}`);expect(res.status()).toBe(200);const data=await res.json();expect(data).toMatchObject({contentType:'MENSAJE',contentId:saved.contentId});expect(data.content.text).toBe('Mensaje sintético que se reportará como evidencia');expect(data.content.attributes.conversationId).toBe(String(c.id));expect((await history(buyer,c.id))[0].text).toBe(data.content.text);
    await evidence('report-support',{reportPersisted:true,caseId:saved.caseId,conversationId:c.id,originalTextPreserved:true,supportAuthenticatedRead200:true,wholeConversationReportNotClaimed:true});expect(await witness('report','report-persisted')).toMatchObject({messages:1,reports:1});
   }finally{await support.close();}
  });
 }finally{await context.close();}
});

test('CU10 external: notificación externa fallida no oculta mensaje ni aviso interno',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'external');try{
  const c=await start(buyer,g);
  await test.step('A9 — Adaptador externo de prueba UNAVAILABLE registra fallo después del commit',async()=>{await send(buyer,'Mensaje disponible aunque el aviso externo falle');await expect.poll(async()=>(await witness('external','external-failed')).externalFailed).toBe(1);expect(await witness('external','external-atomic')).toMatchObject({messages:1,notifications:1,notificationLinksCorrect:1,externalFailed:1});await capture(buyer,'external-01-mensaje-confirmado',chat(buyer).locator('main'));});
  await test.step('RF041 — Vendedor consulta el aviso interno y abre el mensaje persistido',async()=>{
   await seller.getByRole('button',{name:'Abrir notificaciones',exact:true}).click();const item=seller.locator('app-notificaciones li').filter({hasText:g.productName});await expect(item).toContainText('Nuevo mensaje');await capture(seller,'external-02-aviso-interno',item);await item.getByRole('button',{name:'Abrir conversación',exact:true}).click();await expect(texts(seller)).toHaveText(['Mensaje disponible aunque el aviso externo falle']);await capture(seller,'external-03-vendedor-consulta',chat(seller).locator('main'));expect((await history(seller,c.id)).length).toBe(1);await evidence('external-scope',{provider:'existing simulated adapter',mode:'UNAVAILABLE',externalFailedWithAttempt:true,internalNotificationAndMessagePreserved:true,realProductionProviderNotUsed:true});
  });
 }finally{await context.close();}
});

test('CU10 failure: fallo SQL real conserva borrador sin entrega falsa y permite reintento',async({page,browser})=>{
 const {g,buyer,context}=await participants(page,browser,'failure');try{
  await start(buyer,g);await send(buyer,'Mensaje anterior que debe conservarse');const before=await witness('failure','failure-before');action('fail-on','failure');
  try{await test.step('A10 — Constraint de prueba rechaza persistencia; no aparece como entregado',async()=>{
   const response=await send(buyer,'CU10 fallo de persistencia',500);expect(response.status()).toBe(500);await evidence('failure-http',{status:response.status(),method:'POST',path:new URL(response.url()).pathname,ownDatabaseConstraint:true});await expect(chat(buyer).getByRole('alert')).toContainText('borrador se conserva');await expect(chat(buyer).getByLabel('Mensaje',{exact:true})).toHaveValue('CU10 fallo de persistencia');await expect(texts(buyer)).toHaveText(['Mensaje anterior que debe conservarse']);const after=await witness('failure','failure-not-delivered');expect(after).toMatchObject({messages:1,notifications:1});expect(after.historyDigest).toBe(before.historyDigest);await capture(buyer,'failure-01-sin-entrega-falsa',chat(buyer).getByRole('alert'));
  });}finally{action('fail-off','failure');}
  await test.step('A10 — Retirar fallo y reintentar por UI conserva un solo mensaje nuevo',async()=>{await send(buyer,'CU10 fallo de persistencia');await expect(texts(buyer)).toHaveCount(2);expect(await witness('failure','failure-retry')).toMatchObject({messages:2,notifications:2});await capture(buyer,'failure-02-reintento',chat(buyer).locator('main'));});
 }finally{await context.close();}
});

test('CU10 security: adicional de cuenta ajena y ausencia de edición o borrado',async({page,browser})=>{
 const {g,buyer,context}=await participants(page,browser,'security');try{
  const c=await start(buyer,g);await send(buyer,'Mensaje privado para verificar integridad');const before=await witness('security','security-before');const m=(await history(buyer,c.id))[0];const other=await browser.newContext({baseURL:process.env['SUST_BASE_URL'],viewport:{width:1440,height:1000},serviceWorkers:'block'});
  try{await test.step('Adicional — Cuenta ajena no lista ni consulta conversaciones o mensajes de otros',async()=>{const p=await other.newPage();await login(p,fixture.stranger);await inbox(p);await expect(chat(p).locator('nav button')).toHaveCount(0);expect(await(await p.request.get('/api/conversations')).json()).toEqual([]);for(const path of [`/api/conversations/${c.id}`,`/api/conversations/${c.id}/messages`])expect((await p.request.get(path)).status()).toBe(404);await capture(p,'security-01-cuenta-ajena',chat(p));});}finally{await other.close();}
  await test.step('RF039 — API no ofrece modificar/borrar mensaje enviado e historia permanece igual',async()=>{for(const method of ['PUT','DELETE']){const r=await write(buyer,`/api/conversations/${c.id}/messages/${m.id}`,{text:'Cambio prohibido'},method);expect(r.status()).toBeGreaterThanOrEqual(400);expect(r.status()).toBeLessThan(500);}const after=await witness('security','security-unchanged');expect(after.historyDigest).toBe(before.historyDigest);await expect(chat(buyer).getByRole('button',{name:/^(Editar mensaje|Eliminar mensaje)$/})).toHaveCount(0);await evidence('security-extra',{foreignConversationAndHistory404:true,foreignListEmpty:true,editAndDeleteRejected:true,historyUnchanged:true,additionalSecurityCheck:true});});
 }finally{await context.close();}
});

test('CU10 concurrency: seis envíos UI simultáneos sin omisiones ni duplicados',async({page,browser})=>{
 const {g,buyer,seller,context}=await participants(page,browser,'concurrency');try{
  const c=await start(buyer,g);await inbox(seller,true);await select(seller,g.productName);
  await test.step('Regresión breve — Tres rondas comprador/vendedor y recepción reconciliada',async()=>{
   for(let round=1;round<=3;round++)await Promise.all([send(buyer,'Concurrente comprador '+round),send(seller,'Concurrente vendedor '+round)]);
   const rows=await history(buyer,c.id);expect(rows).toHaveLength(6);expect(new Set(rows.map((m:any)=>m.id)).size).toBe(6);const expected=rows.map((m:any)=>m.text);
   for(const [p,role] of [[buyer,'comprador'],[seller,'vendedor']] as const){await p.bringToFront();await expect(texts(p)).toHaveText(expected,{timeout:20000});await capture(p,'concurrency-01-'+role,chat(p).locator('main'));}
   expect(await witness('concurrency','concurrency-persisted')).toMatchObject({messages:6,notifications:6,notificationLinksCorrect:6});await evidence('concurrency-real',{rounds:3,messages:6,uiHistoriesEqualServerChronologicalHistory:true,duplicates:0,omissions:0,productionPollingChanged:false,performanceMeasurement:false});
  });
 }finally{await context.close();}
});
