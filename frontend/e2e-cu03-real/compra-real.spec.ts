import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { automatic, requireAutomaticSettings, loginMarketplace, paySandbox } from './automatic-login';
import { purchaseSummary, clearPurchaseSummary } from './purchase-presentation';
const support = require('../cu03-real-support.cjs');

test('CU-03 asistido: sandbox real, pedido y doble chequeo persistidos en MySQL', async ({ page, baseURL }) => {
  requireAutomaticSettings();
  await support.preflight();
  let created: any;
  let starts = 0;
  let checkoutStatus = 0;
  // Reenvío real, sin reintentos. Leer ANTES de entregar la respuesta evita que
  // la navegación inmediata destruya el cuerpo que Playwright intenta capturar.
  await page.route(`${baseURL}/api/payments/mercadopago/checkouts`, async route => {
    if (route.request().method() === 'POST') {
      starts++;
      const response = await route.fetch({ maxRetries: 0, maxRedirects: 0 });
      checkoutStatus = response.status();
      const body = await response.body();
      if (response.ok()) created = JSON.parse(body.toString('utf8'));
      if (created?.id) console.log(`Checkout real: ${created.id}. Conservar este ID para continuar si se interrumpe la prueba.`);
      await route.fulfill({ response, body });
    } else {
      await route.continue();
    }
  });
  // Las respuestas conservan los bytes del backend real. No se simula la API.
  await page.goto('/');
  console.log(automatic() ? 'Inicio de sesión automático con la cuenta de pruebas configurada.' : 'PASO MANUAL: inicia sesión en Chrome como comprador del Marketplace.');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  if (automatic()) await loginMarketplace(page, baseURL!);
  await expect.poll(async () => {
    const session = await page.request.get(`${baseURL}/api/auth/me`);
    return session.ok() ? (await session.json()).activeRole : null;
  }, { timeout: 5 * 60 * 1000, intervals: [2000], message: 'Inicia sesión como comprador en Chrome.' }).toBe('COMPRADOR');
  const user = await page.request.get(`${baseURL}/api/auth/me`);
  expect(user.ok()).toBe(true);
  const account = await user.json();
  expect(account.activeRole).toBe('COMPRADOR');
  const close = page.getByRole('button', { name: 'Cerrar panel', exact: true });
  if (await close.isVisible()) await close.click();
  const resume = support.setting('CU03_FLOW') === 'new' ? '' : support.setting('CU03_CHECKOUT_ID');
  let product: any;
  let expectedItems: any[];
  if (resume) {
    expect(resume).toMatch(/^[a-f0-9-]{36}$/i);
    const status = await page.request.get(`${baseURL}/api/payments/mercadopago/checkouts/${resume}`);
    expect(status.ok(), 'La compra debe pertenecer al comprador autenticado.').toBe(true);
    created = await status.json();
    expect(['PENDING', 'APPROVED']).toContain(created.status);
    const saved = support.query(`SELECT JSON_OBJECT('items',JSON_EXTRACT(snapshot_json,'$.items'),'expired',expires_at<CURRENT_TIMESTAMP(6)) FROM payment_checkouts WHERE id='${resume}'`)[0];
    expectedItems = saved.items.map((item: any) => ({ productId: item.productId, name: item.productName, quantity: item.quantity }));
    product = { name: expectedItems.map(item => item.name).join(', ') };
    await purchaseSummary(page, 'Continuando compra existente', expectedItems, Number(created.total));
    await page.waitForTimeout(4000);
    await clearPurchaseSummary(page);
    if (created.status === 'PENDING') {
      expect(Number(saved.expired), 'La reserva venció; no vuelvas a pagar este intento.').toBe(0);
      expect(new URL(created.checkoutUrl).hostname).toBe('sandbox.mercadopago.com.co');
      await page.goto(created.checkoutUrl);
    } else {
      await page.goto(`/?checkout=${resume}`);
    }
  } else {
  const existing = await page.request.get(`${baseURL}/api/cart`);
  expect(existing.ok()).toBe(true);
  expect((await existing.json()).items, 'Usa un comprador con carrito vacío. La prueba no elimina productos existentes.').toHaveLength(0);
  const addresses = await page.request.get(`${baseURL}/api/addresses`);
  expect(addresses.ok()).toBe(true);
  expect((await addresses.json()).length, 'Registra antes una dirección válida en esta cuenta.').toBeGreaterThan(0);
  const response = await page.request.get(`${baseURL}/api/products`);
  expect(response.ok()).toBe(true);
  const products = await response.json();
  product = products.find((p: any) => p.active && p.stock > 0 && (!support.setting('CU03_PRODUCT_ID') || String(p.id) === support.setting('CU03_PRODUCT_ID')));
  expectedItems = [{ productId: product?.id, name: product?.name, quantity: 1 }];
  expect(product, 'Se requiere un producto visible con stock.').toBeTruthy();
  const card = page.locator('#catalogo .product-card').filter({ has: page.getByRole('heading', { name: product.name, exact: true }) });
  await test.step('Mostrar el producto seleccionado y su detalle', async () => {
    await card.scrollIntoViewIfNeeded();
    await card.getByRole('button', { name: 'Ver detalle', exact: true }).click();
    const detail = page.getByRole('dialog', { name: 'Detalle del producto' });
    await expect(detail).toContainText(product.name);
    await page.waitForTimeout(3000);
    await detail.getByRole('button', { name: 'Cerrar detalle', exact: true }).click();
  });
  await purchaseSummary(page, '1. Producto seleccionado', expectedItems);
  await card.getByRole('button', { name: /Agregar/ }).click();
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await expect(page.locator('.cart-panel')).toContainText(product.name);
  await purchaseSummary(page, '2. Producto en el carrito', expectedItems);
  await page.waitForTimeout(3000);
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  await purchaseSummary(page, '3. Dirección y envío', expectedItems);
  await page.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true }).click();
  await expect(page.getByRole('radio', { name: /Usar esta dirección/ }).first()).toBeVisible();
  await page.waitForTimeout(2500);
  await page.getByRole('button', { name: 'Cerrar selector de direcciones', exact: true }).click();
  const previewResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/checkout/preview' && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
  const preview = await previewResponse;
  expect(preview.ok()).toBe(true);
  const amounts = await preview.json();
  await purchaseSummary(page, '4. Total calculado por el backend', expectedItems, Number(amounts.total));
  const pay = page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true });
  await expect(pay).toBeVisible();
  await pay.scrollIntoViewIfNeeded();
  await page.waitForTimeout(3000);
  await clearPurchaseSummary(page);
  await pay.click();
  await expect.poll(() => checkoutStatus, { timeout: 30000, message: 'No se recibió respuesta del backend al crear checkout; no repitas el pago sin consultar su estado.' }).toBeGreaterThan(0);
  expect(checkoutStatus, 'El backend rechazó la creación de checkout. Revisar el mensaje visible sin repetir la compra.').toBeLessThan(300);
  expect(created?.checkoutUrl, 'La respuesta real debe incluir la URL sandbox.').toBeTruthy();
  // La implementación está restringida a sandbox. Se exige además el destino sandbox.
  expect(new URL(created.checkoutUrl).hostname).toBe('sandbox.mercadopago.com.co');
  expect(starts).toBe(1);
  }
  if (automatic() && created.status !== 'APPROVED') await paySandbox(page, baseURL!, created.id);
  else if (created.status !== 'APPROVED') console.log('PASO MANUAL: completa el pago APROBADO con cuenta y tarjeta de PRUEBA y vuelve a la tienda.');
  if (created.status !== 'APPROVED') await page.waitForURL(url => url.origin === baseURL && url.searchParams.get('checkout') === created.id, { timeout: 8 * 60 * 1000 });
  const banner = page.getByRole('region', { name: 'Estado del pago' });
  await expect(banner).toContainText('Pago de pruebas aprobado', { timeout: 30000 });
  const first = support.evidence(created.id);
  expect(first).toBeTruthy();
  expect(first.accountId).toBe(account.accountId);
  expect(first.status).toBe('APPROVED');
  expect(first.verifiedStatus).toBe('approved');
  expect(first.resolution).toBe('ORDER_LINKED');
  expect(Number(first.total)).toBe(Number(created.total));
  expect(Number(first.orderTotal)).toBe(Number(first.total));
  expect(Number(first.observedAmount)).toBe(Number(first.total));
  expect(first.currency).toBe('COP');
  expect(String(first.collector)).toBe(support.setting('MERCADOPAGO_COLLECTOR_ID'));
  expect(first.orderAccountId).toBe(account.accountId);
  expect(first.ordersWithPayment).toBe(1);
  expect(first.transactionId).toBe(`MP-${first.paymentId}`);
  expect(first.items).toEqual(expect.arrayContaining(expectedItems.map(item => expect.objectContaining(item))));
  expect(first.items).toHaveLength(expectedItems.length);
  await purchaseSummary(page, `5. Pedido #${first.orderId} registrado en MySQL`, first.items, Number(first.total));
  const pair = (first.checks ?? []).find((row: any) => row.phase === 'FIRST' && row.result === 'MATCH' &&
    first.checks.some((second: any) => second.phase === 'SECOND' && second.result === 'MATCH' && second.verificationId === row.verificationId));
  expect(pair, 'Se exige una pareja real FIRST/SECOND de la misma verificación.').toBeTruthy();
  for (const row of first.checks.filter((r: any) => r.verificationId === pair.verificationId)) {
    expect(String(row.paymentId)).toBe(String(first.paymentId));
    expect(Number(row.expectedAmount)).toBe(Number(first.total));
    expect(Number(row.observedAmount)).toBe(Number(first.total));
    expect(row.expectedCurrency).toBe('COP');
    expect(row.observedCurrency).toBe('COP');
    expect(String(row.expectedCollector)).toBe(String(first.collector));
    expect(String(row.observedCollector)).toBe(String(first.collector));
  }
  await banner.getByRole('button', { name: 'Ver verificaciones del pago', exact: true }).click();
  await expect(banner).toContainText('Primer chequeo: datos del proveedor');
  await expect(banner).toContainText('Segundo chequeo: compra bloqueada');
  const refreshed = page.waitForResponse(response => {
    const path = new URL(response.url()).pathname;
    return path === `/api/payments/mercadopago/checkouts/${created.id}/refresh` ||
      path === `/api/payments/mercadopago/checkouts/${created.id}`;
  });
  await banner.getByRole('button', { name: 'Consultar estado del pago', exact: true }).click();
  const refreshedResponse = await refreshed;
  expect(refreshedResponse.ok()).toBe(true);
  expect((await refreshedResponse.json()).orderId).toBe(first.orderId);
  await expect(banner).toContainText(`Tu pedido es el #${first.orderId}`);
  const after = support.evidence(created.id);
  expect(after.orderId).toBe(first.orderId);
  expect(after.ordersWithPayment).toBe(1);
  expect(starts).toBe(resume ? 0 : 1);
  const directory = resolve(__dirname, '../../artifacts/cu03-sandbox-real');
  mkdirSync(directory, { recursive: true });
  const { accountId, orderAccountId, ...safeEvidence } = after;
  writeFileSync(resolve(directory, `${created.id}.json`), JSON.stringify({ executedAt: new Date().toISOString(),
    scope: 'Sandbox real; API y MySQL reales. No acredita liquidación de dinero real.', result: 'PASSED', ...safeEvidence }, null, 2));
  console.log(`VERIFICADO: pedido ${after.orderId}, producto ${product.name}, importe ${after.total} COP, receptor y doble chequeo coincidentes; un único pedido.`);
  await page.waitForTimeout(5000);
});
