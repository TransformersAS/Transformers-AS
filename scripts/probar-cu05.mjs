import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, createWriteStream, readFileSync, copyFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// No lee .env ni altera los datos o contenedores de la demostración local.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const evidence=path.join(root,'artifacts','cu05',new Date().toISOString().replace(/[:.]/g,'-'));
mkdirSync(evidence,{recursive:true});
const results=[];
const steps=[
  ['backend','mvn -Pcu05-coverage clean verify -Dlogging.level.org.springframework.jdbc=WARN','backend/demo',{}],
  ['build','npm run build','frontend',{}],
  ['interfaz','npx playwright test e2e/resenas.spec.ts --project=chrome --workers=1 --reporter=list','frontend',{PLAYWRIGHT_PORT:'4437'}],
];
async function run([name,command,cwd,extra]) {
  console.log(`\nCU-05: ${name}`);
  const log=createWriteStream(path.join(evidence,`${name}.log`));
  const started=Date.now();
  const code=await new Promise(resolve=>{
    const windows=process.platform==='win32';
    const child=spawn(windows?'cmd.exe':'sh',windows?['/d','/s','/c',command]:['-c',command],{
      cwd:path.join(root,cwd),env:{...process.env,...extra},stdio:['ignore','pipe','pipe'],windowsHide:true
    });
    for(const stream of [child.stdout,child.stderr]) stream.on('data',data=>{log.write(data);process.stdout.write(data);});
    child.on('error',err=>{log.write(err.message);resolve(1);});
    child.on('close',value=>resolve(value??1));
  });
  await new Promise(resolve=>log.end(resolve));
  results.push({name,command,code,seconds:Math.round((Date.now()-started)/1000)});
  writeFileSync(path.join(evidence,'resultado.json'),JSON.stringify({date:new Date().toISOString(),results,
    scope:'MySQL real aislado; interfaz con API controlada. Valida rese?as, permisos, im?genes y respuestas; no modifica la base local.'},null,2));
  if(code!==0) throw new Error(`Falló ${name}. Evidencias: ${evidence}`);
  if(name==='backend') {
    const report=path.join(root,'backend/demo/target/site/jacoco-cu05/jacoco.xml');
    copyFileSync(report,path.join(evidence,'jacoco-cu05.xml'));
    const xml=readFileSync(report,'utf8');
    const packages=['reviews/application','reviews/infrastructure','reviews/infrastructure/web/controller'];
    const coverage=packages.map(suffix=>{
      const name=`com/transformersas/marketplace/${suffix}`;
      const body=[...xml.matchAll(/<package name="([^"]+)">([\s\S]*?)<\/package>/g)].find(m=>m[1]===name)?.[2];
      const counter=body && [...body.matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\s*\/>/g)].at(-1);
      if(!counter) throw new Error(`Falta cobertura de ${name}`);
      const missed=Number(counter[1]),covered=Number(counter[2]);
      const percent=100*covered/(covered+missed);
      if(!(percent>95)) throw new Error(`Cobertura insuficiente: ${name} ${percent}%`);
      console.log(`${suffix}: ${percent.toFixed(2)}% de líneas`);
      return {package:name,covered,missed,percent};
    });
    writeFileSync(path.join(evidence,'cobertura.json'),JSON.stringify(coverage,null,2));
  }
}
try {
  for(const step of steps) await run(step);
  console.log(`\nCU-05: todas las etapas pasaron. Evidencias: ${evidence}`);
} catch(error) {console.error(error.message);process.exitCode=1;}
