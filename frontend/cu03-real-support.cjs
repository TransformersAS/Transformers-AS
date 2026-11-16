const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { execFileSync } = require('node:child_process');

function setting(name) {
  if (process.env[name]) return process.env[name].trim();
  let source = '';
  try { source = readFileSync(resolve(__dirname, '../.env'), 'utf8'); } catch { return ''; }
  const value = source.match(new RegExp(`^\\s*${name}\\s*=\\s*(.*?)\\s*$`, 'm'))?.[1] ?? '';
  return value.replace(/^(['"])(.*)\1$/, '$2').trim();
}
function origin() {
  const value = setting('CU03_REAL_BASE_URL') || setting('MARKETPLACE_PUBLIC_ORIGIN') || setting('MERCADOPAGO_RETURN_URL');
  if (!value) throw new Error('Configura CU03_REAL_BASE_URL con la URL HTTPS vigente del Marketplace.');
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error('La prueba real requiere una URL HTTPS pública sin credenciales.');
  return url.origin;
}
function query(sql) {
  // Solo consultas de lectura. El secreto permanece dentro del contenedor MySQL.
  if (!/^SELECT\s/i.test(sql.trim()) || /;\s*\S/.test(sql)) throw new Error('Solo se permite una consulta SELECT.');
  const project = setting('CU03_DOCKER_PROJECT') || 'marketplace-pruebas';
  if (!/^[a-z0-9_-]+$/.test(project)) throw new Error('Nombre de proyecto Docker inválido.');
  const ids = execFileSync('docker', ['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`, '--filter', 'label=com.docker.compose.service=mysql'], { encoding: 'utf8', windowsHide: true }).trim().split(/\s+/).filter(Boolean);
  if (ids.length !== 1) throw new Error('Se requiere exactamente un servicio mysql activo en el proyecto de pruebas.');
  const raw = execFileSync('docker', ['exec', '-i', ids[0], 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw --skip-column-names'], {
    input: sql, encoding: 'utf8', windowsHide: true, timeout: 20000, stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
  return raw ? raw.split(/\r?\n/).map(line => JSON.parse(line)) : [];
}
function evidence(id) {
  if (!/^[a-f0-9-]{36}$/i.test(id)) throw new Error('ID de checkout inválido.');
  return query(`SELECT JSON_OBJECT(
    'checkoutId',c.id,'accountId',c.account_id,'status',c.status,'total',c.total,'paymentId',c.payment_id,'orderId',c.order_id,
    'orderTotal',o.total,'orderAccountId',o.account_id,'transactionId',o.transaction_id,
    'ordersWithPayment',(SELECT COUNT(*) FROM orders oo WHERE oo.transaction_id=CONCAT('MP-',c.payment_id)),
    'verifiedStatus',p.verified_status,'observedAmount',p.amount,'currency',p.currency,'collector',p.collector_id,'resolution',p.resolution_status,
    'items',(SELECT JSON_ARRAYAGG(JSON_OBJECT('productId',i.product_id,'name',i.product_name,'quantity',i.quantity)) FROM order_items i WHERE i.order_id=c.order_id),
    'checks',(SELECT JSON_ARRAYAGG(JSON_OBJECT('verificationId',s.verification_id,'phase',s.phase,'result',s.result,
      'paymentId',s.payment_id,'expectedAmount',s.expected_amount,'observedAmount',s.observed_amount,
      'expectedCurrency',s.expected_currency,'observedCurrency',s.observed_currency,
      'expectedCollector',s.expected_collector,'observedCollector',s.observed_collector)) FROM payment_safety_checks s WHERE s.checkout_id=c.id)
    ) FROM payment_checkouts c LEFT JOIN orders o ON o.id=c.order_id
      LEFT JOIN external_payment_observations p ON p.payment_id=c.payment_id WHERE c.id='${id}'`)[0];
}
async function preflight() {
  const base = origin();
  if (new URL(setting('MERCADOPAGO_RETURN_URL')).origin !== base) throw new Error('La URL de retorno del .env debe coincidir con la URL pública elegida. Recrea backend si cambiaste el .env.');
  if (!/^\d+$/.test(setting('MERCADOPAGO_COLLECTOR_ID'))) throw new Error('Falta MERCADOPAGO_COLLECTOR_ID numérico.');
  const response = await fetch(base, { signal: AbortSignal.timeout(15000) });
  if (!response.ok) throw new Error(`El túnel respondió HTTP ${response.status}.`);
  const rows = query("SELECT JSON_OBJECT('tables',COUNT(*)) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('payment_checkouts','external_payment_observations','payment_safety_checks','orders','order_items')");
  if (Number(rows[0]?.tables) !== 5) throw new Error('Faltan tablas requeridas en MySQL; verifica migraciones.');
  console.log('Preflight CU-03: HTTPS accesible y cinco tablas de verificación disponibles. No se creó ninguna compra.');
}
module.exports = { setting, origin, query, evidence, preflight };
if (require.main === module) preflight().catch(error => {
  console.error('Preflight CU-03 falló:', error.message.replace(/https?:\/\/\S+/g, '[URL]'));
  process.exitCode = 1;
});
