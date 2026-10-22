import { defineConfig, devices } from '@playwright/test';

const port = process.env.PLAYWRIGHT_PORT || '4300';

export default defineConfig({
  testDir: './e2e',

  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'on-first-retry',
    // Estas suites simulan HTTP con page.route; un service worker puede eludirlo.
    serviceWorkers: 'block',
  },

  webServer: {
    // E2E must control navigation; dev-server reloads can destroy an open account panel.
    command: `npm start -- --host 127.0.0.1 --port ${port} --live-reload=false --hmr=false`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: !process.env.CI && !process.env.PLAYWRIGHT_PORT,
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
