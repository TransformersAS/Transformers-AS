import { test, expect, Page } from '@playwright/test';

type Event = { fromStatus: string | null; toStatus: string; createdAt: string | null; legacy: boolean };
async function fixture(page: Page, history: Event[], status = 'CONFIRMED') {
  let cancelled = false;
  let reads = 0;
  const order = () => ({ id: 7, status: cancelled ? 'CANCELLED' : status, total: 110000,
    shippingMethod: 'STANDARD', createdAt: '2026-01-01T12:00:00', addressId: 8, transactionId: 'test-only',
    storeId: 1, sellerNameSnapshot: 'Tienda original', discountAmount: 0, shippingCost: 10000,
    paymentStatus: cancelled ? 'REFUNDED' : 'APPROVED',
    delivery: { recipientName: 'Ana', street: 'Calle original', city: 'Bogotá', department: 'Bogotá', postalCode: null, phone: '123' },
    items: [{ productId: 2, productName: 'Producto original', quantity: 1, unitPrice: 100000, subtotal: 100000 }], history });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path === '/api/orders') return reply([order()]);
    if (path === '/api/orders/7') { reads++; return reply(order()); }
    if (path === '/api/orders/7/cancellation') {
      cancelled = true;
      history = [...history, { fromStatus: status, toStatus: 'CANCELLED', createdAt: '2026-10-31T13:00:00', legacy: false }];
      return reply({ orderId: 7, status: 'CANCELLED', paymentStatus: 'REFUNDED',
        refund: { status: 'COMPLETED', message: 'Reembolso completado' } });
    }
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  const panel = page.getByRole('dialog', { name: 'Mis pedidos' });
  await panel.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await expect(panel.getByRole('heading', { name: 'Historial del pedido', exact: true })).toBeVisible();
  return { panel, timeline: panel.getByRole('region', { name: 'Historial del pedido' }), reads: () => reads };
}

test('muestra estados y fechas en orden registrado, incluso con fechas iguales y estados repetidos', async ({ page }) => {
  const date = '2026-10-20T10:00:00';
  const { timeline } = await fixture(page, [
    { fromStatus: 'IN_TRANSIT', toStatus: 'DELIVERY_EXCEPTION', createdAt: date, legacy: false },
    { fromStatus: 'DELIVERY_EXCEPTION', toStatus: 'IN_TRANSIT', createdAt: date, legacy: false },
    { fromStatus: 'IN_TRANSIT', toStatus: 'DELIVERY_EXCEPTION', createdAt: date, legacy: false }
  ]);
  const entries = timeline.getByRole('listitem');
  await expect(entries).toHaveCount(3);
  await expect(entries.nth(0)).toContainText('Estado anterior: En camino');
  await expect(entries.nth(0)).toContainText('Estado nuevo: Novedad en la entrega');
  await expect(entries.nth(1)).toContainText('Estado nuevo: En camino');
  await expect(entries.nth(2)).toContainText('Estado nuevo: Novedad en la entrega');
  await expect(timeline.getByText(/Fecha registrada:/)).toHaveCount(3);
});

test('un historial vacío no inventa el evento inicial', async ({ page }) => {
  const { timeline } = await fixture(page, []);
  await expect(timeline).toContainText('No hay historial registrado');
  await expect(timeline.getByRole('listitem')).toHaveCount(0);
});

test('un registro antiguo oculta la fecha y conserva eventos posteriores y estado actual independiente', async ({ page }) => {
  const { panel, timeline } = await fixture(page, [
    { fromStatus: null, toStatus: 'CONFIRMED', createdAt: null, legacy: true },
    { fromStatus: 'CONFIRMED', toStatus: 'IN_PREPARATION', createdAt: '2026-10-20T10:00:00', legacy: false }
  ], 'CANCELLED');
  const entries = timeline.getByRole('listitem');
  await expect(entries).toHaveCount(2);
  await expect(entries.nth(0)).toContainText('Estado incorporado desde datos anteriores; fecha del cambio no disponible');
  await expect(entries.nth(0)).not.toContainText('Fecha registrada');
  await expect(entries.nth(1)).toContainText('Estado nuevo: En preparación');
  await expect(entries.nth(1)).toContainText('Fecha registrada:');
  await expect(panel.locator('app-resumen-pedido')).toContainText('Cancelado');
  await expect(timeline).not.toContainText('Estado nuevo: Cancelado');
});

test('cancelar actualiza desde backend sin perder H02 ni filtros H01', async ({ page }) => {
  const { panel, timeline, reads } = await fixture(page, [
    { fromStatus: null, toStatus: 'CONFIRMED', createdAt: '2026-10-01T10:00:00', legacy: false }
  ]);
  await expect(timeline.getByRole('listitem')).toHaveCount(1);
  await panel.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await panel.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
  await panel.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
  await expect.poll(reads).toBe(2);
  await expect(timeline.getByRole('listitem')).toHaveCount(2);
  await expect(timeline.getByRole('listitem').nth(1)).toContainText('Estado nuevo: Cancelado');
  await expect(panel).toContainText('Estado del pago: Reembolsado');
  await expect(panel).toContainText('Vendedor: Tienda original');
  await panel.getByRole('button', { name: 'Volver a mis pedidos', exact: true }).click();
  await panel.getByLabel('Estado del pedido', { exact: true }).selectOption('CANCELLED');
  await panel.getByRole('button', { name: 'Aplicar filtros', exact: true }).click();
  await panel.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await expect(timeline.getByRole('listitem')).toHaveCount(2);
  await panel.getByRole('button', { name: 'Volver a mis pedidos', exact: true }).click();
  await expect(panel.getByLabel('Estado del pedido', { exact: true })).toHaveValue('CANCELLED');
});
