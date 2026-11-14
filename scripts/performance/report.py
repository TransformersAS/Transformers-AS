"""Evalúa evidencia k6 sin modificar umbrales ni convertir falta de muestras en éxito."""
import html
import json
from pathlib import Path


def values(data, name):
    item = data.get('metrics', {}).get(name, {})
    return item.get('values', item)


def assess(data, exit_code):
    config = data.get('performance', {})
    metrics = []
    span = values(data, 'measurement_elapsed_ms').get('max', 0) / 1000
    for name, (limit, rnf) in config.get('operations', {}).items():
        count = values(data, name + '_attempts').get('count', 0)
        success = values(data, name + '_successes').get('count', 0)
        latency = values(data, name + '_ms')
        latency_ok = values(data, name + '_success_ms')
        enough = success >= config.get('minimum_samples', 20) and count >= success and span > 0
        state = ('No evaluado' if not enough or 'p(95)' not in latency or 'p(95)' not in latency_ok else
                 'Cumple' if latency['p(95)'] <= limit and latency_ok['p(95)'] <= limit
                 and (count-success)/count < 0.02 else 'No cumple')
        metrics.append({'operation': name, 'rnf': rnf, 'limit_ms': limit,
                        'attempts': count, 'successes': success, 'errors': count-success,
                        'error_rate': (count-success)/count if count else None,
                        'avg_ms': latency.get('avg'), 'p95_ms': latency.get('p(95)'), 'max_ms': latency.get('max'),
                        'successful_p95_ms': latency_ok.get('p(95)'),
                        'operations_per_second': count/span if span else None, 'state': state})
    contract_error = values(data, 'contract_errors').get('rate', 0)
    participation = values(data, 'measured_vus').get('count', 0)
    started = values(data, 'started_flows').get('count', 0)
    finished = values(data, 'finished_flows').get('count', 0)
    valid = bool(metrics) and participation == config.get('vus') and started == finished and started > 0
    valid = valid and not contract_error and all(m['state'] != 'No evaluado' for m in metrics)
    errors = values(data, 'flow_errors').get('rate')
    p95 = values(data, 'flow_ms').get('p(95)')
    status = ('No evaluado' if not valid or exit_code not in (0, 99) else
              'Cumple' if exit_code == 0 and all(m['state'] == 'Cumple' for m in metrics)
              and errors is not None and errors < 0.02 and p95 is not None and p95 <= 4000 else 'No cumple')
    if not valid:
        for metric in metrics:
            metric['state'] = 'No evaluado'
    return {'scenario': config.get('scenario'), 'vus': config.get('vus'),
            'run_kind': config.get('run_kind'), 'minimum_samples': config.get('minimum_samples'),
            'warmup_seconds': config.get('warmup_seconds'), 'measurement_seconds': config.get('measurement_seconds'),
            'observed_measurement_span_seconds': span, 'k6_exit_code': exit_code,
            'state': status, 'valid_evidence': valid, 'measured_vus': participation,
            'flow_p95_ms': p95, 'flow_error_rate': errors,
            'flow_avg_ms': values(data, 'flow_ms').get('avg'),
            'measured_requests': values(data, 'measured_requests').get('count', 0),
            'http_requests_per_second': values(data, 'measured_requests').get('count', 0)/span if span else None,
            'metrics': metrics,
            'limitations': ['Solo HTTP; sin renderizado ni filtros Angular.', 'Una corrida breve no acredita estabilidad prolongada.',
                            'Preparación y calentamiento fuera de métricas personalizadas; HTTP general incluye otras fases.',
                            'El cierre de una iteración puede extender la ventana por gracefulStop; se informa duración observada.']}


def write_report(directory, metadata, runs):
    directory = Path(directory)
    document = {'metadata': metadata, 'runs': runs,
                'coverage': {'RNF-016': 'Parcial: listado HTTP; búsquedas, filtros y orden se ejecutan en Angular y no se cronometraron.',
                             'RNF-017': 'HTTP: detalle con variantes, reseñas e imágenes; no visualización en navegador.',
                             'RNF-018': 'CRUD carrito/favoritos; lectura y preferencias de perfil; alta/lectura de direcciones. No hay edición/borrado de direcciones en API.',
                             'RNF-024': 'Comparación 50/100 solo de los escenarios ejecutados; no de todos los CU.',
                             'RNF-019..023': 'No evaluados en esta implementación. Checkout legado no se ejecuta.'}}
    (directory/'performance-summary.json').write_text(json.dumps(document, indent=2, ensure_ascii=False)+'\n')
    esc = lambda x: html.escape(str(x), quote=True)
    def fmt(x): return '—' if x is None else f'{x:.2f}'
    comparisons = []
    scenario_labels = {'catalog': 'Catálogo y ficha de producto', 'resources': 'Carrito, favoritos, perfil y direcciones'}
    labels = {'catalog_list':'Listado de 1.000 productos','cart_create':'Agregar al carrito','cart_read':'Leer carrito',
              'cart_update':'Cambiar cantidad','cart_delete':'Retirar del carrito','favorite_save':'Guardar favorito',
              'favorite_read':'Leer favoritos','favorite_delete':'Quitar favorito','profile_read':'Leer perfil',
              'profile_preferences_update':'Actualizar preferencias','address_create':'Crear dirección','address_read':'Leer direcciones'}
    for size, spanish in [('small','pequeño'),('medium','mediano'),('large','grande')]:
        for prefix, label in [('detail_ready','Ficha HTTP completa'),('detail','Detalle y variantes'),('reviews','Reseñas'),('rating','Calificación'),('image','Descarga de imagen')]:
            labels[prefix+'_'+size]=label+' · producto '+spanish
    for kind in ('catalog', 'resources'):
        selected = [r for r in runs if r.get('scenario') == kind and r.get('run_kind') == 'measurement']
        by_vus = {r['vus']:r for r in selected}
        comparable = 50 in by_vus and 100 in by_vus and all(by_vus[v]['valid_evidence'] for v in (50,100))
        comparisons.append(f'<h3>{esc(scenario_labels[kind])}</h3>')
        if comparable:
            comparisons.append('<div class="scroll"><table><tr><th>VU</th><th>Media del recorrido ms</th><th>P95 ms</th><th>Error %</th><th>HTTP/s</th><th>Estado</th></tr>')
            for v in (50,100):
                r = by_vus[v]
                comparisons.append('<tr>'+''.join('<td>'+esc(x)+'</td>' for x in [v,fmt(r['flow_avg_ms']),fmt(r['flow_p95_ms']),fmt(r['flow_error_rate']*100),fmt(r['http_requests_per_second']),r['state']])+'</tr>')
            ratio = by_vus[100]['flow_p95_ms']/by_vus[50]['flow_p95_ms'] if by_vus[50]['flow_p95_ms'] else None
            comparisons.append('</table></div><p>Relación P95 de 100 frente a 50 VU: '+fmt(ratio)+' veces. Cada carga se ejecutó una vez; no es una estimación estadística de capacidad.</p>')
        else:
            comparisons.append('<p>No evaluado: falta una pareja válida 50/100.</p>')
    cards = []
    for run in runs:
        flow_error = run.get('flow_error_rate')
        rows = ''.join('<tr>'+''.join('<td>'+esc(x)+'</td>' for x in [
            labels.get(m['operation'],m['operation']),m['rnf'],m['attempts'],m['successes'],fmt(m['avg_ms']),fmt(m['p95_ms']),
            fmt(m['max_ms']),fmt(m['error_rate']*100 if m['error_rate'] is not None else None),m['limit_ms'],m['state']])+'</tr>' for m in run.get('metrics',[]))
        width = min(100,(run.get('flow_p95_ms') or 0)/4000*100)
        cards.append(f'<article><h2>{esc(scenario_labels.get(run["scenario"],run["scenario"]))} · {run["vus"]} usuarios virtuales</h2>'
                     f'<p><b>{esc(run["state"])}</b> · {esc(run["run_kind"])} · calentamiento {run.get("warmup_seconds")} s · medición prevista {run.get("measurement_seconds")} s</p>'
                     + ('<p>Validación pequeña de contratos. No acredita los RNF con 50/100 VU.</p>' if run.get('run_kind')=='smoke' else '')
                     +
                     f'<div class="cards"><div>Media del flujo<br><strong>{fmt(run.get("flow_avg_ms"))} ms</strong></div>'
                     f'<div>P95 del flujo<br><strong>{fmt(run.get("flow_p95_ms"))} ms</strong></div>'
                     f'<div>Errores del flujo<br><strong>{fmt(flow_error*100 if flow_error is not None else None)} %</strong></div>'
                     f'<div>HTTP medido<br><strong>{fmt(run.get("http_requests_per_second"))} solicitudes/s</strong></div></div>'
                     f'<p>P95 del flujo frente al límite de RNF-024: 4.000 ms</p><div class="track"><div style="width:{width:.2f}%"></div></div>'
                     + '<p>Recorrido: '+('listado, detalle, reseñas, calificación e imágenes.' if run['scenario']=='catalog' else 'carrito completo, favoritos, lectura y preferencias del perfil, alta y lectura de direcciones, ejecutados en secuencia. El límite de 2.000 ms se evalúa por operación; el de 4.000 ms se aplica a este recorrido completo, más exigente que cada funcionalidad aislada.')+'</p>'
                     + (('<p><b>Umbrales incumplidos:</b> '+esc(', '.join(m['operation'] for m in run.get('metrics',[]) if m['state']=='No cumple'))+'</p>') if any(m['state']=='No cumple' for m in run.get('metrics',[])) else '')
                     +
                     '<details><summary>Operaciones, muestras y umbrales</summary><div class="scroll"><table><thead><tr>'
                     +''.join('<th>'+x+'</th>' for x in ['Operación','RNF','Intentos','Éxitos','Media ms','P95 ms','Máximo ms','Error %','Límite P95 ms','Estado'])
                     +'</tr></thead><tbody>'+rows+'</tbody></table></div></details></article>')
    coverage = ''.join('<li><b>'+esc(k)+':</b> '+esc(v)+'</li>' for k,v in document['coverage'].items())
    page = '''<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Desempeño — Transformers-AS</title>
<style>body{background:#eff4f8;color:#183047;font:16px/1.6 system-ui;margin:0}main{max-width:1180px;margin:auto;padding:28px}header{background:#183047;color:white;padding:30px;border-radius:16px}article,section{background:white;padding:24px;margin:22px 0;border-radius:12px;border:1px solid #d8e1ea}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}.cards div{padding:14px;background:#f0f5f8;border-radius:8px}strong{font-size:20px}.track{height:14px;background:#e0e8ef}.track div{height:100%;background:#207e8b}table{border-collapse:collapse;width:100%;font-size:12px}td,th{padding:9px;border-bottom:1px solid #d8e1ea;text-align:left}.scroll{overflow:auto}summary{cursor:pointer;font-weight:bold}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}@media(max-width:650px){main{padding:12px}.cards{grid-template-columns:1fr 1fr}}@media print{body{background:white}article{break-inside:avoid}details::details-content{content-visibility:visible;block-size:auto}}</style><main><header><p>MARKETPLACE INTEGRAL · ASR-005</p><h1>Informe de desempeño</h1><p>Pruebas de carga con k6 contra aplicación y MySQL reales en un ambiente desechable.</p></header>'''
    dataset = ('1.000 productos activos, 100 compradores independientes y tres perfiles de detalle, verificados en MySQL.'
               if metadata.get('dataset_verified') else 'El volumen previsto todavía no fue verificado; no se acredita la preparación de datos.')
    problems = '<p><b>Problemas observados:</b> '+esc('; '.join(metadata.get('risks',[])))+'</p>' if metadata.get('risks') else ''
    page += '<section><h2>Qué se midió</h2><p>'+dataset+' Los resultados describen exclusivamente las ejecuciones documentadas.</p>'+problems+''.join(comparisons)+'</section>'
    page += ''.join(cards) or '<section>No evaluado: no se obtuvieron resultados k6 verificables.</section>'
    page += '<section><h2>Alcance y limitaciones</h2><ul>'+coverage+'</ul><p>No se utilizaron proveedores de pago. No se ejecutaron checkout, registro de vendedores, IA ni cargas contra ambientes compartidos. Los contenedores comparten el computador con otros servicios; no se certifica capacidad de producción.</p><p>VU no equivale a una persona ni a una petición simultánea. Un resultado con muestras insuficientes o contratos incorrectos no se considera aprobado.</p></section>'
    page += '<section><h2>Trazabilidad y problemas observados</h2><pre>'+esc(json.dumps(metadata,indent=2,ensure_ascii=False))+'</pre></section></main></html>'
    (directory/'performance-executive.html').write_text(page,encoding='utf-8')
