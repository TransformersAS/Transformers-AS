import { defineConfig, devices } from '@playwright/test';

// Started and seeded by scripts/cu08-real-e2e.sh. Separate from API-mocked UI tests.
export default defineConfig({
  testDir: './e2e-real',
  workers: 1,
  retries: 0,
  outputDir: 'test-results-real',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report-real', open: 'never' }]],
  use: {
    baseURL: process.env['CU08_REAL_BASE_URL'] ?? 'http://127.0.0.1:14300',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
