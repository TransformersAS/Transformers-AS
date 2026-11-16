import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';
import shared from './playwright.sustentacion.config';
if(process.env['SUST_CU']!=='cu09') throw new Error('Configuración exclusiva CU09');
export default defineConfig({
  ...shared,
  testDir: process.env['SUST_CU09_REGRESSION']==='1' ? resolve(__dirname,'e2e-real') : shared.testDir,
  testMatch: process.env['SUST_CU09_REGRESSION']==='1' ? 'cu09-profile.spec.ts' : process.env['SUST_CU09_EXTRAS']==='1' ? 'cu09-extras.spec.ts' : 'cu09.spec.ts',
  timeout: 300_000,
  reporter: [['./e2e-sustentacion/cu09-reporter.ts']],
  use: {...shared.use, viewport:{width:1440,height:1000},
    launchOptions:{...shared.use?.launchOptions,args:[...(shared.use?.launchOptions?.args || []),'--window-size=1500,1100']},
  },
});
