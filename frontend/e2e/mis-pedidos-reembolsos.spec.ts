import { test, expect, Page } from '@playwright/test';
async function fixture(page: Page, initial = true) {
  let cancelled = initial, confirmed = false, cancellations = 0;
  let currentStatus: string | null = null;
  const order = () => ({ id: 7, status: currentStatus ?? (cancelled ? 'CANCELLED' : 'CONFIRMED'), total: 110000,
    shippingMethod: 'STANDARD', createdAt: '2026-01-01T12:00:00', paymentStatus: confirmed ? 'REFUNDED' : 'REFUND_PENDING',
    sellerNameSnapshot: 'Tienda original', discountAmount: 0, shippingCost: 10000, items: [], history: [],
    provenance: { financialUpdateVerified: confirmed, paymentSource: 'UNKNOWN' },
    refunds: !cancelled ? [] : [{ reason: 'Cancelación del pedido', amount: 110000, currency: 'COP',
      status: confirmed ? 'CONFIRMED' : 'UNKNOWN', label: confirmed ? 'Devolución confirmada' : 'Verificando devolución',
      message: confirmed ? 'El proveedor confirmó la devolución.' : 'Todavía no hay confirmación de devolución.',
      requestedAt: '2026-01-02T10:00:00', updatedAt: '2026-01-03T11:00:00', confirmedAt: confirmed ? '2026-01-04T12:00:00' : null }] });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path === '/api/orders') return reply([order()]);
    if (path === '/api/orders/7') return reply(order());
    if (path === '/api/orders/7/cancellation') {
      cancellations++; cancelled = true;
      return reply({ orderId: 7, status: 'CANCELLED', paymentStatus: 'REFUND_PENDING', refund: { status: 'FAILED', message: 'Resultado inicial registrado' } });
    }
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  const open = async () => {
    await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
    await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  };
  await page.goto('/'); await open();
  return { open, region: page.getByRole('region', { name: 'Seguimiento de devoluciones' }),
    confirm: () => confirmed = true, changeStatus: (status: string) => currentStatus = status,
    cancellations: () => cancellations };
}
test('recarga recupera el seguimiento y actualizar consulta conciliación sin cancelar', async ({ page }) => {
  const f = await fixture(page);
  await expect(f.region).toContainText('Verificando devolución');
  await expect(f.region).toContainText('COP');
  await expect(f.region).not.toContainText('Confirmación registrada:');
  await page.reload(); await f.open();
  await expect(f.region).toContainText('Verificando devolución');
  f.confirm(); await f.region.getByRole('button', { name: 'Actualizar devoluciones' }).click();
  await expect(f.region).toContainText('Devolución confirmada');
  await expect(f.region).toContainText('Confirmación registrada:');
  await expect(page.getByText('Estado del pago: Reembolsado', { exact: true })).toBeVisible();
  await expect(page.getByText('Actualización financiera respaldada por una confirmación externa registrada.', { exact: true })).toBeVisible();
  expect(f.cancellations()).toBe(0);
});
test('cancelar obtiene seguimiento independiente del mensaje temporal y lo conserva al volver', async ({ page }) => {
  const f = await fixture(page, false);
  await expect(f.region).toContainText('No hay devoluciones registradas');
  await page.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await page.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
  await page.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
  await expect(f.region).toContainText('Verificando devolución');
  await page.getByRole('button', { name: 'Volver a mis pedidos' }).click();
  await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await expect(f.region).toContainText('Verificando devolución');
  await expect(page.getByText('Pedido cancelado. Resultado inicial registrado', { exact: true })).toHaveCount(0);
  expect(f.cancellations()).toBe(1);
});

test('actualizar el detalle retira una cancelación abierta si el pedido ya fue cancelado desde otra sesión', async ({ page }) => {
  const f = await fixture(page, false);
  await page.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await page.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
  f.changeStatus('CANCELLED');
  await f.region.getByRole('button', { name: 'Actualizar devoluciones' }).click();
  const panel = page.getByRole('dialog', { name: 'Mis pedidos' });
  await expect(panel.locator('app-resumen-pedido')).toContainText('Cancelado');
  await expect(panel.getByRole('button', { name: 'Confirmar cancelación', exact: true })).toHaveCount(0);
  await expect(panel.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);
  expect(f.cancellations()).toBe(0);
  await panel.getByRole('button', { name: 'Volver a mis pedidos', exact: true }).click();
  await expect(panel.locator('app-resumen-pedido')).toContainText('Cancelado');
});
