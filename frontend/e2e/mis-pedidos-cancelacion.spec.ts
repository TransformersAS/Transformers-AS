import { test, expect, Page } from '@playwright/test';
async function fixture(page: Page, initial = 'CONFIRMED', code = '', eligible = true, refundStatus = 'PENDING', changedState = 'READY_FOR_DISPATCH') {
  let state = initial, requests = 0;
  const order = () => ({ id: 7, status: state, total: 110000, shippingMethod: 'STANDARD', createdAt: '2026-01-01T12:00:00',
    paymentStatus: 'REFUND_PENDING', sellerNameSnapshot: 'Tienda histórica', discountAmount: 0, shippingCost: 10000,
    history: [], refunds: state === 'CANCELLED' ? [{ reason: 'Cancelación del pedido', amount: 110000, currency: 'COP',
      status: refundStatus, label: refundStatus === 'EXCEPTION' ? 'Requiere revisión' : refundStatus === 'FAILED' ? 'No se pudo completar' : 'Devolución en proceso',
      message: 'Resultado financiero registrado', requestedAt: null, updatedAt: null, confirmedAt: null }] : [],
    items: [{ productId: 2, productName: 'Producto original', quantity: 1, unitPrice: 100000, subtotal: 100000 }] });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path === '/api/orders') return reply([order()]);
    if (path === '/api/orders/7') return reply(order());
    if (path === '/api/orders/7/cancellation') {
      requests++;
      if (code) {
        state = changedState;
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code, message: 'internal backend message' }) });
      }
      state = 'CANCELLED'; return reply({ orderId: 7, status: state, paymentStatus: 'REFUND_PENDING',
        refund: { status: refundStatus === 'EXCEPTION' ? 'FAILED' : refundStatus, message: 'Solicitud financiera registrada' } });
    }
    if (path === '/api/return-requests/eligible-orders') return reply([{ orderId: 7, deliveredAt: '2026-01-01T12:00:00',
      lines: [{ orderItemId: 5, productId: 2, productName: 'Producto original', quantity: 1, unitPrice: 100000, refundAmount: 100000,
        eligible, ineligibleCode: eligible ? null : 'RETURN_WINDOW_EXPIRED', ineligibleMessage: eligible ? null : 'El plazo de devolución terminó',
        returnWindowEndsAt: null, existingReturnId: null, existingReturnStatus: null }] }]);
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/'); await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  return { panel: page.getByRole('dialog', { name: 'Mis pedidos' }), requests: () => requests };
}
async function prepare(panel: ReturnType<Page['getByRole']>) {
  await panel.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await panel.getByLabel('Motivo de cancelación', { exact: true }).selectOption('CHANGED_MIND');
}
test('aviso previo y volver no envían cancelación', async ({ page }) => {
  const { panel, requests } = await fixture(page); await prepare(panel);
  const note = panel.getByRole('note', { name: 'Consecuencias de cancelar' });
  for (const text of ['definitiva', 'operación separada', 'pendiente, en verificación, fallido o en revisión', 'No existe un plazo garantizado', 'Seguimiento de devoluciones']) {
    await expect(note).toContainText(text);
  }
  await panel.getByRole('button', { name: 'Volver', exact: true }).click();
  await expect(panel.getByRole('button', { name: 'Confirmar cancelación', exact: true })).toHaveCount(0); expect(requests()).toBe(0);
});
for (const [state,message] of Object.entries({ READY_FOR_DISPATCH: 'listo para despacho', PICKED_UP: 'posteriormente consta como entregado',
  IN_TRANSIT: 'posteriormente consta como entregado', DELIVERY_EXCEPTION: 'posteriormente consta como entregado',
  DELIVERY_ATTEMPT_FAILED: 'posteriormente consta como entregado', CANCELLED: 'ya está cancelado',
  RETURNED_TO_SELLER: 'no confirma por sí solo un reembolso', CANCELLATION_REQUESTED: 'Consta una solicitud previa' })) {
  test(`explica ${state} sin ofrecer cancelación ni devolución anticipada`, async ({ page }) => {
    const { panel } = await fixture(page, state); await expect(panel).toContainText(message);
    await expect(panel.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Devolver', exact: true })).toHaveCount(0);
  });
}
for (const eligible of [true,false]) {
  test(`entregado reutiliza elegibilidad real del componente, eligible=${eligible}`, async ({ page }) => {
    const { panel } = await fixture(page, 'DELIVERED', '', eligible);
    await expect(panel).toContainText('únicamente si cumple las condiciones');
    if (eligible) {
      await expect(panel.getByRole('button', { name: 'Devolver', exact: true })).toBeVisible();
      await panel.getByRole('button', { name: 'Devolver', exact: true }).click();
      await expect(panel.getByRole('button', { name: 'Solicitar devolución', exact: true })).toBeVisible();
    } else {
      await expect(panel).toContainText('El plazo de devolución terminó');
      await expect(panel.getByRole('button', { name: 'Devolver', exact: true })).toHaveCount(0);
    }
  });
}
for (const [code,message] of Object.entries({ ORDER_INVALID_TRANSITION: 'estado actual del pedido', ORDER_HAS_SHIPMENT: 'envío creado', ORDER_STATE_CONFLICT: 'cambió de estado durante' })) {
  test(`conflicto ${code} actualiza opciones sin repetir la solicitud`, async ({ page }) => {
    const { panel, requests } = await fixture(page, 'CONFIRMED', code); await prepare(panel);
    await panel.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
    await expect(panel).toContainText(message); await expect(panel).toContainText('listo para despacho y ya no admite cancelación');
    await expect(panel.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);
    expect(requests()).toBe(1); await expect(panel).not.toContainText('internal backend message');
  });
}
for (const [status,label] of Object.entries({ PENDING: 'Devolución en proceso', FAILED: 'No se pudo completar', EXCEPTION: 'Requiere revisión' })) {
  test(`cancelación aceptada conserva resultado financiero ${status}`, async ({ page }) => {
    const { panel, requests } = await fixture(page, 'CONFIRMED', '', true, status); await prepare(panel);
    await panel.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
    await expect(panel.getByRole('region', { name: 'Seguimiento de devoluciones' })).toContainText(label);
    await expect(panel).toContainText('ya está cancelado'); expect(requests()).toBe(1);
  });
}

test('si el detalle actualizado confirma entrega se consulta elegibilidad antes de ofrecer Devolver', async ({ page }) => {
  const { panel, requests } = await fixture(page, 'CONFIRMED', 'ORDER_STATE_CONFLICT', true, 'PENDING', 'DELIVERED');
  await prepare(panel); await panel.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
  await expect(panel).toContainText('El pedido fue entregado');
  await expect(panel.getByRole('button', { name: 'Devolver', exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);
  expect(requests()).toBe(1);
});
