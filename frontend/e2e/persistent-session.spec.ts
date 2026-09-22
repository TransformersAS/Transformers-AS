import { test, expect } from '@playwright/test';

test.use({ serviceWorkers: 'block' });

// Checks the actual login UI and HTTP contract; JDBC and cookie lifetime are tested in Java.
for (const rememberMe of [false, true]) {
  test(`login sends rememberMe=${rememberMe}, restores the account and resets the choice after logout`, async ({ page }) => {
    let authenticated = false;
    const choices: string[] = [];
    const account = { accountId: 1, email: 'person@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/csrf') return json(200, { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
      if (path === '/api/auth/me') return authenticated ? json(200, account) : route.fulfill({ status: 401 });
      if (path === '/api/auth/login') {
        const form = new URLSearchParams(route.request().postData() ?? '');
        expect(form.get('email')).toBe(account.email);
        expect(form.get('password')).toBe('CorrectPassword123!');
        expect(route.request().headers()['x-csrf-token']).toBe('test-csrf');
        choices.push(form.get('rememberMe') ?? 'missing');
        authenticated = true;
        return route.fulfill({ status: 204 });
      }
      if (path === '/api/auth/logout') {
        expect(route.request().headers()['x-csrf-token']).toBe('test-csrf');
        authenticated = false;
        return route.fulfill({ status: 204 });
      }
      if (path === '/api/cart') return json(200, { items: [], total: 0 });
      if (path === '/api/recommendations') return json(200, { userId: 1, strategy: 'test', products: [] });
      return json(200, []);
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    const checkbox = page.getByRole('checkbox', { name: 'Mantener la sesión iniciada' });
    await expect(checkbox).not.toBeChecked();
    await page.locator('ion-input[name="correo"] input').fill(account.email);
    await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
    if (rememberMe) {
      await checkbox.click();
      await expect(checkbox).toBeChecked();
    }
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
    expect(choices).toEqual([String(rememberMe)]);

    // The component/service are recreated; identity must be restored through /me.
    await page.reload();
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
    await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    await expect(checkbox).not.toBeChecked();
    await expect(page.getByRole('button', { name: 'Iniciar sesión', exact: true })).toBeVisible();
  });
}
