import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';
import { BASE_URL, cuenta, entrar } from './lib/sesion.js';

// SOLO mediante el runner y su red interna desechable. No usar con checkout ni CU-12.
const kind = __ENV.SCENARIO || 'catalog';
const vus = Number(__ENV.VUS || 1);
const warm = Number(__ENV.WARMUP_SECONDS || 10);
const duration = Number(__ENV.MEASURE_SECONDS || 60);
const smoke = __ENV.RUN_KIND === 'smoke';
if (!['catalog', 'resources'].includes(kind) || !Number.isInteger(vus) || vus < 1 || vus > 100
    || duration < 2 || warm < 0) throw new Error('Configuración inválida');
if (!__ENV.DISPOSABLE_PERFORMANCE || !BASE_URL.match(/^http:\/\/(frontend|127\.0\.0\.1)(:\d+)?$/)) {
  throw new Error('Se exige objetivo de prueba interno/loopback y confirmación desechable');
}
const definitions = kind === 'catalog' ? {
  catalog_list: [3000, 'RNF-016'],
  detail_ready_small: [3000, 'RNF-017'], detail_ready_medium: [3000, 'RNF-017'], detail_ready_large: [3000, 'RNF-017'],
  detail_small: [3000, 'RNF-017'], detail_medium: [3000, 'RNF-017'], detail_large: [3000, 'RNF-017'],
  reviews_small: [3000, 'RNF-017'], reviews_medium: [3000, 'RNF-017'], reviews_large: [3000, 'RNF-017'],
  rating_small: [3000, 'RNF-017'], rating_medium: [3000, 'RNF-017'], rating_large: [3000, 'RNF-017'],
  image_small: [3000, 'RNF-017'], image_medium: [3000, 'RNF-017'], image_large: [3000, 'RNF-017'],
} : {
  cart_create: [2000, 'RNF-018'], cart_read: [2000, 'RNF-018'],
  cart_update: [2000, 'RNF-018'], cart_delete: [2000, 'RNF-018'],
  favorite_save: [2000, 'RNF-018'], favorite_read: [2000, 'RNF-018'], favorite_delete: [2000, 'RNF-018'],
  profile_read: [2000, 'RNF-018'], profile_preferences_update: [2000, 'RNF-018'],
  address_create: [2000, 'RNF-018'], address_read: [2000, 'RNF-018'],
};
const metrics = {};
const thresholds = {};
for (const name of Object.keys(definitions)) {
  metrics[name] = { duration: new Trend(name + '_ms', true), successMs: new Trend(name + '_success_ms', true),
    attempts: new Counter(name + '_attempts'), successes: new Counter(name + '_successes'),
    errors: new Rate(name + '_errors') };
  thresholds[name + '_ms'] = [`p(95)<=${definitions[name][0]}`];
  thresholds[name + '_success_ms'] = [`p(95)<=${definitions[name][0]}`];
  thresholds[name + '_successes'] = [`count>=${smoke ? 1 : 20}`];
  thresholds[name + '_errors'] = ['rate<0.02'];
}
const requestErrors = new Rate('measured_errors');
const contractErrors = new Rate('contract_errors');
const measuredRequests = new Counter('measured_requests');
const measuredVus = new Counter('measured_vus');
const active = new Gauge('active_vus');
const span = new Trend('measurement_elapsed_ms');
const flow = new Trend('flow_ms', true);
const flowErrors = new Rate('flow_errors');
const completedFlows = new Counter('completed_flows');
const startedFlows = new Counter('started_flows');
const finishedFlows = new Counter('finished_flows');
const thresholdsAll = Object.assign(thresholds, {
  measured_errors: ['rate<0.02'], contract_errors: ['rate==0'], flow_errors: ['rate<0.02'],
  flow_ms: ['p(95)<=4000'], completed_flows: [`count>=${smoke ? 1 : 20}`],
  measured_vus: [`count==${vus}`], finished_flows: [`count>=${smoke ? 1 : 20}`],
});
export const options = {
  scenarios: { workload: { executor: 'constant-vus', vus, duration: `${warm + duration}s`, gracefulStop: '20s' } },
  setupTimeout: '4m', thresholds: thresholdsAll,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)'],
};
function json(response) { try { return response.json(); } catch (_) { return null; } }
export function setup() {
  const response = http.get(`${BASE_URL}/api/products`, { tags: { phase: 'preparation' } });
  const products = json(response);
  if (response.status !== 200 || !Array.isArray(products) || products.length !== 1000) {
    throw new Error('Preflight: se requieren exactamente 1000 productos visibles');
  }
  const accounts = [];
  if (kind === 'resources') {
    if (!__ENV.ACCOUNT_PATTERN || Number(__ENV.ACCOUNT_POOL || 100) < vus) {
      throw new Error('Se exige pool independiente >= VUS');
    }
    for (let i = 1; i <= vus; i++) {
      const jar = new http.CookieJar();
      const account = cuenta(i);
      const headers = entrar(jar, account);
      const me = http.get(`${BASE_URL}/api/auth/me`, { jar, tags: { phase: 'preparation' } });
      const identity = json(me);
      if (!headers || me.status !== 200 || !identity || identity.email !== account.email || identity.activeRole !== 'COMPRADOR') {
        throw new Error('Preflight: identidad/rol del pool no comprobados');
      }
      accounts.push({ headers, cookies: jar.cookiesForURL(BASE_URL), email: account.email });
    }
  }
  return { accounts };
}
const jar = new http.CookieJar();
let initialized = false;
let counted = false;
let failed = false;
let elapsed = 0;
let phase = 'warmup';
let headers = {};
function record(name, milliseconds, valid, contractFailure, isHttp = true) {
  if (phase !== 'measurement') return;
  metrics[name].attempts.add(1); metrics[name].duration.add(milliseconds);
  metrics[name].successes.add(valid ? 1 : 0); metrics[name].errors.add(!valid);
  if (valid) metrics[name].successMs.add(milliseconds);
  if (isHttp) {
    requestErrors.add(!valid); contractErrors.add(contractFailure); measuredRequests.add(1);
  }
  span.add(Date.now() - exec.scenario.startTime - warm * 1000);
}
function request(name, method, path, body, expected, contract, binary = false) {
  const params = { jar, headers, timeout: '10s', tags: { name, phase, rnf: definitions[name][1] } };
  if (binary) params.responseType = 'binary';
  const r = http.request(method, `${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), params);
  const statusOk = r.status === expected;
  let valid = false;
  try { valid = statusOk && contract(binary ? r.body : json(r)); } catch (_) { valid = false; }
  if (phase === 'measurement') {
    record(name, r.timings.duration, valid, statusOk && !valid);
    check(r, { [name + ': estado y contrato']: () => valid });
  }
  failed = failed || !valid;
  elapsed += r.timings.duration;
  return valid ? (binary ? r.body : json(r)) : null;
}
function catalog() {
  request('catalog_list', 'GET', '/api/products', null, 200,
    x => Array.isArray(x) && x.length === 1000 && x.every(p => p.id > 0 && p.active && typeof p.name === 'string'));
  const size = (__VU + __ITER) % 3;
  const label = ['small', 'medium', 'large'][size];
  const id = size + 1;
  const count = [1, 5, 20][size];
  const detailStart = Date.now();
  const detail = request('detail_' + label, 'GET', `/api/products/${id}`, null, 200,
    x => x && x.id === id && x.imageUrls.length === [1, 3, 5][size]
      && x.variants.length === [1, 3, 5][size] && x.reviewCount === count);
  const reviews = request('reviews_' + label, 'GET', `/api/reviews/products/${id}`, null, 200,
    x => Array.isArray(x) && x.length === count && x.every(r => r.productId === id && r.rating === 5));
  const rating = request('rating_' + label, 'GET', `/api/reviews/products/${id}/summary`, null, 200,
    x => x && x.productId === id && x.count === count && x.average === 5);
  let imagesOk = !!detail;
  if (detail) for (const path of detail.imageUrls) {
    if (!/^\/performance\/(small|medium|large)\.png$/.test(path)) throw new Error('Imagen ajena al objetivo');
    const image = request('image_' + label, 'GET', path, null, 200,
      x => x && x.byteLength > 1000 && new Uint8Array(x)[0] === 137 && new Uint8Array(x)[1] === 80, true);
    imagesOk = imagesOk && !!image;
  }
  record('detail_ready_' + label, Date.now() - detailStart, !!detail && !!reviews && !!rating && imagesOk, false, false);
}
function resources(data) {
  const email = data.accounts[__VU - 1].email;
  const product = 100 + __VU; // Producto por VU; identidad, carrito y favoritos también separados.
  const item = request('cart_create', 'POST', '/api/cart/items', { productId: product, quantity: 1 }, 201,
    x => x && x.id > 0 && x.productId === product && x.quantity === 1);
  request('cart_read', 'GET', '/api/cart', null, 200,
    x => x && Array.isArray(x.items) && x.items.length === 1 && x.items[0].productId === product);
  if (item) {
    request('cart_update', 'PATCH', `/api/cart/items/${item.id}`, { quantity: 2 }, 200, x => x && x.quantity === 2);
    request('cart_delete', 'DELETE', `/api/cart/items/${item.id}`, null, 204, () => true);
  }
  request('favorite_save', 'PUT', `/api/favorites/${product}`, null, 200, () => true);
  request('favorite_read', 'GET', '/api/favorites', null, 200,
    x => Array.isArray(x) && x.some(p => p.id === product));
  request('favorite_delete', 'DELETE', `/api/favorites/${product}`, null, 200, () => true);
  const profile = request('profile_read', 'GET', '/api/account/profile', null, 200,
    x => x && x.email === email && Number.isInteger(x.version));
  if (profile) request('profile_preferences_update', 'PUT', '/api/account/profile', {
    fullName: profile.fullName, birthDate: profile.birthDate, email: profile.email, phone: profile.phone,
    promotions: !profile.promotions, marketplaceNews: false, version: profile.version, confirmChanges: true,
  }, 200, x => x && x.profile && x.profile.email === email && x.profile.promotions === !profile.promotions
    && x.profile.version === profile.version + 1);
  const address = request('address_create', 'POST', '/api/addresses', {
    recipientName: `Carga ${__VU}`, street: 'Calle ficticia', city: 'Bogotá', department: 'Cundinamarca',
    postalCode: '110111', phone: '3000000000',
  }, 201, x => x && x.id > 0 && x.recipientName === `Carga ${__VU}`);
  request('address_read', 'GET', '/api/addresses', null, 200,
    x => Array.isArray(x) && address && x.some(a => a.id === address.id)
      && x.every(a => a.recipientName === `Carga ${__VU}` || a.recipientName === `Fixture ${__VU}`));
}
export default function(data) {
  if (!initialized && kind === 'resources') {
    const account = data.accounts[__VU - 1];
    for (const name of Object.keys(account.cookies)) jar.set(BASE_URL, name, account.cookies[name][0]);
    headers = account.headers;
    initialized = true;
  }
  const since = Date.now() - exec.scenario.startTime;
  phase = since >= warm * 1000 ? 'measurement' : 'warmup';
  if (since >= (warm + duration) * 1000) { sleep(0.1); return; }
  if (phase === 'measurement' && !counted) { measuredVus.add(1); counted = true; }
  active.add(exec.instance.vusActive);
  const started = Date.now();
  if (phase === 'measurement') startedFlows.add(1);
  failed = false; elapsed = 0;
  if (kind === 'catalog') catalog(); else resources(data);
  if (phase === 'measurement') {
    flow.add(Date.now() - started); flowErrors.add(failed); completedFlows.add(failed ? 0 : 1); finishedFlows.add(1);
  }
  sleep(0.25);
}
export function handleSummary(data) {
  data.performance = { scenario: kind, vus, warmup_seconds: warm, measurement_seconds: duration,
    minimum_samples: smoke ? 1 : 20, run_kind: smoke ? 'smoke' : 'measurement',
    operations: definitions, scope: 'HTTP; no renderizado ni filtros locales Angular' };
  return { [__ENV.RESULT_PATH || '/results/summary.json']: JSON.stringify(data, null, 2),
    stdout: `Escenario ${kind}, ${vus} VU; consultar JSON y thresholds.\n` };
}
