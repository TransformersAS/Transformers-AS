import { defineConfig, devices } from '@playwright/test';
const { origin } = require('./cu03-real-support.cjs');

// Opt-in: navegador y backend reales, sin mocks ni secretos en trazas.
export default defineConfig({
  testDir: './e2e-cu03-real', workers: 1, retries: 0, timeout: 15 * 60 * 1000,
  reporter: [['./cu03-private-reporter.cjs'], ['list']], outputDir: '../artifacts/cu03-sandbox-browser',
  use: {
    baseURL: origin(), headless: false, serviceWorkers: 'block',
    trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: { slowMo: 1000 },
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
