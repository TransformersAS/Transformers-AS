import { test, expect, Page } from '@playwright/test';

async function fixture(page: Page) {
  let cancelled = false;
  const queries: URLSearchParams[] = [];
  const order = () => ({ id: 7, status: cancelled ? 'CANCELLED' : 'CONFIRMED', total: 110000,
    shippingMethod: 'STANDARD', createdAt: '2026-10-31T23:59:59.999999', addressId: 8,
    transactionId: 'test-only', storeId: 1, sellerNameSnapshot: 'Tienda original', discountAmount: 0,
    shippingCost: 10000, paymentStatus: cancelled ? 'REFUNDED' : 'APPROVED',
    delivery: { recipientName: 'Ana', street: 'Calle original', city: 'Bogotá', department: 'Bogotá', postalCode: null, phone: '123' },
    items: [{ productId: 2, productName: 'Producto original', quantity: 1, unitPrice: 100000, subtotal: 100000 }] });
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    switch (url.pathname) {
      case '/api/auth/me': return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
      case '/api/auth/csrf': return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      case '/api/cart': return reply({ cartId: 1, items: [], total: 0 });
      case '/api/orders': {
        queries.push(url.searchParams);
        const row = order();
        const day = row.createdAt.slice(0, 10);
        const matches = (!url.searchParams.get('status') || url.searchParams.get('status') === row.status)
          && (!url.searchParams.get('from') || url.searchParams.get('from')! <= day)
          && (!url.searchParams.get('to') || url.searchParams.get('to')! >= day);
        return reply(matches ? [row] : []);
      }
      case '/api/orders/7': return reply(order());
      case '/api/orders/7/cancellation':
        cancelled = true;
        return reply({ orderId: 7, status: 'CANCELLED', paymentStatus: 'REFUNDED',
          refund: { status: 'COMPLETED', message: 'Reembolso completado' } });
      case '/api/recommendations': return reply({ userId: 1, strategy: 'test', products: [] });
      default: return reply([]);
    }
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  const panel = page.getByRole('dialog', { name: 'Mis pedidos' });
  await expect(panel.getByRole('button', { name: 'Ver detalle del pedido 7' })).toBeVisible();
  return { panel, queries };
}

test('aplica fechas y estado, conserva al volver y limpia los filtros', async ({ page }) => {
  const { panel, queries } = await fixture(page);
  expect(queries[0].toString()).toBe('');
  await panel.getByLabel('Fecha inicial', { exact: true }).fill('2026-10-01');
  await panel.getByLabel('Fecha final', { exact: true }).fill('2026-10-31');
  await panel.getByLabel('Estado del pedido', { exact: true }).selectOption('CONFIRMED');
  await panel.getByRole('button', { name: 'Aplicar filtros', exact: true }).click();
  await expect.poll(() => queries.at(-1)?.get('to')).toBe('2026-10-31');
  expect(queries.at(-1)?.get('from')).toBe('2026-10-01');
  expect(queries.at(-1)?.get('status')).toBe('CONFIRMED');
  await panel.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await expect(panel).toContainText('Vendedor: Tienda original');
  await expect(panel).toContainText('Calle original');
  await panel.getByRole('button', { name: 'Volver a mis pedidos', exact: true }).click();
  await expect(panel.getByLabel('Fecha inicial', { exact: true })).toHaveValue('2026-10-01');
  await expect(panel.getByLabel('Fecha final', { exact: true })).toHaveValue('2026-10-31');
  await expect(panel.getByLabel('Estado del pedido', { exact: true })).toHaveValue('CONFIRMED');
  await panel.getByRole('button', { name: 'Limpiar filtros', exact: true }).click();
  await expect.poll(() => queries.at(-1)?.toString()).toBe('');
  await expect(panel.getByLabel('Fecha inicial', { exact: true })).toHaveValue('');
  await expect(panel.getByLabel('Fecha final', { exact: true })).toHaveValue('');
  await expect(panel.getByLabel('Estado del pedido', { exact: true })).toHaveValue('');
});

test('valida el rango y distingue resultados sin coincidencias', async ({ page }) => {
  const { panel, queries } = await fixture(page);
  const count = queries.length;
  await panel.getByLabel('Fecha inicial', { exact: true }).fill('2026-11-01');
  await panel.getByLabel('Fecha final', { exact: true }).fill('2026-10-01');
  await panel.getByRole('button', { name: 'Aplicar filtros', exact: true }).click();
  await expect(panel.getByRole('alert')).toHaveText('La fecha inicial no puede ser posterior a la final.');
  expect(queries.length).toBe(count);
  await panel.getByRole('button', { name: 'Limpiar filtros', exact: true }).click();
  await panel.getByLabel('Estado del pedido', { exact: true }).selectOption('DELIVERED');
  await panel.getByRole('button', { name: 'Aplicar filtros', exact: true }).click();
  await expect(panel).toContainText('No hay pedidos que coincidan con los filtros seleccionados.');
  await expect(panel.getByRole('button', { name: 'Ver detalle del pedido 7' })).toHaveCount(0);
});

test('cancelar recarga con filtros y retira el pedido sin perder su detalle histórico', async ({ page }) => {
  const { panel, queries } = await fixture(page);
  await panel.getByLabel('Estado del pedido', { exact: true }).selectOption('CONFIRMED');
  await panel.getByRole('button', { name: 'Aplicar filtros', exact: true }).click();
  await panel.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  const count = queries.length;
  await panel.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await panel.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
  await panel.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
  await expect(panel).toContainText('Estado del pago: Reembolsado');
  await expect(panel).toContainText('Vendedor: Tienda original');
  await expect.poll(() => queries.length).toBeGreaterThan(count);
  expect(queries.at(-1)?.get('status')).toBe('CONFIRMED');
  await panel.getByRole('button', { name: 'Volver a mis pedidos', exact: true }).click();
  await expect(panel).toContainText('No hay pedidos que coincidan con los filtros seleccionados.');
  await expect(panel.getByLabel('Estado del pedido', { exact: true })).toHaveValue('CONFIRMED');
});
