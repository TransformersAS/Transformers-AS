import {defineConfig} from '@playwright/test';
import shared from './playwright.sustentacion.config';
if(process.env['SUST_CU']!=='cu10')throw new Error('Configuración exclusiva CU10');
export default defineConfig({...shared,testMatch:process.env['SUST_CU10_EXTRAS']==='1'?'cu10-extras.spec.ts':'cu10.spec.ts',timeout:300000,
 reporter:[['./e2e-sustentacion/cu10-reporter.ts']],
 use:{...shared.use,viewport:{width:1440,height:1000},launchOptions:{...shared.use?.launchOptions,args:[...(shared.use?.launchOptions?.args||[]),'--window-size=1500,1100']}}});
