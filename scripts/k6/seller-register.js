import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const registerDuration = new Trend('seller_register_duration', true);
const registerFailed = new Rate('seller_register_failed');

export const options = {
  vus: 5,
  iterations: 20,
  thresholds: {
    seller_register_duration: ['p(95)<3000'],
    seller_register_failed: ['rate<0.02'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export default function () {
  const csrf = http.get(`${BASE_URL}/api/auth/csrf`);

  if (csrf.status !== 200) {
    console.error(`GET /api/auth/csrf: status=${csrf.status} body=${csrf.body}`);
    registerFailed.add(true);
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
  registerFailed.add(response.status !== 201);

  if (response.status !== 201) {
    console.error(`POST /api/sellers/register: status=${response.status} body=${response.body}`);
  }

  check(response, {
    'seller registration returns 201': (r) => r.status === 201,
  });
}
