// Ejecuta AppComponent real; sustituye únicamente DI, decoradores y transporte.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const ts = require('typescript');
const rx = require('rxjs');
function fixture() {
  let account = { accountId: 1, activeRole: 'COMPRADOR' };
  const effects = [], requests = [], redirects = [];
  const auth = { cuenta: () => account, sesionFinalizada: () => false, restaurar() {} };
  const payment = { startCheckout: () => { const subject = new rx.Subject(); requests.push(subject); return subject; } };
  const generic = new Proxy({}, { get: () => () => rx.of([]) });
  const tokens = new Map();
  const token = name => { if (!tokens.has(name)) { const t = function() {}; Object.defineProperty(t,'name',{value:name}); tokens.set(name,t); } return tokens.get(name); };
  const modules = {
    '@angular/core': { Component: () => c => c, effect: fn => { effects.push(fn); fn(); }, inject: t => t.name === 'AuthService' ? auth : t.name === 'PaymentService' ? payment : generic },
    '@angular/core/rxjs-interop': { toObservable: () => rx.of(account) }, rxjs: rx,
    ionicons: { addIcons() {} }
  };
  const exports = {};
  runInNewContext(ts.transpileModule(readFileSync('src/app/app.component.ts','utf8'),{
    compilerOptions:{ module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,experimentalDecorators:true }
  }).outputText, { exports, require: name => modules[name] ?? new Proxy({}, { get: (_, key) => token(String(key)) }),
    window: { location: { assign: value => redirects.push(value) } }, URL, console });
  const c = new exports.AppComponent();
  c.mostrarCheckout = true; c.addressId = 8; c.checkoutPreview = { total: 100 };
  return { c, requests, redirects, change: value => { account=value; effects.forEach(fn=>fn()); } };
}
function purchaseError(f) { f.c.confirmarCompra(); f.requests[0].error({error:{detail:'Error exclusivo de A'}}); assert.equal(f.c.compraError,'Error exclusivo de A'); }
for (const [name,next] of [['A → B',{accountId:2,activeRole:'COMPRADOR'}],['logout',null],['cambio de rol',{accountId:1,activeRole:'VENDEDOR'}]]) {
  test('limpia el error al cambiar identidad: '+name,()=>{const f=fixture();purchaseError(f);f.change(next);assert.equal(f.c.compraError,'');});
}
test('conserva un error legítimo ante una actualización de la misma identidad',()=>{
 const f=fixture();purchaseError(f);f.change({accountId:1,activeRole:'COMPRADOR',email:'actualizado@example.test'});assert.equal(f.c.compraError,'Error exclusivo de A');
});
test('cerrar checkout elimina el mensaje y no cierra la sesión',()=>{
 const f=fixture();purchaseError(f);f.c.cerrarCheckout();assert.equal(f.c.compraError,'');assert.equal(f.c.mostrarCheckout,false);
});
for(const outcome of ['error','redirect']) test('ignora respuesta tardía de A tras iniciar B: '+outcome,()=>{
 const f=fixture();f.c.confirmarCompra();f.change(null);f.change({accountId:2,activeRole:'COMPRADOR'});
 if(outcome==='error')f.requests[0].error({error:{detail:'Respuesta tardía de A'}});
 else f.requests[0].next({checkoutUrl:'https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=A'});
 assert.equal(f.c.compraError,'');assert.equal(f.redirects.length,0);
});
test('cerrar y reabrir checkout invalida el error de la solicitud anterior',()=>{
 const f=fixture();f.c.confirmarCompra();f.c.cerrarCheckout();f.c.continuarCompra();f.requests[0].error({error:{detail:'Solicitud anterior'}});assert.equal(f.c.compraError,'');
});
test('la respuesta vigente conserva el comportamiento de navegación',()=>{
 const f=fixture();f.c.confirmarCompra();f.requests[0].next({checkoutUrl:'https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=actual'});assert.equal(f.redirects.length,1);
});
for (const panel of ['mostrarCarrito', 'mostrarCheckout']) test('H09 pérdida de autenticación cierra únicamente el panel visual: '+panel,()=>{
 const f=fixture(); f.c.mostrarCarrito=false; f.c.mostrarCheckout=false; f.c[panel]=true;
 f.change(null); assert.equal(f.c[panel],false);
});
