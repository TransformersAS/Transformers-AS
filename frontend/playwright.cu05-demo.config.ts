import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

// Mismas pruebas funcionales; solo cambia la presentación para la sustentación.
const port = '4438';
export default defineConfig({
  ...base,
  testMatch: 'resenas.spec.ts',
  workers: 1,
  timeout: 120000,
  reporter: 'list',
  use: {
    ...base.use,
    baseURL: `http://127.0.0.1:${port}`,
    headless: false,
    launchOptions: { slowMo: 700 },
  },
  webServer: {
    command: `npm start -- --host 127.0.0.1 --port ${port} --live-reload=false --hmr=false`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 120000,
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
