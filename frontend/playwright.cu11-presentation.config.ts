import {defineConfig} from '@playwright/test';
import shared from './playwright.sustentacion.config';
if(process.env['SUST_CU']!=='cu11')throw new Error('Configuración exclusiva CU11');
export default defineConfig({...shared,testMatch:process.env['SUST_CU11_EXTRAS']==='1'?'cu11-extras.spec.ts':'cu11.spec.ts',timeout:300000,
 reporter:[['./e2e-sustentacion/cu11-reporter.ts']],use:{...shared.use,viewport:{width:1440,height:1000},launchOptions:{...shared.use?.launchOptions,args:[...(shared.use?.launchOptions?.args||[]),'--window-size=1500,1100']}}});
