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

function fixture(status = 'CONFIRMED', response) {
  class AuthService {}
  class PedidosService {}
  const requests = [];
  const service = {
    listarMisPedidos: () => rx.of([]),
    cancelarPedido: (id, body) => {
      requests.push({ id, body });
      return response ?? rx.of({ orderId: id, status: 'CANCELLED', paymentStatus: 'REFUNDED',
        refund: { status: 'COMPLETED', message: 'Reembolso completado' } });
    }
  };
  const { MisPedidosComponent } = load('pedidos/components/mis-pedidos.component.ts', {
    '@angular/core': {
      Component: () => target => target, Output: () => () => {}, EventEmitter: class {},
      effect: callback => callback(() => {}),
      inject: token => token === AuthService ? { cuenta: () => ({ activeRole: 'COMPRADOR' }) } : service
    },
    '@angular/common': {}, '@angular/forms': {}, 'rxjs': rx,
    '../../core/services/auth.service': { AuthService }, '../services/pedidos.service': { PedidosService },
    './resumen-pedido.component': {}, '../../seguimiento/components/seguimiento-logistico.component': {},
    '../../seguimiento/components/consulta-devolucion.component': {},
    '../../seguimiento/models/seguimiento.model': {}, '../../devoluciones/components/devolver-linea.component': {}
  });
  const component = new MisPedidosComponent();
  component.detalle = { id: 7, status, items: [] };
  component.pedidos = [{ id: 7, status }, { id: 8, status: 'CONFIRMED' }];
  return { component, requests };
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
