const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Execute only the JS logic with in-memory k6 doubles; no k6, server or network.
const source = fs.readFileSync(path.join(__dirname, '../../k6/performance.js'), 'utf8')
  .replace(/^import .*;\n/gm, '')
  .replace('export default function(data)', 'function workload(data)')
  .replace(/export (const|function) /g, '$1 ');
function harness(respond) {
  const metrics = {}, logs = [], calls = [];
  class Metric {
    constructor(name) { this.name = name; metrics[name] = []; }
    add(value) { metrics[this.name].push(value); }
  }
  class Jar { set() {} }
  const context = vm.createContext({
    __ENV: { SCENARIO: 'resources', VUS: '100', DISPOSABLE_PERFORMANCE: '1' },
    __VU: 1, __ITER: 0, BASE_URL: 'http://frontend',
    Counter: Metric, Rate: Metric, Trend: Metric, Gauge: Metric,
    check: () => {}, sleep: () => {},
    exec: { scenario: { startTime: Date.now() - 10100 }, instance: { vusActive: 100 } },
    console: { warn: line => logs.push(line) },
    http: { CookieJar: Jar, request(method, url, body, params) {
      calls.push({ method, url, body, params });
      const r = respond(calls.length, { method, url, body, params });
      return { status: 200, error: '', error_code: 0, timings: { duration: 123 },
        json: () => r.value, body: JSON.stringify(r.value), ...r };
    } },
  });
  vm.runInContext(source, context);
  vm.runInContext("phase = 'measurement'", context);
  return { context, metrics, logs, calls, run: text => vm.runInContext(text, context),
    diagnostics: () => logs.map(l => JSON.parse(l.replace('PERF_FAILURE ', ''))) };
}

test('successes stay silent and failures preserve counters, checks and safe metadata', () => {
  const secret = 'private-password Cookie=secret CSRF=secret jane@private.test';
  const h = harness(n => n === 1 ? { value: {} } : n === 2
    ? { status: 0, error: 'request timeout ' + secret, error_code: 1050, value: { secret } }
    : { value: { secret } });
  h.run("request('cart_read', 'GET', '/api/cart', null, 200, () => true)");
  assert.equal(h.logs.length, 0);
  h.run("request('cart_read', 'GET', '/api/cart', null, 200, () => true)");
  h.run("request('cart_read', 'GET', '/api/cart', null, 200, () => false)");
  assert.deepEqual(h.metrics.cart_read_attempts, [1, 1, 1]);
  assert.deepEqual(h.metrics.cart_read_errors, [false, true, true]);
  assert.deepEqual(h.metrics.contract_errors, [false, false, true]);
  assert.deepEqual(h.metrics.measured_requests, [1, 1, 1]);
  const [timeout, contract] = h.diagnostics();
  assert.equal(timeout.transport_error, 'request_timeout');
  assert.equal(timeout.transport_error_code, 1050);
  assert.equal(timeout.received_status, 0);
  assert.equal(timeout.expected_status, 200);
  assert.equal(timeout.http_status_failed, true);
  assert.equal(timeout.contract_failed, false);
  assert.equal(contract.contract_failed, true);
  assert.equal(contract.contract_check, 'cart_read_response_contract');
  assert.equal(contract.response_ms, 123);
  assert.equal(contract.vu, 1); assert.equal(contract.iteration, 0);
  assert.ok(!Number.isNaN(Date.parse(contract.timestamp)));
  assert.ok(!h.logs.join('\n').includes(secret));
  assert.ok(!h.logs.join('\n').includes('jane@private.test'));
});

test('dependency failures remain failed metrics, including missing address creation result', () => {
  const h = harness((n, { method, url }) => {
    if (method === 'POST') return { status: 0, error: 'request timeout', value: null };
    if (method === 'PUT' && url.includes('/favorites/')) return { status: 500, value: {} };
    if (url.endsWith('/api/cart')) return { value: { items: [] } };
    if (url.endsWith('/api/favorites') || url.endsWith('/api/addresses')) return { value: [] };
    if (url.endsWith('/api/account/profile')) {
      const profile = { email: 'fixture@example.test', version: method === 'GET' ? 0 : 1, promotions: method !== 'GET' };
      return { value: method === 'GET' ? profile : { profile } };
    }
    return { value: null };
  });
  h.run("workload({accounts:[{email:'fixture@example.test',headers:{},cookies:{}}]})");
  assert.equal(h.calls.length, 9); // Original conditional skips update/delete after failed cart creation.
  assert.equal(h.metrics.measured_requests.length, 9);
  assert.equal(h.metrics.measured_errors.filter(Boolean).length, 6);
  assert.equal(h.metrics.contract_errors.filter(Boolean).length, 3);
  assert.equal(h.run('failed'), true);
  assert.deepEqual(h.metrics.flow_errors, [true]);
  assert.deepEqual(h.metrics.completed_flows, [0]);
  assert.deepEqual(h.metrics.started_flows, [1]);
  assert.deepEqual(h.metrics.finished_flows, [1]);
  const diagnostics = h.diagnostics();
  const address = diagnostics.find(d => d.operation === 'address_read');
  assert.equal(address.contract_failed, true);
  assert.equal(address.dependency_condition_failed, true);
  assert.equal(address.dependency_impact, 'required_value_unavailable');
  assert.equal(address.prior_failed_operations[0].operation, 'address_create');
  assert.ok(address.failed_contract_conditions.includes('created_address_available'));
  const favorite = diagnostics.find(d => d.operation === 'favorite_read');
  assert.equal(favorite.prior_failed_operations[0].operation, 'favorite_save');
  assert.equal(favorite.dependency_impact, 'prior_failure_effect_unknown');
  assert.equal(diagnostics.find(d => d.operation === 'cart_read').prior_failed_operations[0].operation, 'cart_create');
});

test('failed cart cleanup is visible in later iterations; successful cleanup clears uncertainty', () => {
  let ok = false;
  const h = harness(() => ({ status: ok ? 204 : 0, error: ok ? '' : 'request timeout', value: null }));
  h.run("request('cart_delete', 'DELETE', '/api/cart/items/1', null, 204, () => true)");
  h.run("__ITER = 1; request('cart_read', 'GET', '/api/cart', null, 200, () => true, false, {dependencies:Object.values(cartFailures)})");
  const d = h.diagnostics().at(-1);
  assert.equal(d.prior_failed_operations[0].operation, 'cart_delete');
  assert.equal(d.prior_failed_operations[0].iteration, 0);
  ok = true;
  h.run("request('cart_delete', 'DELETE', '/api/cart/items/1', null, 204, () => true)");
  assert.equal(h.run('Object.keys(cartFailures).length'), 0);
});

test('approval settings and complete successful workload remain unchanged', () => {
  const h = harness((n, { method, url, body }) => {
    if (url.endsWith('/api/cart/items')) return { status: 201, value: { id: 9, productId: 101, quantity: 1 } };
    if (url.endsWith('/api/cart')) return { value: { items: [{ productId: 101 }] } };
    if (method === 'PATCH') return { value: { quantity: 2 } };
    if (method === 'DELETE' && url.includes('/cart/')) return { status: 204, value: null };
    if (url.endsWith('/api/favorites')) return { value: [{ id: 101 }] };
    if (url.endsWith('/api/account/profile')) return { value: method === 'GET'
      ? { email: 'fixture@example.test', version: 0, promotions: false }
      : { profile: { email: 'fixture@example.test', version: 1, promotions: true } } };
    if (url.endsWith('/api/addresses')) return method === 'POST'
      ? { status: 201, value: { id: 20, recipientName: 'Carga 1' } }
      : { value: [{ id: 20, recipientName: 'Carga 1' }, { id: 1, recipientName: 'Fixture 1' }] };
    return { value: null };
  });
  h.run("workload({accounts:[{email:'fixture@example.test',headers:{},cookies:{}}]})");
  assert.equal(h.calls.length, 11); assert.equal(h.logs.length, 0);
  assert.equal(h.metrics.measured_requests.length, 11);
  assert.equal(h.metrics.measured_errors.filter(Boolean).length, 0);
  assert.deepEqual(h.metrics.completed_flows, [1]);
  assert.deepEqual(h.metrics.started_flows, [1]); assert.deepEqual(h.metrics.finished_flows, [1]);
  assert.ok(h.calls.every(c => c.params.timeout === '10s'));
  const options = JSON.parse(h.run('JSON.stringify(options)'));
  assert.deepEqual(options.scenarios.workload, { executor: 'constant-vus', vus: 100, duration: '70s', gracefulStop: '20s' });
  assert.deepEqual(options.thresholds.flow_ms, ['p(95)<=4000']);
  assert.deepEqual(options.thresholds.contract_errors, ['rate==0']);
  assert.deepEqual(options.thresholds.flow_errors, ['rate<0.02']);
  assert.deepEqual(options.thresholds.cart_read_ms, ['p(95)<=2000']);
  assert.deepEqual(options.thresholds.cart_read_successes, ['count>=20']);
  const data = { setup_data: { cookies: 'private', csrf: 'private' }, metrics: { untouched: true } };
  h.context.summaryInput = data;
  const summary = JSON.parse(h.run('Object.values(handleSummary(summaryInput))[0]'));
  assert.equal(summary.setup_data, undefined);
  assert.deepEqual(summary.metrics, { untouched: true });
});
