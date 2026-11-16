import { defineConfig } from '@playwright/test';
import shared from './playwright.sustentacion.config';
if(process.env['SUST_CU']!=='cu07') throw new Error('Configuración exclusiva CU07');
export default defineConfig({
  ...shared,
  testMatch: process.env['SUST_CU07_EXTRAS']==='1' ? 'cu07-extras.spec.ts' : 'cu07.spec.ts',
  timeout: 300_000,
  reporter: [['./e2e-sustentacion/cu07-reporter.ts']],
  use: {...shared.use, viewport:{width:1440,height:1000},
    launchOptions:{...shared.use?.launchOptions,args:[...(shared.use?.launchOptions?.args || []),'--window-size=1500,1100']},
  },
});
