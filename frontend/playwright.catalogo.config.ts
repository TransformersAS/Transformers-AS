import { defineConfig, devices } from '@playwright/test';

// Pruebas de interfaz aisladas: API simulada, puerto separado del Compose local.
export default defineConfig({
  testDir: './e2e',
  testMatch: 'catalogo.spec.ts',
  outputDir: '../artifacts/playwright-catalogo',
  use: { baseURL: 'http://127.0.0.1:4401', trace: 'retain-on-failure', serviceWorkers: 'block' },
  webServer: {
    command: 'node node_modules/@angular/cli/bin/ng.js build --optimization=false && node tests/servir-catalogo.cjs',
    url: 'http://127.0.0.1:4401',
    reuseExistingServer: false,
    timeout: 120000
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }]
});
