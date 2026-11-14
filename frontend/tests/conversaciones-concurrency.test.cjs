// Instrumento determinista: ejecuta los métodos TypeScript reales con RxJS y servicios controlados.
// No es evidencia de latencia ni reemplaza las corridas Docker/Chromium reales.
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const ts=require('../node_modules/typescript'),rx=require('../node_modules/rxjs');
const source=process.env.CONVERSACIONES_SOURCE||path.resolve(__dirname,'../src/app/mensajeria/components/conversaciones.component.ts');
const code=ts.transpileModule(fs.readFileSync(source,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,experimentalDecorators:true}}).outputText;
const thread=id=>({id,productId:1,closed:false,archived:false,unread:1,readThrough:0});
const message=(id,senderId=2)=>({id,senderId,conversationId:1,text:'mensaje '+id,createdAt:'2026-11-14T00:00:00',image:false});
function setup(){
 const state={rows:[],calls:[],reads:[],sends:[],initial:[],timer:null};
 const service={obtenerConversaciones:()=>rx.of([]),detalle:id=>rx.of(thread(id)),
  historial:(id,after=0,before=Number.MAX_SAFE_INTEGER)=>{state.calls.push({id,after,before});const all=state.rows.filter(m=>m.id>after&&m.id<before).sort((a,b)=>a.id-b.id);return rx.of(after?all.slice(0,50):all.slice(-50));},
  leer:(id,through)=>{state.reads.push({id,through});return rx.of(null);},
  enviar:()=>{const s=new rx.Subject();state.sends.push(s);return s;},seleccionada:()=>null};
 const tokens={MensajeriaService:class{},AuthService:class{}};const auth={cuenta:()=>({accountId:1})};
 const exports={};vm.runInNewContext(code,{exports,require:name=>{
  if(name==='rxjs')return {...rx,forkJoin:input=>rx.forkJoin(Object.fromEntries(Object.entries(input)))};
  if(name==='@angular/core')return {Component:()=>x=>x,inject:t=>t===tokens.MensajeriaService?service:auth,effect:()=>{}};
  if(name.includes('mensajeria.service'))return {MensajeriaService:tokens.MensajeriaService};
  if(name.includes('auth.service'))return {AuthService:tokens.AuthService};
  return new Proxy({},{get:()=>class{}});
 },window:{setInterval:(fn,ms)=>{state.timer={fn,ms};return 1;},clearInterval:()=>{}},document:{hidden:false},crypto:{randomUUID:()=> 'deterministic-key-123456'},console});
 const component=new exports.ConversacionesComponent();component.actual=thread(1);return {component,service,state};
}
const ids=c=>Array.from(c.mensajes,m=>m.id);
test('un remoto pendiente no se salta al confirmar primero el envío propio',()=>{
 const {component:c,state}=setup();c.texto='propio';c.enviar();state.rows=[message(1),message(2,1)];state.sends[0].next(message(2,1));state.sends[0].complete();
 c.actualizar();assert.deepEqual(ids(c),[1,2]);assert.equal(c.texto,'');assert.equal(c.clave,null);
});
test('recupera confirmaciones de IDs menores que aparecen después',()=>{
 const {component:c,state}=setup();state.rows=[message(2)];c.abrir(1);state.rows=[message(1),message(2)];c.actualizar();assert.deepEqual(ids(c),[1,2]);
});
test('recupera más de una página, mantiene orden y deduplica envíos rápidos',()=>{
 const {component:c,state}=setup();c.abrir(1);state.rows=Array.from({length:125},(_,i)=>message(i+1));c.mensajes=[message(125,1),message(124,1)];c.actualizar();c.actualizar();assert.deepEqual(ids(c),Array.from({length:125},(_,i)=>i+1));
 assert.equal(state.timer.ms,3000);assert.ok(state.calls.length>=7);
});
test('polling de A no puede aplicarse tras cambiar A → B → A',()=>{
 const {component:c,service,state}=setup();c.abrir(1);const old=new rx.Subject();service.historial=()=>old;c.actualizar();
 service.historial=()=>rx.of([]);c.abrir(2);c.abrir(1);old.next([message(99)]);old.complete();assert.deepEqual(ids(c),[]);
});
test('una respuesta antigua de lectura no altera la conversación nueva',()=>{
 const {component:c,service,state}=setup();const ack=new rx.Subject();service.leer=()=>ack;state.rows=[message(1)];c.abrir(1);state.rows=[];c.abrir(2);ack.next(null);ack.complete();assert.equal(c.actual.id,2);assert.equal(c.actual.readThrough,0);
});
test('borradores y clave idempotente se conservan ante error y cambio de conversación',()=>{
 const {component:c,state}=setup();c.texto='borrador';c.enviar();const key=c.clave;state.sends[0].error({status:503});c.abrir(2);c.texto='otro';c.abrir(1);assert.equal(c.texto,'borrador');assert.equal(c.clave,key);
});
test('un envío propio no adelanta lectura de un remoto sin recuperar',()=>{
 const {component:c,state}=setup();c.abrir(1);c.texto='propio';c.enviar();state.sends[0].next(message(2,1));state.sends[0].complete();assert.equal(state.reads.length,0);
 state.rows=[message(1),message(2,1)];c.actualizar();assert.equal(state.reads.at(-1).through,2);assert.equal(c.actual.unread,0);
});
test('polling anterior al envío propio no pierde el remoto en la siguiente reconciliación',()=>{
 const {component:c,service,state}=setup();const delayed=new rx.Subject();const history=service.historial;service.historial=()=>delayed;
 c.actualizar();c.texto='propio';c.enviar();state.sends[0].next(message(2,1));state.sends[0].complete();delayed.next([]);delayed.complete();
 state.rows=[message(1),message(2,1)];service.historial=history;c.actualizar();assert.deepEqual(ids(c),[1,2]);
});
test('confirmaciones de lectura fuera de orden no hacen retroceder readThrough',()=>{
 const {component:c,service,state}=setup();const acks=[];service.leer=()=>{const s=new rx.Subject();acks.push(s);return s;};
 state.rows=[message(1)];c.abrir(1);state.rows.push(message(2));c.actualizar();acks[1].next(null);acks[1].complete();acks[0].next(null);acks[0].complete();assert.equal(c.actual.readThrough,2);
});
