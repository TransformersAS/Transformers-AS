import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

const registerDuration = new Trend('seller_register_duration', true);
const registerErrorRate = new Rate('seller_register_error_rate');
const registerThroughput = new Counter('seller_register_throughput');

const vus = Number(__ENV.VUS || 50);
const duration = __ENV.DURATION || '1m';
const p95LimitMs = Number(__ENV.P95_LIMIT_MS || 4000);
const errorRateLimit = Number(__ENV.ERROR_RATE_LIMIT || 0.02);

export const options = {
  scenarios: {
    seller_registration: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // ASR global: P95 <= 4 s y tasa de error < 2 % con 100 usuarios.
    seller_register_duration: [`p(95)<=${p95LimitMs}`],
    seller_register_error_rate: [`rate<${errorRateLimit}`],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export default function () {
  const csrf = http.get(`${BASE_URL}/api/auth/csrf`);

  if (csrf.status !== 200) {
    console.error(`GET /api/auth/csrf: status=${csrf.status} body=${csrf.body}`);
    registerErrorRate.add(true);
    return;
  }

  const token = csrf.json('token');
  const headerName = csrf.json('headerName');

  const unique = `${__VU}-${__ITER}-${Date.now()}`;

  const response = http.post(
    `${BASE_URL}/api/sellers/register`,
    JSON.stringify({
      email: `k6-${unique}@test.local`,
      password: 'K6Marketplace123!',
      storeName: `K6 Store ${unique}`,
      acceptTerms: true,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        [headerName]: token,
      },
    }
  );

  registerDuration.add(response.timings.duration);
  registerErrorRate.add(response.status !== 201);
  if (response.status === 201) {
    registerThroughput.add(1);
  }

  if (response.status !== 201) {
    console.error(`POST /api/sellers/register: status=${response.status} body=${response.body}`);
  }

  check(response, {
    'seller registration returns 201': (r) => r.status === 201,
  });
}
