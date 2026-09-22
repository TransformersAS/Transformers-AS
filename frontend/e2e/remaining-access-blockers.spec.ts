import { test, expect } from '@playwright/test';

test.use({ serviceWorkers: 'block' });

for (const scenario of ['single-role', 'account-switch'] as const) {
  test(`account access: ${scenario}`, async ({ page }) => {
    let account: any = { accountId: 1, email: 'a@example.com', roles: ['COMPRADOR'], activeRole: scenario === 'single-role' ? null : 'COMPRADOR' };
    let failedCart = 0;
    await page.route('**/api/**', async route => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/me') return account ? json(account) : route.fulfill({ status: 401 });
      if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (path === '/api/auth/active-role') {
        expect(req.postDataJSON()).toEqual({ role: 'COMPRADOR' });
        expect(req.headers()['x-csrf-token']).toBe('csrf');
        account = { ...account, activeRole: 'COMPRADOR' };
        return json(account);
      }
      if (path === '/api/auth/logout') { account = null; return route.fulfill({ status: 204 }); }
      if (path === '/api/auth/login') {
        expect(new URLSearchParams(req.postData()!).get('email')).toBe('b@example.com');
        account = { accountId: 2, email: 'b@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
        return route.fulfill({ status: 204 });
      }
      if (path === '/api/cart') {
        if (account?.accountId === 2) { failedCart++; return route.fulfill({ status: 503 }); }
        return json({ cartId: 1, items: [{ id: 1, productId: 1, productName: 'Privado de A', quantity: 2, unitPrice: 10, subtotal: 20 }], total: 20 });
      }
      if (path === '/api/recommendations') return json({ userId: account?.accountId, strategy: 'test', products: [] });
      return json([]);
    });
    await page.goto('/');
    await expect(page.locator('.welcome-strip')).toContainText('Hola, a');
    if (scenario === 'single-role') {
      await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
      await expect(page.getByText('Sin seleccionar', { exact: true })).toBeVisible();
      await page.locator('ion-select').filter({ hasText: 'Cambiar rol activo' }).click();
      await page.getByRole('radio', { name: 'COMPRADOR', exact: true }).click();
      await page.getByRole('button', { name: 'OK', exact: true }).click();
      await expect(page.getByRole('status').filter({ hasText: 'Rol activo actualizado.' })).toBeVisible();
      await expect(page.getByText('Sin seleccionar', { exact: true })).toHaveCount(0);
      expect(account.activeRole).toBe('COMPRADOR');
    } else {
      await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
      await expect(page.getByText('Privado de A', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Cerrar carrito', exact: true }).click();
      await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
      await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page.locator('.cart-button em')).toHaveText('0');
      await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
      await expect(page.getByText('Privado de A', { exact: true })).toHaveCount(0);
      await page.getByRole('button', { name: 'Cerrar carrito', exact: true }).click();
      await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
      await page.locator('ion-input[name="correo"] input').fill('b@example.com');
      await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
      await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
      await expect(page.locator('.welcome-strip')).toContainText('Hola, b');
      await expect.poll(() => failedCart).toBeGreaterThan(0);
      await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
      await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
      await expect(page.getByText('Privado de A', { exact: true })).toHaveCount(0);
      await expect(page.locator('.cart-button em')).toHaveText('0');
    }
  });
}
