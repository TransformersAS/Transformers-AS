import { test, expect, Page } from '@playwright/test';

const unavailable = 'Información histórica no disponible';
async function openOrder(page: Page, legacy = false, free = false, source?: 'UNKNOWN' | 'MIGRATED' | 'CAPTURED_AT_PURCHASE', verified = false) {
  let cancelled = false;
  const order = () => ({
    provenance: source ? { deliverySource: source, sellerSource: source, paymentSource: source,
      paymentStatusAtCapture: 'APPROVED', financialUpdateVerified: verified } : undefined,
    id: 7, status: cancelled ? 'CANCELLED' : 'CONFIRMED', total: 190000,
    shippingMethod: 'STANDARD', createdAt: '2026-10-01T12:00:00', addressId: 8, transactionId: 'test-transaction',
    storeId: 1, sellerNameSnapshot: legacy ? null : 'Tienda original',
    discountAmount: legacy ? null : free ? 0 : 20000, shippingCost: legacy ? null : free ? 0 : 10000,
    paymentStatus: cancelled ? 'REFUND_PENDING' : 'APPROVED',
    delivery: legacy ? null : { recipientName: 'Ana Comprador', street: 'Calle original 123', city: 'Bogotá',
      department: 'Cundinamarca', postalCode: '110111', phone: '3001234567' },
    items: [{ productId: 2, productName: 'Producto original', quantity: 2, unitPrice: 100000, subtotal: 200000 }]
  });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path === '/api/orders') return reply([order()]);
    if (path === '/api/orders/7') return reply(order());
    if (path === '/api/orders/7/cancellation') {
      expect(route.request().postDataJSON()).toEqual({ reasonCode: 'CHANGED_MIND' });
      cancelled = true;
      return reply({ orderId: 7, status: 'CANCELLED', paymentStatus: 'REFUND_PENDING',
        refund: { status: 'PENDING', message: 'Reembolso en proceso' } });
    }
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  await page.getByRole('button', { name: 'Ver detalle del pedido 7', exact: true }).click();
  return page.getByRole('dialog', { name: 'Mis pedidos' });
}

test('muestra los diez datos históricos y actualiza el pago al cancelar', async ({ page }) => {
  const detail = await openOrder(page);
  for (const text of ['Producto original', 'Cantidad: 2', 'Precio unitario: 100,000.00',
    'Descuento aplicado: 20,000.00', 'Costo del envío: 10,000.00', 'Total: 190,000.00',
    'Calle original 123', 'Bogotá', 'Cundinamarca', 'STANDARD', 'Estado del pago: Aprobado', 'Vendedor: Tienda original']) {
    await expect(detail).toContainText(text);
  }
  await expect(detail).not.toContainText('Dirección de envío registrada #');
  await detail.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await detail.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
  await detail.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
  await expect(detail).toContainText('Estado del pago: Reembolso pendiente');
  await expect(detail).toContainText('Cancelado');
  await expect(detail).toContainText('Vendedor: Tienda original');
  await expect(detail).toContainText('Costo del envío: 10,000.00');
});

test('los campos históricos desconocidos no se convierten en cero', async ({ page }) => {
  const detail = await openOrder(page, true);
  for (const label of ['Vendedor', 'Descuento aplicado', 'Costo del envío']) {
    await expect(detail).toContainText(`${label}: ${unavailable}`);
  }
  await expect(detail).not.toContainText('Descuento aplicado: 0.00');
  await expect(detail).not.toContainText('Costo del envío: 0.00');
});

test('un importe histórico cero se muestra como cero', async ({ page }) => {
  const detail = await openOrder(page, false, true);
  await expect(detail).toContainText('Descuento aplicado: 0.00');
  await expect(detail).toContainText('Costo del envío: 0.00');
  await expect(detail).not.toContainText(unavailable);
});

for (const source of ['UNKNOWN', 'MIGRATED', 'CAPTURED_AT_PURCHASE'] as const) {
  test(`procedencia histórica ${source} se presenta sin reconstrucciones falsas`, async ({ page }) => {
    const detail = await openOrder(page, false, true, source);
    if (source === 'UNKNOWN') {
      await expect(detail).toContainText('Dirección de procedencia histórica no verificada');
      await expect(detail).toContainText('Estado financiero: procedencia histórica no verificada');
      await expect(detail).not.toContainText('Dirección incorporada por migración');
    } else if (source === 'MIGRATED') {
      await expect(detail).toContainText('Dirección incorporada por migración');
      await expect(detail).toContainText('Estado financiero incorporado por migración');
      await expect(detail).not.toContainText('Dirección de procedencia histórica no verificada');
    } else {
      await expect(detail).toContainText('Dirección de entrega utilizada');
      await expect(detail).not.toContainText('procedencia histórica no verificada');
    }
    await expect(detail).toContainText('Costo del envío: 0.00');
  });
}
test('evidencia de actualización financiera se distingue del origen desconocido', async ({ page }) => {
  const detail = await openOrder(page, false, false, 'UNKNOWN', true);
  await expect(detail).toContainText('Actualización financiera respaldada por una confirmación externa registrada');
  await expect(detail).not.toContainText('Estado financiero: procedencia histórica no verificada');
  await expect(detail).toContainText('Dirección de procedencia histórica no verificada');
});
