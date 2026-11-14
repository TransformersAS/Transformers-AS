'use strict';
// Regresión funcional con HTTP, Angular y Chromium reales. Sin interceptar respuestas.
const {chromium}=require('playwright');const fs=require('node:fs');const crypto=require('node:crypto');
const {performance}=require('node:perf_hooks');
const out={checks:[],complete:false,started_utc:new Date().toISOString()};let browser;
const assert=(value,name)=>{if(!value)throw new Error(name);out.checks.push(name);};
async function wait(fn){const end=performance.now()+15000;while(performance.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error('FUNCTIONAL_WAIT_TIMEOUT');}
async function login(c,email,role){const csrf=await(await c.request.get('/api/auth/csrf')).json();assert((await c.request.post('/api/auth/login',{form:{email,password:process.env.FIXTURE_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status()===204,'LOGIN_'+role);assert((await(await c.request.get('/api/auth/me')).json()).activeRole===role,'ROLE_'+role);}
async function post(c,path,data){const csrf=await(await c.request.get('/api/auth/csrf')).json();return c.request.post(path,{data,headers:{[csrf.headerName]:csrf.token}});}
async function send(c,id,text,key=crypto.randomUUID()){const r=await post(c,`/api/conversations/${id}/messages`,{text,clientKey:key});assert(r.status()===200,'SEND_PERSISTED');return r.json();}
async function open(c,seller){const p=await c.newPage();await p.goto('/');await p.getByRole('button',{name:seller?'Opciones de mi negocio':'Opciones de mis compras',exact:true}).click();await p.getByRole('button',{name:'Conversaciones',exact:true}).click();await p.locator('app-conversaciones nav button').filter({hasText:'Producto alterno RNF020'}).click();await wait(()=>p.locator('app-conversaciones main').isVisible());return p;}
async function uiSend(p,text){await p.getByLabel('Mensaje',{exact:true}).fill(text);await p.getByRole('button',{name:'Enviar mensaje',exact:true}).click();await wait(async()=>await p.getByLabel('Mensaje',{exact:true}).inputValue()==='');}
(async()=>{try{
 browser=await chromium.launch({headless:true});const settings={baseURL:'https://frontend',ignoreHTTPSErrors:true,viewport:{width:1440,height:1080},serviceWorkers:'block'};
 const buyer=await browser.newContext(settings),seller=await browser.newContext(settings),other=await browser.newContext(settings);
 await login(buyer,'rnf020-buyer@example.test','COMPRADOR');await login(seller,'rnf020-seller@example.test','VENDEDOR');await login(other,'rnf020-other@example.test','COMPRADOR');
 const a=(await(await post(buyer,'/api/conversations',{productId:2,orderId:null})).json()).id;
 const b=(await(await post(buyer,'/api/conversations',{productId:1,orderId:null})).json()).id;
 assert((await other.request.get(`/api/conversations/${a}/messages`)).status()===404,'OTHER_ACCOUNT_DENIED');
 await send(buyer,a,'Regresión pendiente 1');await send(buyer,a,'Regresión pendiente 2');
 assert((await(await seller.request.get(`/api/conversations/${a}`)).json()).unread===2,'UNREAD_BEFORE_OPEN');
 const bp=await open(buyer,false),sp=await open(seller,true);
 await wait(async()=>await sp.locator('app-conversaciones article p').count()===2);
 await wait(async()=>(await(await seller.request.get(`/api/conversations/${a}`)).json()).unread===0);out.checks.push('DOM_AND_READ_MARKER');
 for(let round=0;round<3;round++)await Promise.all([uiSend(bp,'Regresión comprador '+round),uiSend(sp,'Regresión vendedor '+round)]);
 await wait(async()=>await bp.locator('app-conversaciones article').count()===8 && await sp.locator('app-conversaciones article').count()===8);
 const history=await(await buyer.request.get(`/api/conversations/${a}/messages`)).json();
 for(const p of [bp,sp]){const text=await p.locator('app-conversaciones article p').allTextContents();assert(JSON.stringify(text)===JSON.stringify(history.map(m=>m.text)),'SIMULTANEOUS_SORTED_NO_DUPLICATES');}
 await bp.getByLabel('Mensaje',{exact:true}).fill('Borrador A');await bp.locator('app-conversaciones nav button').filter({hasText:'Producto RNF020'}).click();await wait(async()=>await bp.locator('app-conversaciones main').textContent().then(t=>t.includes('Producto #1')));
 await bp.getByLabel('Mensaje',{exact:true}).fill('Borrador B');await bp.locator('app-conversaciones nav button').filter({hasText:'Producto alterno RNF020'}).click();await wait(async()=>await bp.getByLabel('Mensaje',{exact:true}).inputValue()==='Borrador A');out.checks.push('DRAFTS_A_B_A');
 const key=crypto.randomUUID();const first=await send(buyer,a,'Idempotencia real',key),duplicate=await send(buyer,a,'Idempotencia real',key);assert(first.id===duplicate.id,'IDEMPOTENCY_REAL');
 await wait(async()=>await sp.locator('app-conversaciones article p').filter({hasText:'Idempotencia real'}).count()===1);
 await bp.getByRole('button',{name:'Cerrar conversación',exact:true}).click();await wait(()=>bp.getByRole('button',{name:'Reabrir conversación',exact:true}).isVisible());assert((await post(seller,`/api/conversations/${a}/messages`,{text:'cerrada',clientKey:crypto.randomUUID()})).status()===409,'CLOSED_REJECTS_SEND');
 await sp.getByRole('button',{name:'Reabrir conversación',exact:true}).waitFor();await sp.getByRole('button',{name:'Reabrir conversación',exact:true}).click();
 await wait(()=>bp.getByRole('button',{name:'Cerrar conversación',exact:true}).isVisible());out.checks.push('CLOSE_REOPEN_REAL');
 assert((await other.request.get(`/api/conversations/${b}`)).status()===404,'SWITCHED_CONVERSATION_ISOLATED');
 out.complete=true;
}catch(e){out.error=/^[A-Z_]+$/.test(e.message)?e.message:'FUNCTIONAL_BROWSER_FAILURE';console.error(out.error);if(browser){let i=0;for(const c of browser.contexts())for(const p of c.pages())await p.screenshot({path:`/results/functional-failure-${++i}.png`}).catch(()=>{});}}
finally{if(browser)await browser.close();out.finished_utc=new Date().toISOString();fs.writeFileSync('/results/functional.json',JSON.stringify(out,null,2));}process.exitCode=out.complete?0:2;})();
