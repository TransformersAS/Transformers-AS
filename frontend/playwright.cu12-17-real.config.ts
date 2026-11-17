import { defineConfig, devices } from '@playwright/test';

/**
 * Demos de CU-12 a CU-17 contra la aplicación REAL que ya está corriendo (por defecto el Docker local en el puerto
 * 4300), sin servidor propio ni API simulada: lo que hacen queda en MySQL. Se lanzan con scripts/demos/cuXX-real.sh.
 */
const rapida = process.env['DEMO_RAPIDA'] === '1';
export default defineConfig({
  testDir: './e2e-cu12-17-real',
  workers: 1,
  timeout: 300000,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-cu12-17-real', open: 'never' }]],
  outputDir: '../artifacts/playwright-cu12-17-real',
  use: {
    baseURL: process.env['DEMO_URL'] || 'http://localhost:4300',
    headless: rapida,
    actionTimeout: 30000,
    launchOptions: { slowMo: rapida ? 0 : 600 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
