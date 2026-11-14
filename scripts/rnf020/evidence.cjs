'use strict';
const fs = require('node:fs');
const {performance} = require('node:perf_hooks');
const directions=['buyer_to_seller','seller_to_buyer'];
const labels={buyer_to_seller:'Comprador → vendedor',seller_to_buyer:'Vendedor → comprador',message:'Mensajes',notification:'Notificaciones'};
function percentile(numbers,p) {
  if (!numbers.length) return null;
  const a=[...numbers].sort((x,y)=>x-y);return a[Math.max(0,Math.ceil(p*a.length)-1)];
}
function correlated(event, rows) {
  const matches=rows.filter(r=>r.message_id===event.message_id && r.text===event.token
    && r.sender_id===(event.direction==='buyer_to_seller'?1:2)
    && r.conversation_id===event.conversation_id && r.event_key===`conversation-message-${event.message_id}`
    && r.recipient_type===(event.direction==='buyer_to_seller'?'STORE':'BUYER') && r.recipient_id===1);
  return matches.length===1 && matches[0].notification_id===event.notification_id;
}
function summarize(events, expected, complete, smoke=false) {
  return directions.flatMap(direction=>['message','notification'].map(channel=>{
    const selected=events.filter(e=>e.direction===direction);
    const latencies=selected.filter(e=>e.persisted && e[channel+'_verified'] && !e.functional_error && Number.isFinite(e[channel+'_latency_ms'])).map(e=>e[channel+'_latency_ms']);
    const within=latencies.filter(x=>x<=5000).length;
    const unverified=selected.filter(e=>!e.persisted || !e[channel+'_verified']).length+Math.max(0,expected-selected.length);
    const functional=selected.filter(e=>e.functional_error || !e.persisted || !e[channel+'_verified']).length+Math.max(0,expected-selected.length);
    const sufficient=!smoke && expected>=100 && selected.length===expected && complete;
    return {direction,channel,expected,attempted:selected.length,successes:latencies.length,failures:expected-latencies.length,
      unverifiable:unverified,within_5s:within,within_5s_percent:expected?within/expected*100:0,
      mean_ms:latencies.length?latencies.reduce((a,b)=>a+b,0)/latencies.length:null,
      p50_ms:percentile(latencies,.5),p95_ms:percentile(latencies,.95),max_ms:latencies.length?Math.max(...latencies):null,
      functional_errors:functional,functional_error_rate:expected?functional/expected:null,
      state:!sufficient?'No evaluado':within/expected>=.95?'Cumple':'No cumple'};
  }));
}
// Solo lectura del DOM; ninguna consulta HTTP, refresh, cambio de polling o mock.
async function panelReady(page,selector) {
  const state=await page.evaluate(sel=>{
    const el=document.querySelector(sel),r=el?.getBoundingClientRect();
    return {visible:document.visibilityState==='visible',panel:!!el && !!r?.width && !!r?.height && getComputedStyle(el).visibility!=='hidden'};
  },selector);
  if(!state.visible || !state.panel) throw new Error('PANEL_ABSENT_OR_HIDDEN');
}
async function inPanel(locator,panel) {
  if(!(await locator.isVisible()))return false;
  const [r,p]=await Promise.all([locator.boundingBox(),panel.boundingBox()]);
  return !!r && !!p && r.x+r.width>p.x && r.x<p.x+p.width && r.y+r.height>p.y && r.y<p.y+p.height;
}
async function observeMessage(page,token,t0,timeout=12000) {
  const panel=page.locator('app-conversaciones');
  const row=panel.locator('article p').filter({hasText:token});
  const deadline=t0+timeout;
  while(performance.now()<deadline) {
    await panelReady(page,'app-conversaciones main');
    if(await row.count()===1 && await row.textContent()===token && await inPanel(row,panel))
      return {latency:performance.now()-t0,verified:true};
    await new Promise(r=>setTimeout(r,50));
  }
  return {latency:null,verified:false,error:'MESSAGE_DOM_TIMEOUT'};
}
function rowMatches(row,item) {
  const date=new Date(item.createdAt+'Z');
  const pad=x=>String(x).padStart(2,'0');
  const stamp=`${pad(date.getUTCDate())}/${pad(date.getUTCMonth()+1)}/${date.getUTCFullYear()} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`;
  return row.title===item.title && row.message===item.message && row.date===stamp && row.conversationButton===1;
}
async function observeNotification(page,feed,baseline,conversation,t0,timeout=12000) {
  const deadline=t0+timeout;
  while(performance.now()<deadline) {
    await panelReady(page,'app-notificaciones section');
    const rows=page.locator('app-notificaciones li'),count=await rows.count();
    const fresh=feed.items.filter(n=>!baseline.ids.has(n.id));
    if(count===baseline.count+1 && fresh.length===1 && feed.items[0]?.id===fresh[0].id) {
      const item=fresh[0],first=rows.first();
      const row={title:await first.locator('h3').textContent(),message:await first.locator('p').nth(0).textContent(),
        date:await first.locator('p').nth(1).textContent(),conversationButton:await first.getByRole('button',{name:'Abrir conversación',exact:true}).count()};
      if(item.conversationId===conversation && rowMatches(row,item) && await inPanel(first,page.locator('app-notificaciones section')))
        return {latency:performance.now()-t0,verified:true,id:item.id,dom:row,count};
    }
    if(count>baseline.count+1 || fresh.length>1)return {latency:null,verified:false,error:'AMBIGUOUS_NOTIFICATION_DELTA'};
    await new Promise(r=>setTimeout(r,50));
  }
  return {latency:null,verified:false,error:'NOTIFICATION_DOM_TIMEOUT'};
}
function sanitize(value) {
  if(Array.isArray(value))return value.map(sanitize);
  if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,/password|cookie|authorization|csrf|secret|access_token/i.test(k)?'[REDACTED]':sanitize(v)]));
  if(typeof value==='string')return value.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,'[CUENTA]').replace(/Bearer\s+[^\s]+/gi,'Bearer [REDACTED]');
  return value;
}
function writeReport(directory,metadata,events,smokeEvents=[]) {
  metadata=sanitize(metadata);events=sanitize(events);smokeEvents=sanitize(smokeEvents);
  const groups=summarize(events,metadata.expected_per_direction,metadata.complete,false);
  const scenarioState=groups.every(g=>g.state==='Cumple')?'Cumple':groups.some(g=>g.state==='No cumple')?'No cumple':'No evaluado';
  // Un modelo alternado favorable no acredita el requisito general ante un fallo simultáneo conocido.
  const state=metadata.send_mode==='alternating' && metadata.known_concurrent_issue && scenarioState==='Cumple'?'No evaluado':scenarioState;
  const doc={requirement:'RNF-020',limit_ms:5000,required_percent:95,state,scenario_state:scenarioState,metadata,groups,events,smoke_events:smokeEvents};
  fs.writeFileSync(directory+'/rnf020-summary.json',JSON.stringify(doc,null,2)+'\n');
  const esc=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const fmt=x=>x===null?'—':Number(x).toFixed(2);
  const cards=groups.map(g=>`<article><h2>${labels[g.direction]} · ${labels[g.channel]}</h2><p class="${g.state==='Cumple'?'pass':'other'}">${g.state}</p><div class="numbers"><div><b>${fmt(g.within_5s_percent)} %</b><br>visibles en ≤ 5 s</div><div><b>${g.within_5s} / ${g.expected}</b><br>eventos dentro del límite</div></div><div class="track"><span style="width:${Math.min(100,g.within_5s_percent)}%;background:${g.state==='Cumple'?'#147d70':'#ad5c14'}"></span></div><p>Objetivo: al menos 95 %. P95 observado: ${fmt(g.p95_ms)} ms.</p></article>`).join('');
  const rows=groups.map(g=>'<tr>'+[labels[g.direction],labels[g.channel],g.expected,g.attempted,g.successes,g.failures,g.unverifiable,fmt(g.within_5s_percent)+' %',fmt(g.mean_ms),fmt(g.p50_ms),fmt(g.p95_ms),fmt(g.max_ms),fmt((g.functional_error_rate||0)*100)+' %',g.state].map(x=>'<td>'+esc(x)+'</td>').join('')+'</tr>').join('');
  const reasonLabels={MESSAGE_DOM_TIMEOUT:'Mensaje no visible antes del plazo de observación',NOTIFICATION_DOM_TIMEOUT:'Notificación no visible antes del plazo de observación',AMBIGUOUS_NOTIFICATION_DELTA:'Llegaron varias notificaciones y no pudo verificarse la asociación',NOTIFICATION_DB_CORRELATION_FAILED:'No se verificó la asociación exacta con la notificación persistida',MESSAGE_PERSISTENCE_FAILED:'Mensaje sin persistencia verificada',SEND_RESPONSE_FAILED:'El envío no obtuvo una respuesta válida',SEND_ACTION_FAILED:'No se completó la acción de envío',SEND_CONTRACT_INVALID:'Respuesta de envío funcionalmente incorrecta',MESSAGE_DUPLICATE:'Mensaje duplicado en la interfaz',PANEL_OR_SESSION_PRECONDITION_FAILED:'Panel ausente, oculto o sesión no disponible'};
  const reasons={};for(const event of events)for(const reason of event.errors||[])reasons[reason]=(reasons[reason]||0)+1;
  const reasonText=Object.entries(reasons).map(([code,count])=>`${reasonLabels[code]||'Incidencia de observación '+code}: ${count}`).join('; ')||'No se registraron errores de observación en las muestras principales disponibles.';
  const page=`<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RNF-020 · Visibilidad de mensajes y notificaciones</title><style>body{margin:0;background:#eef3f8;color:#183149;font:16px/1.6 system-ui}main{max-width:1200px;margin:auto;padding:28px}header{background:#183149;color:white;border-radius:18px;padding:30px}section,article{background:white;padding:24px;border-radius:14px;margin:20px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}.grid article{margin:0}.numbers{display:flex;gap:30px}.numbers b{font-size:25px}.pass{color:#147d70;font-weight:bold}.other{color:#995014;font-weight:bold}.track{background:#dce5ec;height:14px;margin-top:18px}.track span{display:block;height:100%}table{border-collapse:collapse;font-size:12px;width:100%}td,th{padding:9px;border-bottom:1px solid #dbe3e9;text-align:left}.scroll{overflow:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}@media(max-width:750px){.grid{grid-template-columns:1fr}main{padding:12px}}@media print{article{break-inside:avoid}}</style><main><header><p>MARKETPLACE INTEGRAL · ASR-005</p><h1>¿Cuándo lo ve el destinatario?</h1><p>RNF-020 · Latencia desde la acción real de envío hasta la aparición comprobada en la interfaz.</p><h2>RNF-020 general: ${esc(state)}</h2><p>Escenario ${metadata.send_mode==='alternating'?'de turnos alternos':'ejecutado'}: ${esc(scenarioState)}</p></header><section><h2>Hallazgo que limita la conclusión</h2><p>${esc(metadata.known_concurrent_issue||'Sin hallazgos adicionales documentados.')}</p><p>El estado se refiere exclusivamente a los cuatro grupos del escenario ejecutado. No se declara cumplimiento general en condiciones que no superaron el smoke.</p></section><section><h2>Qué se probó</h2><p>Comprador y vendedor ficticios intercambian mensajes en una conversación real. Cada destinatario mantiene abiertas su conversación y una bandeja separada. Chromium muestra la aplicación Angular/Ionic real; Spring Boot y MySQL persisten los eventos. Se conserva el polling de 3 s para mensajes y 2 s para bandeja.</p><p>Se esperan ${metadata.expected_per_direction} mensajes por dirección y sus notificaciones. El smoke se informa aparte y no acredita cumplimiento. La latencia usa un reloj monotónico común: empieza inmediatamente antes del clic y termina cuando se observa el elemento del destinatario dentro del panel visible. Incluye envío, backend, polling y renderizado; es una medición conservadora.</p></section><div class="grid">${cards}</div><section><h2>Resultados separados por dirección y canal</h2><p>Los fallos, tiempos de espera y muestras no verificables permanecen en el denominador. Percentiles por rango más cercano sobre latencias verificadas observadas; nunca sustituyen el porcentaje de todos los eventos esperados.</p><div class="scroll"><table><tr>${['Dirección','Canal','Esperados','Intentados','Visibles verificados','Fallos','No verificables','≤ 5 s','Media ms','P50 ms','P95 ms','Máximo ms','Error funcional','Estado'].map(x=>'<th>'+x+'</th>').join('')}</tr>${rows}</table></div></section><section><h2>Cómo se evitó un falso positivo</h2><p>Cada mensaje tiene un texto único. Para notificaciones se exige una sola fila nueva en la primera posición, texto y fecha iguales a la respuesta del polling natural, ID nuevo y conversación correcta. MySQL respalda la asociación exacta entre ID de mensaje, event_key, ID de notificación y destinatario. Una respuesta HTTP o una fila persistida por sí solas nunca acreditan visibilidad.</p></section><section><h2>Problemas y límites</h2><p>${esc(reasonText)}</p><p>${esc((metadata.problems||[]).join('; ')||'No se registraron incidencias de infraestructura. Consultar fallos y muestras no verificables en la tabla y el JSON.')}</p><p>Se evalúan texto y bandeja interna, dos cuentas y una conversación, sin carga concurrente de 50/100 VU. Push del navegador, correo, adjuntos, redes móviles y estabilidad prolongada: no evaluados. No se cambiaron intervalos, código funcional ni scripts k6. Las posiciones de scroll se preparan antes de medir; durante la observación no hay recarga, navegación ni consultas API para declarar visibilidad.</p></section><section><h2>Entorno, duración y limpieza</h2><pre>${esc(JSON.stringify(metadata,null,2))}</pre></section></main></html>`;
  fs.writeFileSync(directory+'/rnf020-executive.html',page);return doc;
}
module.exports={percentile,correlated,summarize,panelReady,inPanel,observeMessage,observeNotification,rowMatches,writeReport,sanitize};
