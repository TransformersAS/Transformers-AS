import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

// Recorrido visual de CU-18 con API controlada; las reglas reales están en las pruebas de la tienda del vendedor en el backend.
const port = '4452';
export default defineConfig({
  ...base,
  testDir: './e2e-cu18-demo',
  testMatch: 'mi-tienda-demo.spec.ts',
  workers: 1,
  timeout: 300000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-cu18-demo', open: 'never' }]],
  outputDir: '../artifacts/playwright-cu18-demo',
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
