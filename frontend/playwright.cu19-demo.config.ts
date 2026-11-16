import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

// Recorrido visual de CU-19 con API controlada; las reglas reales están en las pruebas del paquete returns del backend.
const port = '4451';
export default defineConfig({
  ...base,
  testDir: './e2e-cu19-demo',
  testMatch: 'devolucion-demo.spec.ts',
  workers: 1,
  timeout: 300000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-cu19-demo', open: 'never' }]],
  outputDir: '../artifacts/playwright-cu19-demo',
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
