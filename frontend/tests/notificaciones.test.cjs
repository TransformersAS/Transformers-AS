const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const ts = require('typescript');
const rx = require('rxjs');
function fixture(answer) {
  class AuthService {} class NotificacionesService {}
  let account = { accountId: 1, activeRole: 'COMPRADOR' }, effect, cleanup;
  const calls = [];
  const service = { listar: (seller,page) => { calls.push({ seller,page });return answer(seller,page,calls.length); } };
  const exports = {};
  const modules = {
    '@angular/core': { Component: () => t => t, Output: () => () => {}, EventEmitter: class {},
      inject: token => token === AuthService ? { cuenta: () => account } : service,
      effect: callback => { effect = callback; callback(fn => cleanup = fn); } },
    '@angular/common': {}, rxjs: rx,
    '../core/services/auth.service': { AuthService }, './notificaciones.service': { NotificacionesService },
    '../pedidos/components/mis-pedidos.component': {}, '../panel-vendedor/components/pedidos-recibidos.component': {}
  };
  const js = ts.transpileModule(readFileSync(resolve(__dirname,'../src/app/notificaciones/notificaciones.component.ts'),'utf8'),
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, experimentalDecorators: true } }).outputText;
  runInNewContext(js,{ exports,require: key => modules[key] });
  return { c: new exports.NotificacionesComponent(),calls,change: value => { cleanup?.();account = value;effect(fn => cleanup = fn); } };
}
test('pagina sucesivamente y conserva avisos sin duplicados', () => {
  const { c,calls } = fixture((s,p) => rx.of({ items: [{ id: p+1 }],hasMore: p===0 }));
  c.cargar(); assert.equal(c.avisos.length,2);assert.equal(c.hayMas,false);assert.equal(calls[1].page,1);
});
test('el error no avanza de página y permite reintentar', () => {
  const { c,calls } = fixture((s,p,n) => n===1 ? rx.throwError(() => Error('private')) : rx.of({items:[],hasMore:false}));
  assert.match(c.error,/No se pudieron/);assert.doesNotMatch(c.error,/private/);
  c.cargar();assert.equal(calls[1].page,0);assert.equal(c.error,'');
});
test('logout cancela la respuesta pendiente y limpia los avisos y pedido', () => {
  const pending = new rx.Subject();const { c,change } = fixture(() => pending);
  c.avisos=[{id:1}];c.pedido=42;change(null);pending.next({items:[{id:999}],hasMore:false});
  assert.equal(c.avisos.length,0);assert.equal(c.pedido,null);assert.equal(pending.observers.length,0);
});
test('otra cuenta o rol reinicia paginación y utiliza la bandeja de tienda', () => {
  const { c,calls,change } = fixture((seller,p) => rx.of({items:[{id:seller?2:1}],hasMore:false}));
  c.pedido=42;change({accountId:8,activeRole:'VENDEDOR'});
  assert.equal(c.avisos.length,1);assert.equal(c.avisos[0].id,2);assert.equal(c.pedido,null);
  assert.deepEqual(calls[1],{seller:true,page:0});
});
