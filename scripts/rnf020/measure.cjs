'use strict';
const {chromium}=require('playwright');
const fs=require('node:fs');
const {performance}=require('node:perf_hooks');
const crypto=require('node:crypto');
const {panelReady,observeMessage,observeNotification}=require('./evidence.cjs');
const count=Number(process.env.EVENTS||100),stage=process.env.STAGE||'measurement';
if(!Number.isInteger(count)||count<1||count>1000||!['smoke','measurement'].includes(stage)||process.env.BASE_URL!=='https://frontend')throw new Error('DISPOSABLE_CONFIGURATION_REQUIRED');
const sendMode=process.env.SEND_MODE||'alternating';
if(!['alternating','simultaneous'].includes(sendMode))throw new Error('SEND_MODE_INVALID');
const output='/results/'+stage+'-raw.json';
const result={stage,send_mode:sendMode,expected_per_direction:count,events:[],complete:false,problems:[],started_utc:new Date().toISOString(),clock:'node:perf_hooks.performance.now',browser_version:null};
let browser;
async function until(fn,timeout=15000){const end=performance.now()+timeout;while(performance.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,50));}throw new Error('PREPARATION_TIMEOUT');}
async function login(context,email,role,id){
 const csrf=await(await context.request.get('/api/auth/csrf')).json();
 const r=await context.request.post('/api/auth/login',{form:{email,password:process.env.FIXTURE_PASSWORD},headers:{[csrf.headerName]:csrf.token}});
 if(r.status()!==204)throw new Error('LOGIN_FAILED');
 const me=await(await context.request.get('/api/auth/me')).json();
 if(me.accountId!==id||me.email!==email||me.activeRole!==role)throw new Error('ACCOUNT_ROLE_ISOLATION_FAILED');
}
async function post(context,path,data){const csrf=await(await context.request.get('/api/auth/csrf')).json();return context.request.post(path,{data,headers:{[csrf.headerName]:csrf.token}});}
async function chat(context,seller){
 const p=await context.newPage();await p.goto('/');
 await p.getByRole('button',{name:seller?'Opciones de mi negocio':'Opciones de mis compras',exact:true}).click();
 await p.getByRole('button',{name:'Conversaciones',exact:true}).click();
 await p.locator('app-conversaciones nav button').filter({hasText:'Producto RNF020'}).click();
 await until(async()=>await p.locator('app-conversaciones main').isVisible());
 await panelReady(p,'app-conversaciones main');
 if(!await p.evaluate(()=>window.isSecureContext && typeof crypto.randomUUID==='function'))throw new Error('SECURE_CONTEXT_CRYPTO_REQUIRED');
 return p;
}
async function inbox(context,seller){
 const p=await context.newPage(),feed={items:[],responses:0,errors:0};
 const path=seller?'/api/seller/notifications':'/api/notifications';
 p.on('response',async response=>{if(new URL(response.url()).pathname!==path)return;try{
   if(response.status()!==200){feed.errors++;return;}
   const body=await response.json();if(!Array.isArray(body.items)){feed.errors++;return;}
   feed.items=body.items;feed.responses++;
 }catch(_){feed.errors++;}});
 await p.goto('/');await p.getByRole('button',{name:'Abrir notificaciones',exact:true}).click();
 await until(async()=>feed.responses>0 && await p.locator('app-notificaciones li').count()===feed.items.length);
 await panelReady(p,'app-notificaciones section');return {page:p,feed};
}
async function event(sender,receiver,inbox,direction,conversation,round){
 const token=`RNF020-${stage}-${direction}-${round}-${crypto.randomUUID()}`;
 const event={direction,round,token,conversation_id:conversation,message_id:null,notification_id:null,
   message_latency_ms:null,notification_latency_ms:null,message_verified:false,notification_verified:false,persisted:false,functional_error:false,errors:[]};
 result.events.push(event);
 let active=true;
 const health=async()=>{
  await Promise.all([panelReady(sender,'app-conversaciones main'),panelReady(receiver,'app-conversaciones main'),panelReady(inbox.page,'app-notificaciones section')]);
 };
 try{
  await health();
  await sender.getByLabel('Mensaje',{exact:true}).fill(token);
  const baseline={count:await inbox.page.locator('app-notificaciones li').count(),ids:new Set(inbox.feed.items.map(n=>n.id))};
  const response=sender.waitForResponse(r=>new URL(r.url()).pathname===`/api/conversations/${conversation}/messages` && r.request().method()==='POST',{timeout:12000}).catch(()=>null);
  const t0=performance.now();event.t0_monotonic_ms=t0;
  const observations=await Promise.allSettled([
   observeMessage(receiver,token,t0),observeNotification(inbox.page,inbox.feed,baseline,conversation,t0),
   sender.getByRole('button',{name:'Enviar mensaje',exact:true}).click({timeout:12000}),response,
  ]);
  const [msg,notice,click,reply]=observations;
  if(msg.status==='fulfilled'){event.message_latency_ms=msg.value.latency;event.message_verified=msg.value.verified;if(msg.value.error)event.errors.push(msg.value.error);}else event.errors.push('MESSAGE_PANEL_OBSERVATION_FAILED');
  if(notice.status==='fulfilled'){event.notification_latency_ms=notice.value.latency;event.notification_verified=notice.value.verified;event.notification_id=notice.value.id||null;event.notification_dom=notice.value.dom||null;if(notice.value.error)event.errors.push(notice.value.error);}else event.errors.push('NOTIFICATION_PANEL_OBSERVATION_FAILED');
  if(click.status==='rejected')event.errors.push('SEND_ACTION_FAILED');
  const responseValue=reply.status==='fulfilled'?reply.value:null;
  event.send_status=responseValue?.status()||null;
  if(responseValue && responseValue.status()>=400)event.send_error=(await responseValue.text()).slice(0,160);
  if(responseValue && responseValue.status()===200){const m=await responseValue.json();event.message_id=m.id;
   if(m.text!==token||m.senderId!==(direction==='buyer_to_seller'?1:2)||m.conversationId!==conversation)event.errors.push('SEND_CONTRACT_INVALID');
  }else event.errors.push('SEND_RESPONSE_FAILED');
  await health();
  const duplicates=await receiver.locator('app-conversaciones article p').filter({hasText:token}).count();
  if(event.message_verified && duplicates!==1)event.errors.push('MESSAGE_DUPLICATE');
  event.functional_error=click.status==='rejected'||!responseValue||responseValue.status()!==200||event.errors.some(e=>e.includes('CONTRACT')||e.includes('DUPLICATE'));
 }catch(_){event.errors.push('PANEL_OR_SESSION_PRECONDITION_FAILED');event.functional_error=true;active=false;}
 fs.writeFileSync(output,JSON.stringify(result,null,2));return active;
}
(async()=>{
 try{
  browser=await chromium.launch({headless:true});result.browser_version=browser.version();
  const settings={baseURL:'https://frontend',ignoreHTTPSErrors:true,viewport:{width:1440,height:1080},timezoneId:'UTC',locale:'es-CO',serviceWorkers:'block'};
  const buyer=await browser.newContext(settings),seller=await browser.newContext(settings),other=await browser.newContext(settings);
  await login(buyer,'rnf020-buyer@example.test','COMPRADOR',1);await login(seller,'rnf020-seller@example.test','VENDEDOR',2);await login(other,'rnf020-other@example.test','COMPRADOR',3);
  const start=await post(buyer,'/api/conversations',{productId:1,orderId:null});if(start.status()!==200)throw new Error('CONVERSATION_PREPARATION_FAILED');
  const conversation=(await start.json()).id;
  if((await other.request.get(`/api/conversations/${conversation}`)).status()!==404 || (await other.request.get(`/api/conversations/${conversation}/messages`)).status()!==404)throw new Error('THIRD_ACCOUNT_ISOLATION_FAILED');
  if((await other.request.get('/api/seller/notifications')).status()!==403)throw new Error('ROLE_BOUNDARY_FAILED');
  const otherNotices=await(await other.request.get('/api/notifications')).json();if(otherNotices.items.length)throw new Error('THIRD_ACCOUNT_NOTIFICATIONS_LEAK');
  result.preflight={identities_and_roles:true,third_account_denied:true,third_account_inbox_empty:true,conversation};
  const buyerPage=await chat(buyer,false),sellerPage=await chat(seller,true),buyerInbox=await inbox(buyer,false),sellerInbox=await inbox(seller,true);
  // Scroll pertenece a preparación; no se desplaza ninguna interfaz tras t0.
  for(let i=1;i<=count;i++){
   await Promise.all([buyerPage,sellerPage].map(p=>p.locator('app-conversaciones').evaluate(el=>{el.scrollTop=el.scrollHeight;})));
   await Promise.all([buyerInbox,sellerInbox].map(x=>x.page.locator('app-notificaciones section').evaluate(el=>{el.scrollTop=0;})));
   const ready=sendMode==='simultaneous'
    ?await Promise.all([event(buyerPage,sellerPage,sellerInbox,'buyer_to_seller',conversation,i),event(sellerPage,buyerPage,buyerInbox,'seller_to_buyer',conversation,i)])
    :[await event(buyerPage,sellerPage,sellerInbox,'buyer_to_seller',conversation,i),await event(sellerPage,buyerPage,buyerInbox,'seller_to_buyer',conversation,i)];
   console.log(`${stage}: ronda ${i}/${count}; mensajes y bandejas observados`);
   if(ready.some(x=>!x))throw new Error('PANEL_OR_SESSION_LOST');
   if(result.events.slice(-2).some(e=>e.errors.length)){
    for(const [name,p] of [['buyer-chat',buyerPage],['seller-chat',sellerPage],['buyer-inbox',buyerInbox.page],['seller-inbox',sellerInbox.page]])await p.screenshot({path:`/results/${stage}-round-${i}-${name}.png`}).catch(()=>{});
   }
   await new Promise(r=>setTimeout(r,50+(i*73)%450));
  }
  result.complete=true;result.poll_response_errors=buyerInbox.feed.errors+sellerInbox.feed.errors;
 }catch(e){result.problems.push(/^[A-Z_]+$/.test(e.message)?e.message:'BROWSER_PREPARATION_OR_EXECUTION_FAILED');
  console.error(String(e.message).replaceAll(process.env.FIXTURE_PASSWORD||'__unused__','[REDACTED]').replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,'[CUENTA]'));
  if(browser){let i=0;for(const context of browser.contexts())for(const page of context.pages())await page.screenshot({path:`/results/${stage}-preparation-failure-${++i}.png`}).catch(()=>{});}
 }
 finally{if(browser)await browser.close();result.finished_utc=new Date().toISOString();fs.writeFileSync(output,JSON.stringify(result,null,2));}
 process.exitCode=result.complete?0:2;
})();
