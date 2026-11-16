import { defineConfig } from '@playwright/test';
import shared from './playwright.sustentacion.config';
if(process.env['SUST_CU']!=='cu08') throw new Error('Configuración exclusiva CU08');
export default defineConfig({
  ...shared,
  testMatch: process.env['SUST_CU08_EXTRAS']==='1' ? 'cu08-extras.spec.ts' : 'cu08.spec.ts',
  timeout: 300_000,
  reporter: [['./e2e-sustentacion/cu08-reporter.ts']],
  use: {...shared.use, viewport:{width:1440,height:1000},
    launchOptions:{...shared.use?.launchOptions,args:[...(shared.use?.launchOptions?.args || []),'--window-size=1500,1100']},
  },
});
