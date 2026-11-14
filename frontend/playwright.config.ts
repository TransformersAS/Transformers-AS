import { defineConfig, devices } from '@playwright/test';

const port = process.env.PLAYWRIGHT_PORT || '4300';

export default defineConfig({
  testDir: './e2e',
  testIgnore: '**/push-browser.spec.ts',
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Estas suites simulan HTTP con page.route; un service worker puede eludirlo.
    serviceWorkers: 'block',
  },

  webServer: {
    // Servir los bytes de producción: Vite transforma módulos e invalida SRI.
    // Ejecutar npm run build antes de Playwright (CI ya lo hace).
    command: `node e2e-security-server.cjs`,
    env: { SECURITY_E2E_PORT: port },
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 120000,
  },

  projects: [
    {
      name: 'chrome',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
      },
    },
    {
      name: 'edge',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'msedge',
      },
    },
    {
      name: 'mobile',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
        viewport: { width: 390, height: 844 },
        hasTouch: true,
      },
    },
  ],
});
