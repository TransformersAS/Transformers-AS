import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

// Recorrido continuo controlado; conserva las regresiones originales separadas.
const port = '4440';
export default defineConfig({
  ...base,
  testDir: './e2e-cu06-demo',
  testMatch: 'direcciones-demo.spec.ts',
  workers: 1,
  timeout: 240000,
  outputDir: '../artifacts/playwright-cu06-demo',
  reporter: 'list',
  use: {
    ...base.use,
    baseURL: `http://127.0.0.1:${port}`,
    headless: false,
    launchOptions: { slowMo: 1000 },
  },
  webServer: {
    command: 'npm run build && node e2e-security-server.cjs',
    env: { SECURITY_E2E_PORT: port },
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 180000,
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
