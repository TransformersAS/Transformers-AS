import { defineConfig, devices } from '@playwright/test';

// Requiere el Compose demo recién iniciado; consume el pedido provisionado, sin seeds ni mocks desde el test.
export default defineConfig({
  testDir: './e2e-demo', workers: 1, retries: 0, timeout: 90_000,
  outputDir: 'test-results-demo', reporter: 'list',
  use: { baseURL: process.env['DEMO_BASE_URL'] ?? 'http://localhost:4300',
    serviceWorkers: 'block', trace: 'retain-on-failure' },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
