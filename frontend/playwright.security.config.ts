import { defineConfig, devices } from '@playwright/test';

// Reutiliza las suites de UI existentes, sin poder alcanzar el backend compartido.
// No sustituye el DAST ni la validación con API/MySQL reales.
const port = process.env['SECURITY_E2E_PORT'] || '45981';
export default defineConfig({
  testDir: './e2e', testIgnore: '**/push-browser.spec.ts', workers: 1, retries: 0,
  outputDir: '../artifacts/security-functional/test-results',
  reporter: [['list'], ['json', { outputFile: '../artifacts/security-functional/results.json' }]],
  use: { baseURL: `http://127.0.0.1:${port}`, serviceWorkers: 'block', trace: 'off' },
  webServer: {
    command: `node e2e-security-server.cjs`,
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120000,
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome', launchOptions: { args: ['--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost'] } } }],
});
