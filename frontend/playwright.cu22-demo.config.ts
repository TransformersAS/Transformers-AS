import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

// Recorrido visual de CU-22 con API controlada; las reglas reales están en AccountRestrictionIntegrationTests.
const port = '4450';
export default defineConfig({
  ...base,
  testDir: './e2e-cu22-demo',
  testMatch: 'restricciones-demo.spec.ts',
  workers: 1,
  timeout: 300000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-cu22-demo', open: 'never' }]],
  outputDir: '../artifacts/playwright-cu22-demo',
  use: {
    ...base.use,
    baseURL: `http://127.0.0.1:${port}`,
    headless: false,
    launchOptions: { slowMo: 700 },
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
