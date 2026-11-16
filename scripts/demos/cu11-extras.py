#!/usr/bin/env python3
"""CU11 extras: tres entornos propios para modos de reembolso reales del adaptador simulado."""
import argparse,json,os,re,subprocess,sys,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('--slow-ms',type=int,required=True);p.add_argument('--headless',action='store_true');args=p.parse_args()
 out=ROOT/'artifacts/cu11-groups'/('group-'+uuid.uuid4().hex[:12]);out.mkdir(parents=True,exist_ok=False);started=time.monotonic();rows=[]
 print('CU11 extras: '+str(out.relative_to(ROOT)),flush=True)
 for part in ['base','pending','error']:
  command=[sys.executable,str(ROOT/'scripts/demos/run.py'),'cu11','--extras','--cu11-group-part',part,'--slow-ms',str(args.slow_ms)]
  if args.headless:command.append('--headless')
  lines=[]
  with (out/(part+'.log')).open('w') as log:
   with subprocess.Popen(command,cwd=ROOT,env=os.environ.copy(),text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=1) as child:
    for line in child.stdout:lines.append(line);log.write(line);log.flush();print(line,end='',flush=True)
    child.wait(timeout=2600)
  found=re.findall(r'sust-cu11-[0-9a-f]{12}',''.join(lines));rows.append({'part':part,'run':found[0] if found else None,'exit_code':child.returncode})
  (out/'group-summary.json').write_text(json.dumps({'parts':rows,'elapsed_seconds':round(time.monotonic()-started,3),'complete':len(rows)==3,'passed':len(rows)==3 and all(r['exit_code']==0 for r in rows),'orchestrator_owns_no_docker_project':True},indent=2)+'\n')
 (out/'report.html').write_text('<!doctype html><html lang="es"><meta charset="utf-8"><title>CU11 extras</title><h1>CU11: escenarios en tres entornos aislados</h1><p>Adaptadores simulados existentes; no devoluciones bancarias.</p><ul>'+''.join('<li>'+r['part']+': '+str(r['exit_code'])+' · <a href="../../sustentacion/'+r['run']+'/report.html">Resultados</a></li>' for r in rows if r['run'])+'</ul></html>')
 return 0 if all(r['exit_code']==0 for r in rows) else 1
if __name__=='__main__':sys.exit(main())
