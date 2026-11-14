'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {performance}=require('node:perf_hooks');
const {percentile,summarize,correlated,panelReady,observeMessage,observeNotification,writeReport}=require('../evidence.cjs');
const {verify}=require('../finalize.cjs');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
function event(i=1){return {direction:'buyer_to_seller',token:'RNF020-unique-'+i,message_id:i,conversation_id:9,notification_id:i,persisted:true,message_verified:true,notification_verified:true,message_latency_ms:3000,notification_latency_ms:2000,functional_error:false,errors:[]};}
test('percentiles por rango cercano y ausencia de datos',()=>{assert.equal(percentile([], .95),null);assert.equal(percentile(Array.from({length:100},(_,i)=>i+1),.95),95);assert.equal(percentile([9,1,4],.5),4);});
test('los fallos permanecen en el denominador y los grupos no se mezclan',()=>{
 const events=Array.from({length:100},(_,i)=>event(i));events.slice(0,6).forEach(e=>{e.notification_verified=false;e.notification_latency_ms=null;});
 const g=summarize(events,100,true);assert.equal(g[0].state,'Cumple');assert.equal(g[1].within_5s_percent,94);assert.equal(g[1].state,'No cumple');assert.equal(g[1].failures,6);assert.equal(g[1].functional_error_rate,.06);assert.equal(g[2].state,'No evaluado');
});
test('95 por ciento inclusive en 5000 ms; no basta P95 observado favorable',()=>{
 const events=Array.from({length:100},(_,i)=>event(i));events.forEach(e=>e.message_latency_ms=5000);events.slice(0,5).forEach(e=>e.message_verified=false);
 assert.equal(summarize(events,100,true)[0].state,'Cumple');events[5].message_verified=false;
 const g=summarize(events,100,true)[0];assert.equal(g.p95_ms,5000);assert.equal(g.state,'No cumple');
});
test('cero, parcial y smoke no pueden acreditar RNF',()=>{for(const [events,n,complete,smoke] of [[[],100,false,false],[[event()],100,true,false],[Array.from({length:5},(_,i)=>event(i)),5,true,true]])assert.ok(summarize(events,n,complete,smoke).every(g=>g.state==='No evaluado'));});
test('correlación exige evento, identidad, conversación y notificación únicos',()=>{
 const e=event(),row={message_id:1,text:e.token,sender_id:1,conversation_id:9,event_key:'conversation-message-1',recipient_type:'STORE',recipient_id:1,notification_id:1};
 assert.equal(correlated(e,[row]),true);assert.equal(correlated(e,[row,row]),false);
 for(const [key,value] of [['sender_id',2],['conversation_id',10],['recipient_id',2],['notification_id',2],['event_key','old'],['text','historical']])assert.equal(correlated(e,[{...row,[key]:value}]),false);
});
test('respaldo persistido mantiene mensajes separados de notificación no correlacionada',()=>{
 const e=event();verify([e],{messages:[{message_id:1,conversation_id:9,sender_id:1,text:e.token}],notifications:[]});
 assert.equal(e.persisted,true);assert.equal(e.message_verified,true);assert.equal(e.notification_verified,false);
});
test('HTML y JSON sin cookies, contraseñas ni cabeceras de sesión del instrumento',()=>{
 const d=fs.mkdtempSync(path.join(os.tmpdir(),'rnf020-'));
 try{writeReport(d,{expected_per_direction:100,complete:false,problems:['<script>unsafe</script>'],password:'private-value',cookie:'SESSION=secret',email:'private@personal.example'},[]);
 const h=fs.readFileSync(d+'/rnf020-executive.html','utf8');assert.ok(!h.includes('<script>'));assert.ok(h.includes('&lt;script&gt;'));assert.ok(!h.includes('class="pass"'));assert.ok(h.includes('No evaluado'));
 const doc=JSON.parse(fs.readFileSync(d+'/rnf020-summary.json'));assert.equal(doc.state,'No evaluado');assert.ok(!JSON.stringify(doc).includes('private-value'));assert.ok(!JSON.stringify(doc).includes('private@personal.example'));assert.ok(!JSON.stringify(doc).includes('SESSION=secret'));assert.ok(!JSON.stringify(doc).includes('SESSION='));
 }finally{fs.rmSync(d,{recursive:true});}
});
test('un escenario favorable no oculta un fallo simultáneo documentado',()=>{
 const d=fs.mkdtempSync(path.join(os.tmpdir(),'rnf020-known-'));
 try{const events=['buyer_to_seller','seller_to_buyer'].flatMap(direction=>Array.from({length:100},(_,i)=>({...event(i),direction})));
 const doc=writeReport(d,{expected_per_direction:100,complete:true,send_mode:'alternating',known_concurrent_issue:'Fallo de cursor persistido y observado'},events);
 assert.equal(doc.scenario_state,'Cumple');assert.equal(doc.state,'No evaluado');assert.ok(doc.groups.every(g=>g.state==='Cumple'));
 }finally{fs.rmSync(d,{recursive:true});}
});
test('Chromium observa DOM real y rechaza panel ausente, oculto o pestaña oculta',async()=>{
 const {chromium}=require('playwright');const browser=await chromium.launch({headless:true});
 try{const page=await browser.newPage();await page.setContent('<app-conversaciones><main></main></app-conversaciones>');
 await panelReady(page,'app-conversaciones main').then(()=>assert.fail('panel vacío no tiene altura'),()=>{});
 await page.setContent('<app-conversaciones style="display:block;width:700px;height:500px"><main style="height:400px"><article><p>RNF020-DOM</p></article></main></app-conversaciones>');
 await panelReady(page,'app-conversaciones main');const visible=await observeMessage(page,'RNF020-DOM',performance.now(),300);assert.equal(visible.verified,true);
 const missing=await observeMessage(page,'RNF020-absent',performance.now(),100);assert.equal(missing.verified,false);
 await page.locator('main').evaluate(el=>el.insertAdjacentHTML('beforeend','<article><p>RNF020-DOM</p></article>'));
 assert.equal((await observeMessage(page,'RNF020-DOM',performance.now(),100)).verified,false);
 await page.locator('article').last().evaluate(el=>el.remove());
 await page.locator('article').evaluate(el=>{el.style.position='absolute';el.style.top='2000px';});
 assert.equal((await observeMessage(page,'RNF020-DOM',performance.now(),100)).verified,false);
 await page.locator('main').evaluate(el=>el.style.display='none');await assert.rejects(()=>panelReady(page,'app-conversaciones main'));
 await page.evaluate(()=>Object.defineProperty(document,'visibilityState',{get:()=> 'hidden'}));await assert.rejects(()=>panelReady(page,'app-conversaciones main'));
 }finally{await browser.close();}
});
test('notificación requiere una fila nueva visible y correlación con polling pasivo',async()=>{
 const {chromium}=require('playwright');const browser=await chromium.launch({headless:true});
 try{const page=await browser.newPage();const html='<app-notificaciones><section style="height:600px;width:700px"><ul><li><h3>Nuevo mensaje</h3><p>Fixture</p><p>13/11/2026 22:00:00</p><button>Abrir conversación</button></li></ul></section></app-notificaciones>';await page.setContent(html);
 const feed={items:[{id:5,title:'Nuevo mensaje',message:'Fixture',createdAt:'2026-11-13T22:00:00',conversationId:9}]};
 const observed=await observeNotification(page,feed,{count:0,ids:new Set()},9,performance.now(),500);assert.equal(observed.verified,true);assert.equal(observed.id,5);
 assert.equal((await observeNotification(page,feed,{count:0,ids:new Set()},10,performance.now(),100)).verified,false);
 const historical=await observeNotification(page,feed,{count:1,ids:new Set([5])},9,performance.now(),100);assert.equal(historical.verified,false);
 feed.items.push({...feed.items[0],id:6});const ambiguous=await observeNotification(page,feed,{count:0,ids:new Set()},9,performance.now(),100);assert.equal(ambiguous.verified,false);
 }finally{await browser.close();}
});
