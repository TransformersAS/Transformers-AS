import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL, cuenta, entrar, productosComprables } from './lib/sesion.js';

// Recursos propios del comprador: carrito, favoritos, perfil y direcciones.
//
// ASR-005: P95 <= 2 s en cada grupo y errores < 2 % con hasta 100 usuarios concurrentes.
// Cada usuario virtual usa su propia cuenta (ACCOUNT_PATTERN), porque estos recursos son por cuenta:
// con una sola cuenta compartida se mediría la contención sobre un único carrito, no el caso real.

const carrito = new Trend('carrito_duration', true);
const favoritos = new Trend('favoritos_duration', true);
const perfil = new Trend('perfil_duration', true);
const direcciones = new Trend('direcciones_duration', true);
const errores = new Rate('recursos_error_rate');

const vus = Number(__ENV.VUS || 100);
const p95 = Number(__ENV.P95_LIMIT_MS || 2000);
const pausa = Number(__ENV.THINK_TIME || 0);

export const options = {
  scenarios: {
    recursos_comprador: { executor: 'constant-vus', vus, duration: __ENV.DURATION || '1m', gracefulStop: '15s' },
  },
  thresholds: {
    carrito_duration: [`p(95)<=${p95}`],
    favoritos_duration: [`p(95)<=${p95}`],
    perfil_duration: [`p(95)<=${p95}`],
    direcciones_duration: [`p(95)<=${p95}`],
    recursos_error_rate: [`rate<${Number(__ENV.ERROR_RATE_LIMIT || 0.02)}`],
  },
};

export function setup() {
  const jar = new http.CookieJar();
  if (!entrar(jar, cuenta(1))) {
    throw new Error('Preflight: login fallido; comprobar cuentas, verificación y URL antes de medir.');
  }
  return { productos: productosComprables(jar, 1) };
}

const jar = new http.CookieJar();
let cabeceras = null;
let conDireccion = false;

/** Registra la petición en su grupo y en la tasa de error. Devuelve si respondió lo esperado. */
function medir(tendencia, response, esperado, nombre) {
  tendencia.add(response.timings.duration);
  const ok = check(response, { [nombre]: (r) => r.status === esperado });
  errores.add(!ok);
  if (!ok) {
    console.error(`${nombre}: status=${response.status}`);
    // Una sesión caducada se recupera entrando de nuevo en la siguiente iteración.
    if (response.status === 401 || response.status === 403) cabeceras = null;
  }
  return ok;
}

export default function (datos) {
  if (!cabeceras) {
    cabeceras = entrar(jar, cuenta(__VU));
    if (!cabeceras) {
      errores.add(true);
      sleep(1);
      return;
    }
  }
  const escribir = { headers: cabeceras, jar };
  const producto = datos.productos[Math.floor(Math.random() * datos.productos.length)];

  // Perfil: datos de la cuenta y de la sesión.
  medir(perfil, http.get(`${BASE_URL}/api/auth/me`, { jar, tags: { name: 'perfil' } }), 200, 'perfil');

  // Carrito: agregar, consultar, cambiar la cantidad y quitar (queda como estaba).
  const agregado = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ productId: producto, quantity: 1 }),
    Object.assign({ tags: { name: 'carrito agregar' } }, escribir));
  if (medir(carrito, agregado, 201, 'carrito agregar')) {
    const item = agregado.json('id');
    medir(carrito, http.get(`${BASE_URL}/api/cart`, { jar, tags: { name: 'carrito ver' } }), 200, 'carrito ver');
    medir(carrito, http.patch(`${BASE_URL}/api/cart/items/${item}`, JSON.stringify({ quantity: 2 }),
      Object.assign({ tags: { name: 'carrito cantidad' } }, escribir)), 200, 'carrito cantidad');
    medir(carrito, http.del(`${BASE_URL}/api/cart/items/${item}`, null,
      Object.assign({ tags: { name: 'carrito quitar' } }, escribir)), 204, 'carrito quitar');
  }

  // Favoritos: guardar, listar y quitar.
  medir(favoritos, http.put(`${BASE_URL}/api/favorites/${producto}`, null,
    Object.assign({ tags: { name: 'favorito guardar' } }, escribir)), 200, 'favorito guardar');
  medir(favoritos, http.get(`${BASE_URL}/api/favorites`, { jar, tags: { name: 'favoritos ver' } }), 200, 'favoritos ver');
  medir(favoritos, http.get(`${BASE_URL}/api/favorites/ids`, { jar, tags: { name: 'favoritos ids' } }), 200, 'favoritos ids');
  medir(favoritos, http.del(`${BASE_URL}/api/favorites/${producto}`, null,
    Object.assign({ tags: { name: 'favorito quitar' } }, escribir)), 200, 'favorito quitar');

  // Direcciones: una sola alta por usuario virtual (no se pueden borrar) y luego consultas.
  if (!conDireccion) {
    const nueva = { recipientName: 'Prueba de carga', street: `Calle ${__VU} # 10-20`, city: 'Bogotá',
      department: 'Cundinamarca', postalCode: '110111', phone: '3001234567' };
    conDireccion = medir(direcciones, http.post(`${BASE_URL}/api/addresses`, JSON.stringify(nueva),
      Object.assign({ tags: { name: 'direccion crear' } }, escribir)), 201, 'direccion crear');
  }
  medir(direcciones, http.get(`${BASE_URL}/api/addresses`, { jar, tags: { name: 'direcciones ver' } }), 200, 'direcciones ver');

  if (pausa > 0) sleep(pausa);
}
