import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',

  use: {
    baseURL: 'http://127.0.0.1:4300',
    trace: 'on-first-retry',
  },

  webServer: {
    // E2E must control navigation; dev-server reloads can destroy an open account panel.
    command: 'npm start -- --host 127.0.0.1 --live-reload=false --hmr=false',
    url: 'http://127.0.0.1:4300',
    reuseExistingServer: true,
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
