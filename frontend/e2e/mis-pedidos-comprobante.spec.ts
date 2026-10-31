import { test, expect, Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

// PDF mínimo válido para aislar el navegador. El contenido completo se valida con PDFBox en backend.
function fixturePdf() {
  const content = 'BT /F1 14 Tf 50 740 Td (Comprobante de compra) Tj ET';
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${content.length} >>\nstream\n${content}\nendstream`];
  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  objects.forEach((object, i) => { offsets.push(pdf.length); pdf += `${i + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = pdf.length;
  pdf += `xref\n0 6\n0000000000 65535 f \n`;
  offsets.slice(1).forEach(offset => { pdf += `${String(offset).padStart(10, '0')} 00000 n \n`; });
  return Buffer.from(pdf + `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
}

async function fixture(page: Page, errorStatus = 0) {
  const pdf = fixturePdf();
  const modes: string[] = [];
  const order = { id: 7, status: 'CONFIRMED', total: 110000, shippingMethod: 'STANDARD', createdAt: '2026-10-01T12:00:00',
    storeId: 1, sellerNameSnapshot: 'Tienda original', discountAmount: 0, shippingCost: 10000, paymentStatus: 'APPROVED',
    delivery: null, addressId: 8, transactionId: 'test-only', history: [],
    items: [{ productId: 2, productName: 'Producto original', quantity: 1, unitPrice: 100000, subtotal: 100000 }] };
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (url.pathname === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (url.pathname === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (url.pathname === '/api/orders') return reply([order]);
    if (url.pathname === '/api/orders/7') return reply(order);
    if (url.pathname === '/api/orders/7/receipt') {
      modes.push(url.searchParams.get('download')!);
      if (errorStatus) return route.fulfill({ status: errorStatus, contentType: 'application/json', body: '{}' });
      return route.fulfill({ contentType: 'application/pdf', headers: { 'Cache-Control': 'no-store',
        'Content-Disposition': `${url.searchParams.get('download') === 'true' ? 'attachment' : 'inline'}; filename="comprobante-pedido-7.pdf"` }, body: pdf });
    }
    if (url.pathname === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  const panel = page.getByRole('dialog', { name: 'Mis pedidos' });
  await panel.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  return { panel, modes, pdf };
}

test('visualiza un PDF y descarga el mismo contenido con un nombre claro', async ({ page }) => {
  const { panel, modes, pdf } = await fixture(page);
  await panel.getByRole('button', { name: 'Ver comprobante', exact: true }).click();
  await expect(panel.locator('iframe[title="PDF del comprobante de compra"]')).toHaveAttribute('src', /^blob:/);
  await expect(panel).toContainText('Este comprobante no constituye una factura fiscal');
  expect(modes).toEqual(['false']);
  await panel.getByRole('button', { name: 'Cerrar comprobante', exact: true }).click();
  await expect(panel.locator('iframe')).toHaveCount(0);
  const downloading = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Descargar comprobante', exact: true }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe('comprobante-pedido-7.pdf');
  expect(await readFile((await download.path())!)).toEqual(pdf);
  expect(modes).toEqual(['false', 'true']);
});

for (const status of [404, 500]) {
  test(`un error ${status} muestra un mensaje y permite reintentar sin abrir PDF`, async ({ page }) => {
    const { panel } = await fixture(page, status);
    await panel.getByRole('button', { name: 'Ver comprobante', exact: true }).click();
    await expect(panel.getByRole('alert')).toContainText(status === 404 ? 'Comprobante no disponible para esta cuenta.' : 'No se pudo obtener el comprobante.');
    await expect(panel.locator('iframe')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Descargar comprobante', exact: true })).toBeEnabled();
  });
}
