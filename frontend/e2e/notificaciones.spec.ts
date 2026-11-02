import { test, expect, Page } from '@playwright/test';
async function fixture(page: Page, role = 'COMPRADOR', fail = false, empty = false) {
  let calls = 0;
  const paths: string[] = [];
  await page.route('**/api/**', async route => {
    const u = new URL(route.request().url()), path = u.pathname; paths.push(path);
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: [role], activeRole: role });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path.endsWith('/notifications')) {
      calls++; if (fail && calls === 1) return route.fulfill({ status: 503 });
      return reply({ items: empty ? [] : [{ id: calls, title: calls === 1 ? 'Compra confirmada' : 'Cancelación registrada', message: 'Mensaje del pedido',
        createdAt: '2026-01-01T12:00:00', orderId: 42 }], hasMore: !empty && u.searchParams.get('page') === '0' });
    }
    if (path === '/api/orders/42') return reply({ id: 42, status: 'CANCELLED', total: 100, shippingMethod: 'STANDARD',
      createdAt: '2026-01-01T12:00:00', sellerNameSnapshot: 'Tienda histórica', items: [], history: [], refunds: [], paymentStatus: 'REFUNDED' });
    if (path === '/api/seller/orders/42') return route.fulfill({ status: 404 });
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/'); await page.getByRole('button', { name: 'Abrir notificaciones', exact: true }).click();
  return { panel: page.getByRole('dialog', { name: 'Notificaciones', exact: true }), paths };
}
test('comprador consulta páginas y abre el pedido usando el detalle existente', async ({ page }) => {
  const f = await fixture(page);
  await expect(f.panel).toContainText('Compra confirmada');
  await f.panel.getByRole('button', { name: 'Cargar más' }).click();
  await expect(f.panel).toContainText('Cancelación registrada');
  await f.panel.getByRole('button', { name: 'Abrir pedido #42' }).first().click();
  await expect(page.getByRole('dialog', { name: 'Mis pedidos' })).toContainText('Tienda histórica');
  expect(f.paths).toContain('/api/orders/42');
});
test('vendedor consulta la bandeja de tienda y abre su endpoint autorizado', async ({ page }) => {
  const f = await fixture(page, 'VENDEDOR');
  await expect(f.panel).toContainText('Compra confirmada');
  await f.panel.getByRole('button', { name: 'Abrir pedido #42' }).click();
  await expect.poll(() => f.paths.includes('/api/seller/orders/42')).toBe(true);
  expect(f.paths).toContain('/api/seller/notifications');
  expect(f.paths).not.toContain('/api/notifications');
});
test('error permite reintentar la misma página', async ({ page }) => {
  const f = await fixture(page, 'COMPRADOR', true);
  await expect(f.panel.getByRole('alert')).toContainText('No se pudieron cargar');
  await f.panel.getByRole('button', { name: 'Reintentar' }).click();
  await expect(f.panel).toContainText('Cancelación registrada');
  await expect(f.panel.getByRole('alert')).toHaveCount(0);
});
test('una bandeja vacía no muestra errores', async ({ page }) => {
  const f = await fixture(page, 'COMPRADOR', false, true);
  await expect(f.panel).toContainText('No hay notificaciones.');
  await expect(f.panel.getByRole('alert')).toHaveCount(0);
});
