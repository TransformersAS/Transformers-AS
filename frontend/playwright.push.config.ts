import { defineConfig, devices } from '@playwright/test';
const port=process.env.PUSH_TEST_PORT || '4321';
export default defineConfig({
  testDir:'./e2e',testMatch:'push-browser.spec.ts',workers:1,
  use:{baseURL:`http://127.0.0.1:${port}`,serviceWorkers:'allow',trace:'retain-on-failure'},
  webServer:{command:'node e2e/support/push-server.mjs',url:`http://127.0.0.1:${port}`,reuseExistingServer:false,timeout:30000},
  projects:[{name:'chrome',use:{...devices['Desktop Chrome'],channel:'chrome'}}]
});
