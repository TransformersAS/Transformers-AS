import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// Navegación del catálogo: listar productos y abrir el detalle de uno, que es lo que hace la mayoría
// de las personas la mayor parte del tiempo. Mide el camino completo (Tomcat, pool de conexiones,
// Hibernate, MySQL) en lectura, sin el coste deliberado de BCrypt que domina el registro.
//
// ASR (Decisiones_Arquitectonicas_Marketplace.md, sección 36): catálogo y detalle P95 <= 3 s,
// tasa de error < 2 % con 100 usuarios concurrentes.
//
// El catálogo exige sesión, así que cada usuario virtual inicia sesión UNA vez y reutiliza su cookie.
// El login también usa BCrypt: si se hiciera en cada iteración, se volvería a medir el cifrado en
// lugar del catálogo. Por eso queda fuera del bucle y fuera de las métricas.

const listDuration = new Trend('catalog_list_duration', true);
const detailDuration = new Trend('catalog_detail_duration', true);
const catalogErrorRate = new Rate('catalog_error_rate');
const catalogThroughput = new Counter('catalog_throughput');

const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || '1m';
const p95LimitMs = Number(__ENV.P95_LIMIT_MS || 3000);
const errorRateLimit = Number(__ENV.ERROR_RATE_LIMIT || 0.02);

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
// Cuenta con la que se navega. Si no existe, setup() registra una: el registro es público.
const EMAIL = __ENV.CATALOG_EMAIL || 'k6-catalog@test.local';
const PASSWORD = __ENV.CATALOG_PASSWORD || 'K6Marketplace123!';

export const options = {
  scenarios: {
    catalog_browsing: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    catalog_list_duration: [`p(95)<=${p95LimitMs}`],
    catalog_detail_duration: [`p(95)<=${p95LimitMs}`],
    catalog_error_rate: [`rate<${errorRateLimit}`],
  },
};

/** Token CSRF de la sesión actual; el backend lo exige en todo lo que modifica datos. */
function csrfToken(jar) {
  const response = http.get(`${BASE_URL}/api/auth/csrf`, jar ? { jar } : {});
  if (response.status !== 200) {
    return null;
  }
  return { name: response.json('headerName'), value: response.json('token') };
}

/**
 * Se ejecuta una sola vez, antes de la prueba: asegura que la cuenta exista. Registrarla es público
 * (CU-12). Si ya existe, el backend rechaza el alta y se sigue con la que hay.
 */
export function setup() {
  const csrf = csrfToken();
  if (!csrf) {
    throw new Error(`No se pudo obtener el token CSRF de ${BASE_URL}. ¿Está arriba el Marketplace?`);
  }
  const registered = http.post(
    `${BASE_URL}/api/sellers/register`,
    JSON.stringify({ email: EMAIL, password: PASSWORD, storeName: 'K6 Catalogo', acceptTerms: true }),
    { headers: { 'Content-Type': 'application/json', [csrf.name]: csrf.value } }
  );
  if (registered.status !== 201) {
    console.log(`La cuenta ${EMAIL} no se creó ahora (HTTP ${registered.status}); se usará la existente.`);
  }
  return { email: EMAIL, password: PASSWORD };
}

/**
 * Frasco de cookies propio de cada usuario virtual. Hace falta crearlo aquí: k6 reinicia el frasco
 * por defecto al empezar cada iteración, así que la sesión se perdería y el catálogo respondería 401
 * a partir de la segunda vuelta. Este sobrevive mientras el usuario virtual siga vivo.
 */
const sessionJar = new http.CookieJar();
let loggedIn = false;

function login(credentials) {
  const csrf = csrfToken(sessionJar);
  if (!csrf) {
    return false;
  }
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    { email: credentials.email, password: credentials.password },
    { headers: { [csrf.name]: csrf.value }, jar: sessionJar }
  );
  if (response.status !== 204) {
    console.error(`POST /api/auth/login: status=${response.status}`);
    return false;
  }
  return true;
}

export default function (credentials) {
  if (!loggedIn) {
    loggedIn = login(credentials);
    if (!loggedIn) {
      catalogErrorRate.add(true);
      return;
    }
  }

  const list = http.get(`${BASE_URL}/api/products`, { tags: { name: 'catalogo' }, jar: sessionJar });
  listDuration.add(list.timings.duration);
  const listOk = check(list, { 'listado de productos responde 200': (r) => r.status === 200 });
  catalogErrorRate.add(!listOk);
  if (!listOk) {
    console.error(`GET /api/products: status=${list.status}`);
    // Una sesión caducada o revocada se recupera volviendo a entrar en la siguiente iteración.
    loggedIn = list.status !== 401;
    return;
  }

  const products = list.json();
  if (!Array.isArray(products) || products.length === 0) {
    console.error('El catálogo está vacío: no hay detalle que medir.');
    catalogErrorRate.add(true);
    return;
  }

  // Un producto distinto en cada iteración, para no medir siempre la misma fila ni una caché accidental.
  const product = products[Math.floor(Math.random() * products.length)];
  const detail = http.get(`${BASE_URL}/api/products/${product.id}`, { tags: { name: 'detalle' }, jar: sessionJar });
  detailDuration.add(detail.timings.duration);
  const detailOk = check(detail, { 'detalle del producto responde 200': (r) => r.status === 200 });
  catalogErrorRate.add(!detailOk);
  if (detailOk) {
    catalogThroughput.add(1);
  } else {
    console.error(`GET /api/products/${product.id}: status=${detail.status}`);
  }
}
