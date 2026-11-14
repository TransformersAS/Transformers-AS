const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const ts = require('typescript');
const rx = require('rxjs');
function fixture(initial = { accountId: 1, activeRole: 'COMPRADOR' }) {
  let account = initial, effect;
  const reads = [], closes = [];
  const auth = { cuenta: () => account };
  const payment = {
    checkoutStatus: id => { const s = new rx.Subject(); reads.push({id,s}); return s; },
    refreshCheckout: id => { const s = new rx.Subject(); reads.push({id,s}); return s; },
    cancelCheckout: id => { const s = new rx.Subject(); closes.push({id,s}); return s; }
  };
  class AuthService {} class PaymentService {} class CarritoService {}
  const modules = {
    '@angular/core': { Component: () => c => c, effect: fn => { effect=fn;fn(); }, inject: token => token===AuthService?auth:token===PaymentService?payment:{ refrescar() {} } },
    '@angular/common': {}, rxjs: rx,
    '../../core/services/auth.service': { AuthService }, '../../carrito/services/carrito.service': { CarritoService },
    '../services/payment.service': { PaymentService }
  };
  const exports = {}, history = [];
  runInNewContext(ts.transpileModule(readFileSync('src/app/checkout/components/checkout-status.component.ts','utf8'),{
    compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,experimentalDecorators:true}
  }).outputText,{exports,require:name=>modules[name],URL,URLSearchParams,window:{location:{search:'?checkout=abc',href:'http://localhost/?checkout=abc'},history:{replaceState:(...args)=>history.push(args)}}});
  const c = new exports.CheckoutStatusComponent();
  return { c, reads, closes, history, change: value => { account=value;effect(); } };
}
const pending={id:'abc',status:'PENDING',orderId:null,total:10,checkoutUrl:'https://example.test'};
test('confirmar y regresar no cancela; una acción no genera dos solicitudes',()=>{
 const {c,reads,closes}=fixture();reads[0].s.next(pending);assert.equal(c.canCancel,true);
 c.confirming=true;c.back();c.cancel();assert.equal(closes.length,0);
 c.confirming=true;c.cancel();c.cancel();assert.equal(closes.length,1);assert.equal(c.busy,true);
 closes[0].s.next({id:'abc',status:'CANCELLED',cancelledAt:'2026-10-31T10:00:00',financialStatus:'PENDING_RESOLUTION'});
 assert.equal(c.checkout.checkoutUrl,null);assert.equal(c.canCancel,false);c.cancel();assert.equal(closes.length,1);
});
for(const [state,message] of [['REQUIRES_VERIFICATION',/verificarse/],['PENDING_RESOLUTION',/pendiente/],['REQUIRES_REVIEW',/revisión/],['REFUNDED',/confirmada/]]) {
 test('recupera seguimiento persistido '+state,()=>{const {c,reads}=fixture();reads[0].s.next({...pending,status:'CANCELLED',financialStatus:state});assert.match(c.financialMessage,message);assert.equal(c.canCancel,false);});
}
for(const status of [401,403,404,409,0]) test('maneja error '+status,()=>{
 const {c,reads,closes}=fixture();reads[0].s.next(pending);c.confirming=true;c.cancel();closes[0].s.error({status});assert.equal(c.busy,status===409);assert.ok(c.error);
 if(status===409){assert.equal(reads.length,2);reads[1].s.next({...pending,status:'APPROVED',orderId:7});assert.equal(c.canCancel,false);}
 if(status===0){c.load();assert.equal(reads.length,2);}
});
for(const next of [null,{accountId:2,activeRole:'COMPRADOR'},{accountId:1,activeRole:'VENDEDOR'}]) test('limpia datos e identificador al cambiar identidad '+JSON.stringify(next),()=>{
 const {c,reads,change,history}=fixture();change(next);reads[0].s.next(pending);assert.equal(c.checkout,null);assert.equal(c.id,null);assert.equal(history.length,1);
});
test('login desde URL recupera mediante autorización backend',()=>{const {c,reads,change}=fixture(null);assert.equal(reads.length,0);change({accountId:1,activeRole:'COMPRADOR'});assert.equal(reads.length,1);reads[0].s.next({...pending,status:'CANCELLED',financialStatus:'REQUIRES_REVIEW'});assert.equal(c.checkout.status,'CANCELLED');});

for (const updated of [
  {...pending,status:'APPROVED',orderId:7},
  {...pending,status:'CANCELLED',checkoutUrl:null,financialStatus:'PENDING_RESOLUTION'}
]) test('actualizar a '+updated.status+' cierra una confirmación que ya no corresponde',()=>{
  const {c,reads,closes}=fixture();reads[0].s.next(pending);
  c.confirming=true;c.load();reads[1].s.next(updated);
  assert.equal(c.confirming,false);assert.equal(c.canCancel,false);
  assert.equal(c.checkout.status,updated.status);assert.equal(c.checkout.orderId,updated.orderId);
  c.cancel();assert.equal(closes.length,0);
  if(updated.status==='CANCELLED'){
    assert.equal(c.checkout.financialStatus,'PENDING_RESOLUTION');
    assert.match(c.financialMessage,/pendiente/);assert.doesNotMatch(c.financialMessage,/Devolución confirmada/);
  }
});
