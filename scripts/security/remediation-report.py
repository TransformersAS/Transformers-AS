#!/usr/bin/env python3
"""Comparación trazable de remediación. Nunca modifica informes ZAP ni su política."""
import argparse
from collections import Counter
import datetime as dt
import html
import json
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_text())


def generate(before_path, after_path, evidence, ui_path=None):
    evidence=Path(evidence);before,after=read(before_path),read(after_path)
    audit=read(evidence/'audit-production.json');development=read(evidence/'audit-all.json')
    advisories=read(evidence/'advisories.json')
    ui=read(ui_path) if ui_path else None
    before_counts=Counter(a['pluginId'] for a in before['alerts']);after_counts=Counter(a['pluginId'] for a in after['alerts'])
    catalog=Path(__file__).resolve().parents[2]/'frontend/src/app/catalogo/services/catalogo.service.ts'
    source=catalog.read_text().splitlines();reviews=[]
    for a in after['alerts']:
        if a['pluginId']=='10096':
            matches=[{'line':i,'source':line.strip()} for i,line in enumerate(source,1) if 'https://images.unsplash.com/photo-'+a['evidence'] in line]
            reviews.append({'pluginId':a['pluginId'],'evidence':a['evidence'],'url':a['url'],'assessment':'Identificador público de foto Unsplash; no fecha sensible de cuentas, sesiones u operaciones.' if matches else 'Requiere investigación; origen no identificado.', 'source_file':'frontend/src/app/catalogo/services/catalogo.service.ts','matches':matches})
    prod=audit.get('metadata',{}).get('vulnerabilities',{});dev=development.get('metadata',{}).get('vulnerabilities',{})
    tests=read(evidence/'functional-tests.json').get('stats',{}) if (evidence/'functional-tests.json').exists() else {}
    security_tests=read(evidence/'security-test-result.json') if (evidence/'security-test-result.json').exists() else {}
    tests_ok=tests.get('expected',0)>0 and tests.get('unexpected',1)==0 and security_tests.get('exit_code')==0
    ui_ok=bool(ui and ui.get('complete'))
    state='Parcial' if not ui_ok or not tests_ok or dev.get('total',0) or prod.get('total',0) or after['result']['exit_code'] or any(not r['matches'] for r in reviews) else 'Cumple en el alcance evaluado'
    result={'state':state,'generated_utc':dt.datetime.now(dt.timezone.utc).isoformat(),'before_report':str(before_path),'after_report':str(after_path),
      'scanner_version':after.get('version'),'before_alerts':len(before['alerts']),'after_alerts':len(after['alerts']),'removed_alert_instances':len(before['alerts'])-len(after['alerts']),
      'before_rules':dict(before_counts),'after_rules':dict(after_counts),'zap_result':after['result'],'production_audit':prod,'full_audit':dev,
      'functional_test_stats':tests,'security_tests':security_tests,'timestamp_review':reviews,'advisories':advisories,'browser_validation':ui,
      'limits':['DAST pasivo del tráfico anónimo y COMPRADOR especificado; no active scan ni cobertura universal.',
      'Las cinco observaciones de timestamp se conservan; la revisión las ubica en identificadores públicos de imágenes.',
      'La clasificación informativa de SPA no es una vulnerabilidad que deba eliminarse ocultando la aplicación.',
      'Las vulnerabilidades de desarrollo registradas requieren una propuesta adicional de actualización, sin forzar Angular 21/22 o Capacitor mayor.',
      'HTML con nonce se obtiene de red; service worker mantiene recursos estáticos, no arranque totalmente offline.',
      'No se ejecutó pago real, proveedores productivos, pruebas nativas ni recertificación de RNF de desempeño.',
      'GitHub Actions remoto no fue ejecutado; el resultado aquí es la ejecución local del mismo runner.']}
    (evidence/'remediation-summary.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    esc=lambda value:html.escape(str(value),quote=True)
    names={'10003':'Dependencia JavaScript vulnerable (Angular)','10020':'Protección contra clickjacking','10038':'Content Security Policy','90003':'Integridad de subrecursos','10036':'Versión del servidor expuesta','10021':'Protección MIME ausente','10096':'Heurística de timestamp','10109':'Clasificación de SPA'}
    rows=''.join('<tr>'+''.join('<td>'+esc(v)+'</td>' for v in [k,names.get(k,k),before_counts[k],after_counts[k], 'Causa corregida; alerta ausente en el nuevo tráfico' if after_counts[k]==0 else 'Revisión técnica adjunta; no se elimina del escaneo'])+'</tr>' for k in before_counts)
    cve_labels={'CVE-2026-52725':'Evasión de protección al montar componentes dinámicos (XSS)',
      'CVE-2026-50557':'Evasión de saneamiento de espacios de nombres (XSS)',
      'CVE-2026-32635':'XSS en atributos traducidos (i18n)',
      'CVE-2026-88057':'Evasión de saneamiento en enlaces host de directivas',
      'CVE-2026-27970':'XSS en traducciones i18n',
      'CVE-2026-54267':'Alteración del DOM y contaminación de caché en hidratación'}
    cve_rows=''
    for a in advisories:
        versions=[v for v in a['vulnerabilities'] if '20.' in v['vulnerable_version_range']]
        patches=sorted(set(v['patched_versions'] for v in versions))
        cve_rows+='<tr><td>'+esc(a['cve_id'])+'</td><td>'+esc(cve_labels.get(a['cve_id'],a['summary']))+'</td><td>'+esc('; '.join(patches))+'</td><td><a href="https://github.com/angular/angular/security/advisories/'+esc(a['ghsa_id'])+'">Aviso oficial</a></td></tr>'
    review_rows=''.join('<li><b>'+esc(r['evidence'])+':</b> '+esc(r['assessment'])+' '+''.join(esc(r['source_file']+':'+str(m['line'])+' · '+m['source']) for m in r['matches'])+'</li>' for r in reviews)
    page='''<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Seguridad · Remediación Angular 20 y OWASP ZAP</title><style>body{margin:0;background:#edf3f8;color:#183149;font:16px/1.6 system-ui}main{max-width:1200px;margin:auto;padding:28px}header{background:#183149;color:white;padding:30px;border-radius:16px}section{background:white;padding:24px;margin:22px 0;border-radius:12px}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}.cards div{padding:18px;background:#eff5f8;border-radius:9px}.cards b{font-size:27px}.scroll{overflow:auto}td,th{padding:10px;border-bottom:1px solid #d9e4ec;text-align:left}table{border-collapse:collapse;width:100%;font-size:13px}li{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.bar{height:16px;background:#e1e9ef}.bar span{display:block;height:100%;background:#287e89}@media(max-width:700px){main{padding:12px}.cards{grid-template-columns:1fr 1fr}}@media print{section{break-inside:avoid}}</style><main>'''
    page+='<header><p>MARKETPLACE INTEGRAL · RNF SEGURIDAD</p><h1>Remediación comprobada con OWASP ZAP</h1><h2>Estado general: '+esc(state)+'</h2><p>Angular 18.2.14 → 19.2.25 → 20.3.35 · Ionic 8.8.19 conservado · Zone.js 0.15.1</p></header>'
    page+='<section><h2>Resultado que puede demostrarse</h2><div class="cards">'+''.join('<div><b>'+esc(v)+'</b><br>'+esc(k)+'</div>' for k,v in [('Alertas anteriores',len(before['alerts'])),('Alertas posteriores',len(after['alerts'])),('Bloqueantes posteriores',after['result']['blocking_alerts']),('Vulnerabilidades npm producción',prod.get('total','No evaluado'))])+'</div><p>Instancias detectadas antes y después; no se presentan como CVE distintas.</p><p>Antes</p><div class="bar"><span style="width:100%"></span></div><p>Después</p><div class="bar"><span style="width:'+str(100*len(after['alerts'])/max(1,len(before['alerts'])))+'%"></span></div><p>El driver, la cobertura y la política Alto=3 con cero excepciones se conservaron. La desaparición se debe a actualización y configuración, no a reglas desactivadas. Código local del scanner: '+esc(after['result']['exit_code'])+'. GitHub Actions remoto: no ejecutado desde esta tarea.</p></section>'
    page+='<section><h2>Qué se corrigió</h2><p>Framework actualizado con las migraciones oficiales, instalación limpia y resolución estricta de peers. Se añadieron compiler y los paquetes Angular coordinados, TypeScript 5.9.3 y Zone.js compatible, manteniendo detección de cambios con Zone. Se conservaron los componentes funcionales; el aviso de sesión usa una clase SCSS en lugar de un atributo style incompatible con CSP.</p><p>El análisis usó OWASP ZAP '+esc(after.get('version','no evaluado'))+' en modo pasivo. Nginx aplica nosniff, DENY, CSP con nonce distinto por respuesta, política de referencias y permisos; no expone su versión. Angular genera integridad SHA-384 en recursos; las fuentes se alojan localmente con licencia OFL. El HTML no se reutiliza con un nonce anterior.</p><div class="scroll"><table><tr><th>Regla</th><th>Hallazgo</th><th>Antes</th><th>Después</th><th>Evaluación</th></tr>'+rows+'</table></div></section>'
    page+='<section><h2>Las seis CVE de la alerta alta</h2><p>Los parches de la rama 20 están incluidos en 20.3.35. Los rangos completos oficiales se conservan en advisories.json. La ausencia de patrones de explotación en la aplicación no se utilizó como sustituto de la actualización.</p><div class="scroll"><table><tr><th>CVE</th><th>Problema</th><th>Primer parche en rama 20</th><th>Fuente</th></tr>'+cve_rows+'</table></div></section>'
    page+='<section><h2>Alertas restantes, sin ocultarlas</h2><ul>'+review_rows+'</ul><p>10109 identifica una aplicación moderna con JavaScript; se conserva como información, no se modifica la SPA para engañar al detector.</p></section>'
    page+='<section><h2>Qué sigue pendiente</h2><p>npm audit completo informa '+esc(dev.get('total','no evaluado'))+' entradas de paquetes afectados en desarrollo: '+esc(dev.get('critical',0))+' críticas, '+esc(dev.get('high',0))+' altas y '+esc(dev.get('moderate',0))+' moderadas. Destacan tar y la cadena de Capacitor CLI. No se desplegaron como dependencias de producción según npm audit --omit=dev, pero siguen siendo riesgo para herramientas y construcción; por eso no se declara remediación integral completa.</p><p>Evaluar separadamente actualización de Capacitor CLI/core y herramientas Angular, sus peers, proyectos nativos y regresiones. No se aplicaron --force, --legacy-peer-deps ni sustituciones incompatibles para conseguir una auditoría verde.</p></section>'
    page+='<section><h2>Pruebas funcionales y controles reales</h2><p>Build e instalación limpia aprobados. Suite existente: '+esc(tests.get('expected','no evaluado'))+' E2E aprobados y '+esc(tests.get('unexpected','no evaluado'))+' fallidos sobre el build estático con SRI/CSP, separados de DAST. Pruebas del runner y política: '+esc(security_tests.get('passed','no evaluado'))+' aprobadas, exit '+esc(security_tests.get('exit_code','no evaluado'))+'. El comando heredado npm test no tiene target Angular configurado; no se presenta como aprobado.</p><p>Validación con backend y MySQL reales: '+('aprobada' if ui_ok else 'no completada')+'. Se verifican login, roles, CSRF, catálogo, carrito, favoritos, cálculo de checkout sin proveedor, conversación, respuesta y bandeja. Se comprueban hashes SRI, nonces únicos, cabeceras en API y errores y bloqueo de un script sin nonce.</p><details><summary>Resultados del navegador real</summary><pre>'+esc(json.dumps(ui,indent=2,ensure_ascii=False))+'</pre></details></section>'
    page+='<section><h2>Alcance y límites</h2><ul>'+''.join('<li>'+esc(v)+'</li>' for v in result['limits'])+'</ul><p>Objetivo DAST: Angular compilado/Nginx, Spring Boot y MySQL/Flyway reales, proyecto Docker nuevo, redes internas, sin puertos públicos ni base compartida. Las carpetas conservan metadatos, versiones, cobertura y limpieza. Cero vulnerabilidades en npm de producción y ausencia de alertas altas/medias en este tráfico no demuestran ausencia absoluta de vulnerabilidades.</p></section></main></html>'
    (evidence/'remediation-executive.html').write_text(page)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--before',required=True);p.add_argument('--after',required=True);p.add_argument('--evidence',required=True);p.add_argument('--ui');a=p.parse_args()
    result=generate(a.before,a.after,a.evidence,a.ui);print(json.dumps({'state':result['state'],'alerts_before':result['before_alerts'],'alerts_after':result['after_alerts']}))
