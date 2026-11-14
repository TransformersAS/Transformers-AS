import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: './e2e-cu10', workers: 1, retries: 0, timeout: 60000,
  outputDir: 'test-results-cu10', reporter: [['list'], ['html', {outputFolder:'playwright-report-cu10',open:'never'}]],
  use: {baseURL:'http://127.0.0.1:14310',serviceWorkers:'block',trace:'retain-on-failure'},
  projects: [{name:'chrome',use:{...devices['Desktop Chrome'],channel:'chrome'}}],
  webServer: {command:'node e2e-cu10/server.cjs',url:'http://127.0.0.1:14310',reuseExistingServer:false,timeout:15000}
});
