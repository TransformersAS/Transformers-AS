// Pruebas unitarias del componente y del contrato HTTP, sin navegador ni backend.
// Se transpila el TypeScript real; solo se sustituyen Angular/DI y los componentes hijos.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const ts = require('typescript');
const rx = require('rxjs');

function load(relative, modules) {
  const filename = resolve(__dirname, '../src/app', relative);
  const js = ts.transpileModule(readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, experimentalDecorators: true }
  }).outputText;
  const exports = {};
  runInNewContext(js, { exports, require: name => {
    if (!(name in modules)) throw new Error(`Dependencia sin sustituir: ${name}`);
    return modules[name];
  }});
  return exports;
}

function fixture(status = 'CONFIRMED', response, options = {}) {
  class AuthService {}
  class PedidosService {}
  const requests = [];
  const lists = [];
  const details = [];
  let account = { accountId: 1, activeRole: 'COMPRADOR' };
  let effectCallback, cleanup;
  let rows = [{ id: 7, status }, { id: 8, status: 'CONFIRMED' }];
  const service = {
    listarMisPedidos: filters => {
      lists.push({ ...filters });
      return options.list ? options.list(filters) : rx.of(rows.filter(p => !filters.status || p.status === filters.status));
    },
    obtenerPedido: id => {
      details.push(id);
      return options.detail ? options.detail(id) : rx.of({ id, status, items: [], history: [] });
    },
    cancelarPedido: (id, body) => {
      requests.push({ id, body });
      return (response ?? rx.of({ orderId: id, status: 'CANCELLED', paymentStatus: 'REFUNDED',
        refund: { status: 'COMPLETED', message: 'Reembolso completado' } })).pipe(rx.tap(result => {
          rows = rows.map(p => p.id === id ? { ...p, status: result.status } : p);
        }));
    }
  };
  const { MisPedidosComponent } = load('pedidos/components/mis-pedidos.component.ts', {
    '@angular/core': {
      Component: () => target => target, Output: () => () => {}, EventEmitter: class {},
      effect: callback => { effectCallback = callback; callback(fn => { cleanup = fn; }); },
      inject: token => token === AuthService ? { cuenta: () => account } : service
    },
    '@angular/common': {}, '@angular/forms': {}, 'rxjs': rx,
    '../../core/services/auth.service': { AuthService }, '../services/pedidos.service': { PedidosService },
    '../models/pedido.model': load('pedidos/models/pedido.model.ts', {}),
    './resumen-pedido.component': {}, '../../seguimiento/components/seguimiento-logistico.component': {},
    '../../seguimiento/components/consulta-devolucion.component': {},
    '../../seguimiento/models/seguimiento.model': {}, '../../devoluciones/components/devolver-linea.component': {}
  });
  const component = new MisPedidosComponent();
  component.detalle = { id: 7, status, items: [] };
  component.pedidos = [{ id: 7, status }, { id: 8, status: 'CONFIRMED' }];
  return { component, requests, lists, details, changeAccount: next => {
    cleanup?.();
    account = next;
    effectCallback(fn => { cleanup = fn; });
  }};
}

for (const status of ['CONFIRMED', 'IN_PREPARATION']) {
  test(`permite cancelar ${status}, exige motivo y confirmación y actualiza lista/detalle`, () => {
    const { component: c, requests } = fixture(status);
    assert.equal(c.puedeCancelar, true);
    c.prepararCancelacion();
    c.cancelarPedido();
    assert.equal(requests.length, 0);
    c.motivo = 'CHANGED_MIND';
    c.confirmar = false;
    c.cancelarPedido();
    assert.equal(requests.length, 0);
    c.confirmar = true;
    c.cancelarPedido();
    assert.equal(requests.length, 1);
    assert.equal(requests[0].body.reasonCode, 'CHANGED_MIND');
    assert.equal(c.detalle.status, 'CANCELLED');
    assert.equal(c.detalle.paymentStatus, 'REFUNDED');
    assert.equal(c.pedidos[0].status, 'CANCELLED');
    assert.equal(c.pedidos[1].status, 'CONFIRMED');
    assert.equal(c.confirmar, false);
    assert.equal(c.cancelando, false);
    assert.match(c.exito, /Pedido cancelado.*Reembolso completado/);
  });
}

test('oculta la cancelación y no envía peticiones en estados no cancelables', () => {
  for (const status of ['READY_FOR_DISPATCH', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'CANCELLATION_REQUESTED']) {
    const { component: c, requests } = fixture(status);
    assert.equal(c.puedeCancelar, false);
    c.confirmar = true;
    c.motivo = 'CHANGED_MIND';
    c.cancelarPedido();
    assert.equal(requests.length, 0);
  }
});

test('Otro exige explicación y envía el motivo y el texto recortado', () => {
  const { component: c, requests } = fixture();
  assert.equal(c.motivos.find(m => m.codigo === 'OTHER').etiqueta, 'Otro');
  c.prepararCancelacion();
  c.motivo = 'OTHER';
  for (const invalid of ['', '   ', 'x'.repeat(1001)]) {
    c.explicacion = invalid;
    assert.equal(c.motivoValido, false);
    c.cancelarPedido();
    assert.equal(requests.length, 0);
  }
  c.explicacion = '  Equivoqué la dirección  ';
  c.cancelarPedido();
  assert.equal(requests[0].body.reasonCode, 'OTHER');
  assert.equal(requests[0].body.details, 'Equivoqué la dirección');
});

test('un reembolso pendiente muestra CANCELLED y el mensaje financiero recibido', () => {
  const { component: c } = fixture('CONFIRMED', rx.of({ status: 'CANCELLED', paymentStatus: 'REFUND_PENDING',
    refund: { status: 'PENDING', message: 'Reembolso en proceso' } }));
  c.prepararCancelacion();
  c.motivo = 'CHANGED_MIND';
  c.cancelarPedido();
  assert.equal(c.detalle.status, 'CANCELLED');
  assert.equal(c.detalle.paymentStatus, 'REFUND_PENDING');
  assert.match(c.exito, /Pedido cancelado.*Reembolso en proceso/);
});

test('409 conserva el estado y pide actualizar el detalle', () => {
  const { component: c } = fixture('CONFIRMED', rx.throwError(() => ({ status: 409 })));
  c.prepararCancelacion();
  c.motivo = 'CHANGED_MIND';
  c.cancelarPedido();
  assert.equal(c.detalle.status, 'CONFIRMED');
  assert.equal(c.conflicto, true);
  assert.match(c.error, /Actualiza el detalle/);
  assert.equal(c.exito, '');
});

test('el servicio envía motivo y explicación al endpoint del comprador', () => {
  const requests = [];
  const { PedidosService } = load('pedidos/services/pedidos.service.ts', {
    '@angular/core': { Injectable: () => target => target, inject: () => ({ post: (url, body) => requests.push({ url, body }) }) },
    '@angular/common/http': {}, '../../core/config/api.config': { API_BASE: '/api' }
  });
  const body = { reasonCode: 'OTHER', details: 'Me equivoqué' };
  new PedidosService().cancelarPedido(7, body);
  assert.equal(requests[0].url, '/api/orders/7/cancellation');
  assert.equal(requests[0].body, body);
});

test('la plantilla conecta botón, motivos, explicación y validación del componente', async () => {
  const { parseTemplate } = await import('@angular/compiler');
  const template = readFileSync(resolve(__dirname, '../src/app/pedidos/components/mis-pedidos.component.html'), 'utf8');
  const parsed = parseTemplate(template, 'mis-pedidos.component.html');
  assert.equal(parsed.errors, null);
  const nodes = [];
  function visit(node) { nodes.push(node); for (const child of node.children ?? []) visit(child); }
  parsed.nodes.forEach(visit);
  const inputs = nodes.flatMap(n => [...(n.inputs ?? []), ...(n.templateAttrs ?? [])]);
  const outputs = nodes.flatMap(n => n.outputs ?? []);
  assert.ok(inputs.some(i => i.name === 'ngIf' && i.value.source === 'puedeCancelar && !confirmar && !conflicto'));
  assert.ok(inputs.some(i => i.name === 'ngModel' && i.value.source === 'motivo'));
  assert.ok(inputs.some(i => i.name === 'ngModel' && i.value.source === 'explicacion'));
  assert.ok(inputs.some(i => i.name === 'ngForOf' && i.value.source === 'motivos'));
  assert.ok(inputs.some(i => i.name === 'disabled' && i.value.source === 'ocupada || !motivoValido'));
  assert.ok(outputs.some(o => o.name === 'click' && o.handler.source === 'cancelarPedido()'));
});


test('aplica y limpia filtros, y conserva los aplicados al volver del detalle', () => {
  const { component: c, lists } = fixture();
  c.detalle = null;
  c.desde = '2026-10-01'; c.hasta = '2026-10-31'; c.estadoFiltro = 'CONFIRMED';
  c.aplicarFiltros();
  assert.equal(JSON.stringify(lists.at(-1)), JSON.stringify({ from: '2026-10-01', to: '2026-10-31', status: 'CONFIRMED' }));
  c.verDetalle(7);
  assert.equal(c.detalle.id, 7);
  c.cargarLista();
  assert.equal(c.detalle, null);
  assert.equal(lists.at(-1).from, '2026-10-01');
  assert.equal(c.estadoFiltro, 'CONFIRMED');
  c.limpiarFiltros();
  assert.equal(JSON.stringify(lists.at(-1)), '{}');
  assert.equal(c.desde, ''); assert.equal(c.hasta, ''); assert.equal(c.estadoFiltro, '');
});

test('fechas inválidas y rango invertido no envían peticiones', () => {
  const { component: c, lists } = fixture();
  const initial = lists.length;
  for (const date of ['ayer', '2026-02-30', '2026-13-01', '0000-01-01']) {
    c.desde = date;
    c.aplicarFiltros();
    assert.match(c.errorFiltros, /fechas válidas/);
  }
  c.desde = '2026-10-31'; c.hasta = '2026-10-01';
  c.aplicarFiltros();
  assert.match(c.errorFiltros, /posterior/);
  c.desde = ''; c.hasta = '';
  c.aplicarFiltros(true); // El navegador detecta una fecha incompleta.
  assert.match(c.errorFiltros, /fechas válidas/);
  assert.equal(lists.length, initial);
});

test('cancelar recarga desde backend respetando los filtros y conserva el detalle H02', () => {
  const { component: c, lists } = fixture();
  c.filtrosActivos = { from: '2026-10-01', status: 'CONFIRMED' };
  c.detalle = { ...c.detalle, sellerNameSnapshot: 'Original', discountAmount: 0 };
  c.prepararCancelacion(); c.motivo = 'CHANGED_MIND'; c.cancelarPedido();
  assert.equal(lists.at(-1).status, 'CONFIRMED');
  assert.equal(lists.at(-1).from, '2026-10-01');
  assert.equal(c.pedidos.some(p => p.id === 7), false);
  assert.equal(c.detalle.sellerNameSnapshot, 'Original');
  assert.equal(c.detalle.discountAmount, 0);
  assert.equal(c.detalle.paymentStatus, 'REFUNDED');
});

test('un cambio logístico recarga la búsqueda activa', () => {
  const { component: c, lists } = fixture();
  c.filtrosActivos = { status: 'IN_TRANSIT' };
  c.estadoActualizado('DELIVERED');
  assert.equal(c.detalle.status, 'DELIVERED');
  assert.equal(lists.at(-1).status, 'IN_TRANSIT');
});

test('cambiar de cuenta cancela respuestas anteriores y reinicia datos, filtros y carga', () => {
  const pending = new rx.Subject();
  let calls = 0;
  const { component: c, changeAccount } = fixture('CONFIRMED', undefined, {
    list: () => ++calls === 1 ? pending : rx.of([{ id: 99, status: 'DELIVERED' }])
  });
  c.desde = '2026-10-01'; c.filtrosActivos = { from: c.desde };
  changeAccount({ accountId: 2, activeRole: 'COMPRADOR' });
  pending.next([{ id: 7, status: 'CONFIRMED' }]); pending.complete();
  assert.equal(c.pedidos[0].id, 99);
  assert.equal(c.desde, ''); assert.equal(JSON.stringify(c.filtrosActivos), '{}');
  assert.equal(c.detalle, null); assert.equal(c.cargando, false);
  changeAccount(null);
  assert.equal(c.pedidos.length, 0); assert.equal(c.detalle, null);
});

test('cerrar sesión cancela también un detalle pendiente', () => {
  const pending = new rx.Subject();
  const { component: c, changeAccount } = fixture('CONFIRMED', undefined, { detail: () => pending });
  c.verDetalle(7);
  changeAccount(null);
  pending.next({ id: 7, status: 'CONFIRMED' }); pending.complete();
  assert.equal(c.detalle, null); assert.equal(c.pedidos.length, 0); assert.equal(c.cargando, false);
});

test('el servicio envía solo los parámetros opcionales de filtro', () => {
  const calls = [];
  const { PedidosService } = load('pedidos/services/pedidos.service.ts', {
    '@angular/core': { Injectable: () => target => target, inject: () => ({ get: (url, options) => calls.push({ url, options }) }) },
    '@angular/common/http': {}, '../../core/config/api.config': { API_BASE: '/api' }
  });
  const service = new PedidosService();
  service.listarMisPedidos();
  assert.equal(JSON.stringify(calls[0].options.params), '{}');
  service.listarMisPedidos({ from: '2026-10-01', to: '2026-10-31', status: 'CANCELLED' });
  assert.equal(calls[1].url, '/api/orders');
  assert.equal(JSON.stringify(calls[1].options.params), JSON.stringify({ from: '2026-10-01', to: '2026-10-31', status: 'CANCELLED' }));
});


test('después de cancelar carga el historial del backend sin fabricar eventos', () => {
  const history = [{ fromStatus: 'CONFIRMED', toStatus: 'CANCELLED', createdAt: '2026-10-31T12:00:00', legacy: false }];
  const { component: c, details } = fixture('CONFIRMED', undefined, {
    detail: () => rx.of({ id: 7, status: 'CANCELLED', history })
  });
  c.detalle.history = [];
  c.prepararCancelacion(); c.motivo = 'CHANGED_MIND'; c.cancelarPedido();
  assert.equal(details.length, 1);
  assert.equal(c.detalle.history, history);
  assert.equal(c.detalle.status, 'CANCELLED');
});

test('un fallo de recarga conserva los eventos existentes y permite reintentar sin cancelar otra vez', () => {
  let calls = 0;
  const { component: c, requests } = fixture('CONFIRMED', undefined, {
    detail: () => ++calls === 1 ? rx.throwError(() => ({ status: 503 })) : rx.of({ id: 7, history: [] })
  });
  const original = [{ fromStatus: null, toStatus: 'CONFIRMED', legacy: false, createdAt: '2026-10-31T12:00:00' }];
  c.detalle.history = original;
  c.prepararCancelacion(); c.motivo = 'CHANGED_MIND'; c.cancelarPedido();
  assert.equal(c.detalle.status, 'CANCELLED');
  assert.equal(c.detalle.history, original);
  assert.match(c.errorHistorial, /No se pudo actualizar/);
  assert.equal(c.cargandoHistorial, false);
  c.actualizarHistorial();
  assert.equal(c.errorHistorial, ''); assert.equal(c.detalle.history.length, 0);
  assert.equal(requests.length, 1);
});

test('cerrar sesión cancela una recarga de historial pendiente', () => {
  const pending = new rx.Subject();
  const { component: c, changeAccount } = fixture('CONFIRMED', undefined, { detail: () => pending });
  c.actualizarHistorial();
  assert.equal(c.cargandoHistorial, true);
  changeAccount(null);
  pending.next({ id: 7, history: [{ toStatus: 'CANCELLED' }] }); pending.complete();
  assert.equal(c.detalle, null); assert.equal(c.cargandoHistorial, false);
});
