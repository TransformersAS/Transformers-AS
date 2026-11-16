import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// Recorrido continuo de recomendaciones con API controlada.
const port = '4442';
export default defineConfig({
  ...base,
  testDir: './e2e-cu02-demo',
  testMatch: 'recomendaciones-demo.spec.ts',
  workers: 1,
  timeout: 180000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-cu02-demo', open: 'never' }]],
  outputDir: '../artifacts/playwright-cu02-demo',
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
  projects: base.projects?.filter(project => project.name === 'chrome'),
});
