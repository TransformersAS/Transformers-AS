import { test, expect, Page } from '@playwright/test';
async function fixture(page: Page, delayed = false) {
  let account: any = { accountId: 1, email: 'a@example.test', roles: ['COMPRADOR', 'VENDEDOR'], activeRole: 'COMPRADOR' };
  let release!: () => void, entered!: () => void;
  const waiting = new Promise<void>(resolve => release = resolve);
  const started = new Promise<void>(resolve => entered = resolve);
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return account ? reply(account) : reply({}, 401);
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/auth/logout') { account = null; return route.fulfill({ status: 204 }); }
    if (path === '/api/auth/login') { account = { accountId: 2, email: 'b@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' }; return route.fulfill({ status: 204 }); }
    if (path === '/api/auth/active-role') { account = { ...account, activeRole: req.postDataJSON().role }; return reply(account); }
    if (path === '/api/cart') return reply({ cartId: 1, items: [{ id: 1, productId: 2, productName: 'Jean', quantity: 1, unitPrice: 95000, subtotal: 95000 }], total: 95000 });
    if (path === '/api/addresses') return reply({ id: 8 });
    if (path === '/api/checkout/preview') return reply({ subtotal: 95000, discount: 0, shippingCost: 10000, total: 105000, couponValid: false });
    if (path === '/api/payments/mercadopago/checkouts') {
      expect(req.headers()['x-csrf-token']).toBe('csrf'); entered(); if (delayed) await waiting;
      return reply({ detail: 'Error de compra de A' }, 503);
    }
    if (path === '/api/notifications') return reply({ items: [], hasMore: false });
    if (path === '/api/recommendations') return reply({ userId: account?.accountId, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await expect(page.locator('.welcome-strip')).toContainText('Hola, a');
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  await page.getByPlaceholder('Escribe tu dirección de entrega').fill('Calle 1 # 2-3');
  await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true }).click(); await started;
  if (!delayed) await expect(page.getByText('Error de compra de A', { exact: true })).toBeVisible();
  return { release };
}
async function accountPanel(page: Page) {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta' })).toBeVisible();
}
async function logoutAndLoginB(page: Page, checkLogout = true) {
  await accountPanel(page); await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
  if (checkLogout) await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).focus(); await page.keyboard.press('Enter');
  await page.locator('ion-input[name="correo"] input').fill('b@example.test');
  await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.locator('.welcome-strip')).toContainText('Hola, b');
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}
test('logout y comprador B no conservan el error visible de A', async ({ page }) => {
  await fixture(page); await logoutAndLoginB(page);
  await expect(page.getByLabel('Finalizar compra', { exact: true })).toBeVisible();
  await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
});
test('cambiar rol limpia el error sin cerrar el checkout', async ({ page }) => {
  await fixture(page); await accountPanel(page);
  await page.locator('ion-select').filter({ hasText: 'Cambiar rol activo' }).click();
  await page.getByRole('radio', { name: 'VENDEDOR', exact: true }).click();
  await page.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Rol activo actualizado.' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
  await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
});
test('misma sesión conserva el error y cerrar checkout lo elimina', async ({ page }) => {
  await fixture(page); await accountPanel(page);
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
  await expect(page.getByText('Error de compra de A', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar checkout', exact: true }).click();
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
});
test('una respuesta tardía de A no repone el mensaje después del logout', async ({ page }) => {
  const f = await fixture(page, true); await logoutAndLoginB(page);
  const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/mercadopago/checkouts'));
  f.release(); await response;
  await expect(page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true })).toBeEnabled();
  await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
});

test('comprador B al iniciar sesión no ve el mensaje conservado de A', async ({ page }) => {
  await fixture(page); await logoutAndLoginB(page, false);
  await expect(page.getByLabel('Finalizar compra', { exact: true })).toBeVisible();
  await expect(page.getByText('Error de compra de A', { exact: true })).toHaveCount(0);
});
