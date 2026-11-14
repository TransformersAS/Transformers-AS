import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, cuenta, entrar, productosComprables } from './lib/sesion.js';

// Compra completa: agregar al carrito, reservar inventario, calcular el total y pagar.
//
// ASR-005: procesamiento interno del checkout <= 3 s, flujo principal P95 <= 4 s, errores < 2 %
// con hasta 100 usuarios concurrentes.
//
// "Interno" = sin el tiempo de la pasarela externa: el backend debe correr con payments.mock-enabled=true
// (PAYMENTS_MOCK_ENABLED=true), que responde la aprobación sin salir a Mercado Pago. Así se mide lo que
// sí es del sistema: validar carrito y dirección, recalcular el total, confirmar reservas, descontar stock
// y crear el pedido en una transacción. SOLO en una base desechable: cada iteración crea un pedido real.

const reserva = new Trend('checkout_reserva_duration', true);
const total = new Trend('checkout_total_duration', true);
const pago = new Trend('checkout_pago_duration', true);
const interno = new Trend('checkout_interno_duration', true);
const flujo = new Trend('flujo_compra_duration', true);
const errores = new Rate('checkout_error_rate');
const pedidos = new Counter('pedidos_creados');

const vus = Number(__ENV.VUS || 100);
// Tiempo entre compras de una misma persona. Sin él, 100 usuarios virtuales serían miles de compras por minuto.
const pausa = Number(__ENV.THINK_TIME || 1);

export const options = {
  scenarios: {
    compra: { executor: 'constant-vus', vus, duration: __ENV.DURATION || '1m', gracefulStop: '20s' },
  },
  thresholds: {
    checkout_interno_duration: [`p(95)<=${Number(__ENV.CHECKOUT_LIMIT_MS || 3000)}`],
    flujo_compra_duration: [`p(95)<=${Number(__ENV.FLOW_LIMIT_MS || 4000)}`],
    checkout_error_rate: [`rate<${Number(__ENV.ERROR_RATE_LIMIT || 0.02)}`],
    pedidos_creados: ['count>0'],
  },
};

export function setup() {
  const jar = new http.CookieJar();
  if (!entrar(jar, cuenta(1))) {
    throw new Error('Preflight: login fallido; comprobar cuentas, verificación y URL antes de medir.');
  }
  // Stock holgado: cada compra descuenta una unidad y no se repone durante la prueba.
  return { productos: productosComprables(jar, Number(__ENV.MIN_STOCK || 50)) };
}

const jar = new http.CookieJar();
let cabeceras = null;
let direccion = null;

function paso(tendencia, response, esperado, nombre) {
  if (tendencia) tendencia.add(response.timings.duration);
  const ok = check(response, { [nombre]: (r) => r.status === esperado });
  if (!ok) {
    console.error(`${nombre}: status=${response.status} ${String(response.body).slice(0, 160)}`);
    if (response.status === 401 || response.status === 403) cabeceras = null;
  }
  return ok;
}

/** Deja el carrito vacío: una compra fallida a medias no debe contaminar la siguiente iteración. */
function vaciarCarrito(opciones) {
  const actual = http.get(`${BASE_URL}/api/cart`, { jar, tags: { name: 'limpieza' } });
  if (actual.status !== 200) return;
  for (const item of actual.json('items') || []) {
    http.del(`${BASE_URL}/api/cart/items/${item.id}`, null, Object.assign({ tags: { name: 'limpieza' } }, opciones));
  }
}

function prepararCuenta(opciones) {
  vaciarCarrito(opciones);
  const existentes = http.get(`${BASE_URL}/api/addresses`, { jar, tags: { name: 'setup' } });
  if (existentes.status === 200 && existentes.json().length > 0) {
    return existentes.json()[0].id;
  }
  const nueva = { recipientName: 'Prueba de carga', street: `Calle ${__VU} # 10-20`, city: 'Bogotá',
    department: 'Cundinamarca', postalCode: '110111', phone: '3001234567' };
  const creada = http.post(`${BASE_URL}/api/addresses`, JSON.stringify(nueva),
    Object.assign({ tags: { name: 'setup' } }, opciones));
  return creada.status === 201 ? creada.json('id') : null;
}

export default function (datos) {
  if (!cabeceras) {
    cabeceras = entrar(jar, cuenta(__VU));
    direccion = cabeceras ? prepararCuenta({ headers: cabeceras, jar }) : null;
    if (!cabeceras || !direccion) {
      cabeceras = null;
      errores.add(true);
      sleep(1);
      return;
    }
  }
  const opciones = { headers: cabeceras, jar };
  const producto = datos.productos[Math.floor(Math.random() * datos.productos.length)];
  const compra = { addressId: direccion, shippingMethod: 'STANDARD', couponCode: null };

  const agregar = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ productId: producto, quantity: 1 }),
    Object.assign({ tags: { name: 'carrito agregar' } }, opciones));
  let ok = paso(null, agregar, 201, 'carrito agregar');
  let milisegundos = agregar.timings.duration;
  let internos = 0;

  let reservas = [];
  if (ok) {
    const r = http.post(`${BASE_URL}/api/reservations/cart`, null, Object.assign({ tags: { name: 'reservar' } }, opciones));
    ok = paso(reserva, r, 200, 'reservar inventario');
    milisegundos += r.timings.duration;
    internos += r.timings.duration;
    if (ok) reservas = r.json().map((x) => x.id);
  }
  if (ok) {
    const r = http.post(`${BASE_URL}/api/checkout/preview`, JSON.stringify(compra),
      Object.assign({ tags: { name: 'calcular total' } }, opciones));
    ok = paso(total, r, 200, 'calcular total');
    milisegundos += r.timings.duration;
    internos += r.timings.duration;
  }
  if (ok) {
    const cuerpo = Object.assign({ paymentMethod: 'CARD', reservationIds: reservas }, compra);
    const r = http.post(`${BASE_URL}/api/payments/process`, JSON.stringify(cuerpo),
      Object.assign({ tags: { name: 'pagar' } }, opciones));
    ok = paso(pago, r, 200, 'pagar') && check(r, { 'pedido creado': (x) => x.json('status') === 'APPROVED' && x.json('orderId') > 0 });
    milisegundos += r.timings.duration;
    internos += r.timings.duration;
  }

  errores.add(!ok);
  if (ok) {
    // Checkout interno = reservar + calcular + pagar; flujo = lo anterior más agregar al carrito.
    interno.add(internos);
    flujo.add(milisegundos);
    pedidos.add(1);
  } else if (cabeceras) {
    vaciarCarrito(opciones);
  }
  if (pausa > 0) sleep(pausa);
}
