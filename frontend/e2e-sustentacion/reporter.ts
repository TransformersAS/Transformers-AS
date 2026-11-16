import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
// Lista blanca: nunca serializa requests, valores, cookies, errores o snippets de código.
export default class DemoReporter implements Reporter {
  rows: {scenario:string;status:string;duration_ms:number}[]=[];
  onTestEnd(test:TestCase,result:TestResult) {
    for(const attachment of result.attachments) {
      if(attachment.name === 'error-context' && attachment.path) writeFileSync(attachment.path, 'Contexto omitido para proteger valores de formularios y sesiones. Ver failure.png y ubicación en playwright.log.\n');
    }
    this.rows.push({scenario:test.title,status:result.status,duration_ms:result.duration});
    console.log(`${result.status}: ${test.title}`);
    // Sólo ubicación del fallo; valores de aserciones pueden contener credenciales.
    for(const error of result.errors) {
      const operation = (error.message || '').match(/(?:Error: )?((?:page|locator|apiRequestContext)\.[A-Za-z]+):/);
      if(operation) console.log(`Operación fallida: ${operation[1]}`);
      if(error.location) console.log(`Ubicación: ${error.location.file}:${error.location.line}`);
      for(const line of (error.stack || '').split('\n')) if(/^\s+at .*\.ts:\d+/.test(line)) console.log(line);
    }
  }
  onEnd(result:FullResult) {
    const out=process.env['SUST_REPORT_DIR']!;
    writeFileSync(join(out,'scenarios.json'),JSON.stringify({status:result.status,scenarios:this.rows},null,2));
    const escape=(s:string)=>s.replace(/[&<>"']/g,c=>`&#${c.charCodeAt(0)};`);
    writeFileSync(join(out,'report.html'),`<!doctype html><html lang="es"><meta charset="utf-8"><title>Playwright — sustentación</title><h1>Playwright: ${escape(result.status)}</h1><p>Chrome visible: ${process.env['SUST_HEADLESS']!=='1'}. Backend y MySQL reales; sin interceptaciones HTTP.</p><ul>${this.rows.map(r=>`<li>${escape(r.scenario)}: ${escape(r.status)} (${r.duration_ms} ms)</li>`).join('')}</ul><p><a href="screens/">Capturas</a> · <a href="database-witness.json">MySQL</a> · <a href="run.json">Ejecución</a></p></html>`);
  }
}
