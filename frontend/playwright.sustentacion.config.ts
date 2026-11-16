import { defineConfig, devices } from '@playwright/test';
const cu = process.env['SUST_CU'];
if (!/^cu(07|08|09|10|11)$/.test(cu || '') || !process.env['SUST_RUN']) throw new Error('Ejecutar mediante npm run demo:cu07...demo:cu11');
export default defineConfig({
  testDir: './e2e-sustentacion', testMatch: `${cu}.spec.ts`,
  workers: 1, retries: 0, timeout: 150_000,
  outputDir: process.env['SUST_OUTPUT']!,
  reporter: [['./e2e-sustentacion/reporter.ts']],
  use: {
    ...devices['Desktop Chrome'], channel: 'chrome',
    headless: process.env['SUST_HEADLESS'] === '1',
    baseURL: process.env['SUST_BASE_URL'], serviceWorkers: 'block',
    actionTimeout: 20_000, navigationTimeout: 30_000,
    trace: 'off', screenshot: 'off', video: 'off',
    launchOptions: {
      slowMo: Number(process.env['SUST_SLOW_MS'] || 350),
      args: ['--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost'],
    },
  },
});
