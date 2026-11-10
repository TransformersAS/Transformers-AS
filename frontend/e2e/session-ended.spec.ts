import { test, expect, Page } from '@playwright/test';

test.use({ serviceWorkers: 'block' });
const notice = 'Tu sesión ha finalizado. Inicia sesión de nuevo para continuar.';
async function fixture(page: Page, visitor = false, expire = false) {
  let authenticated = !visitor;
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return authenticated ? json({ accountId: 1, email: 'person@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' }) : json({}, 401);
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/auth/login') { authenticated = true; return route.fulfill({ status: 204 }); }
    if (path === '/api/auth/logout' || path === '/api/auth/sessions/current') { authenticated = false; return route.fulfill({ status: 204 }); }
    if (path === '/api/auth/sessions') {
      if (expire) { authenticated = false; return route.fulfill({ status: 401 }); }
      return json([{ id: 'current', current: true, createdAt: '2026-01-01T00:00:00Z', lastAccessedAt: '2026-01-01T00:00:00Z', expiresAt: null }]);
    }
    if (path === '/api/cart') return json({ items: authenticated ? [{ id: 1, productId: 2, productName: 'Jean', quantity: 1, unitPrice: 10, subtotal: 10 }] : [], total: authenticated ? 10 : 0 });
    if (path === '/api/recommendations') return json({ userId: 1, strategy: 'test', products: [] });
    if (path === '/api/notifications') return json({ items: [], hasMore: false });
    return json([]);
  });
  await page.goto('/');
  await expect(page.locator('.welcome-strip')).toContainText(visitor ? 'Hola, visitante' : 'Hola, person');
}
async function account(page: Page) {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).focus();
  await page.keyboard.press('Enter');
}
for (const panel of ['cart', 'checkout'] as const) for (const action of ['expired', 'logout'] as const) {
  test(`${action} closes ${panel} and preserves the correct notification`, async ({ page }) => {
    await fixture(page, false, action === 'expired');
    await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
    if (panel === 'checkout') await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
    await account(page);
    if (action === 'expired') await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
    else await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
    await expect(page.locator('.cart-panel, .checkout-panel')).toHaveCount(0);
    await expect(page.getByLabel('Finalizar compra', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mis pedidos', exact: true })).toHaveCount(0);
    if (action === 'expired') {
      await expect(page.getByRole('alert').filter({ hasText: notice })).toBeVisible();
      await page.locator('ion-content').first().evaluate(async el => { await (el as any).scrollToBottom(0); });
      const banner = page.getByRole('alert').filter({ hasText: notice });
      await expect(banner).toBeInViewport();
      await expect.poll(() => banner.evaluate(el => {
        const box = el.getBoundingClientRect();
        return el.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
      })).toBe(true);
      await account(page);
      await page.locator('ion-input[name="correo"] input').fill('person@example.com');
      await page.locator('ion-input[name="clave"] input').fill('CorrectPassword!123');
      await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
      await expect(page.locator('.welcome-strip')).toContainText('Hola, person');
    }
    await expect(page.getByText(notice, { exact: true })).toHaveCount(0);
  });
}
test('visitor gets no expired-session notification', async ({ page }) => {
  await fixture(page, true);
  await expect(page.getByText(notice, { exact: true })).toHaveCount(0);
});
test('self-revocation retains its specific message without a global expiration warning', async ({ page }) => {
  await fixture(page);
  await account(page);
  await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
  await page.getByRole('button', { name: 'Revocar esta sesión', exact: true }).click();
  await page.getByRole('button', { name: 'Sí, revocar', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Sesión actual revocada.' })).toBeVisible();
  await expect(page.getByText(notice, { exact: true })).toHaveCount(0);
  await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
});
