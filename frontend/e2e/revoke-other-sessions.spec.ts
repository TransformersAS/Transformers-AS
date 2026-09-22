import { test, expect } from '@playwright/test';

test.use({ serviceWorkers: 'block' });

for (const scenario of ['success', 'backend', 'network', 'csrf', 'refresh'] as const) {
  test(`close other sessions: ${scenario}`, async ({ page }) => {
    const account = { accountId: 1, email: 'person@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
    const current = { id: 'current-public-id', current: true, createdAt: '2026-01-01T00:00:00Z', lastAccessedAt: '2026-01-01T00:00:00Z', expiresAt: null };
    let sessions = [current, { ...current, id: 'other-normal', current: false }, { ...current, id: 'other-persistent', current: false }];
    let attempts = 0;
    let csrfRequests = 0;
    let failRefresh = false;
    await page.route('**/api/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/me') return json(account);
      if (path === '/api/auth/csrf') {
        csrfRequests++;
        return json({ headerName: 'X-CSRF-TOKEN', token: `csrf-${csrfRequests}` });
      }
      if (path === '/api/auth/sessions') {
        if (failRefresh) {
          failRefresh = false;
          return route.fulfill({ status: 503 });
        }
        return json(sessions);
      }
      if (path === '/api/auth/sessions/revoke-others') {
        expect(request.method()).toBe('POST');
        expect(request.headers()['x-csrf-token']).toBe(`csrf-${csrfRequests}`);
        attempts++;
        if (attempts === 1) {
          if (scenario === 'backend') return route.fulfill({ status: 500 });
          if (scenario === 'network') return route.abort('failed');
          if (scenario === 'csrf') return route.fulfill({ status: 403 });
        }
        sessions = [current];
        failRefresh = scenario === 'refresh';
        return route.fulfill({ status: 204 });
      }
      if (path === '/api/cart') return json({ items: [], total: 0 });
      if (path === '/api/recommendations') return json({ userId: 1, strategy: 'test', products: [] });
      return json([]);
    });
    await page.goto('/');
    await expect(page.locator('.welcome-strip')).toContainText('Hola, person');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
    await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
    await expect(page.locator('.sesiones li')).toHaveCount(3);
    const closeOthers = page.getByRole('button', { name: 'Cerrar las demás sesiones', exact: true });
    const success = page.getByRole('status').filter({ hasText: /^Las demás sesiones se cerraron\. Esta sesión continúa activa\.$/ });
    await closeOthers.click();
    if (['backend', 'network', 'csrf'].includes(scenario)) {
      await expect(page.getByRole('alert')).toContainText(scenario === 'network'
        ? 'No se pudo conectar con el servidor' : 'No se pudo confirmar el cierre de las demás sesiones');
      await expect(page.locator('.sesiones li')).toHaveCount(3);
      await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
      await expect(success).toHaveCount(0);
      await expect(closeOthers).toBeEnabled();
      expect(attempts).toBe(1);
      await closeOthers.click();
    }
    await expect(success).toBeVisible();
    // The success notice can appear before the following list refresh finishes.
    await expect(closeOthers).toBeEnabled();
    await expect(page.locator('.sesiones li')).toHaveCount(1);
    await expect(page.locator('.sesiones li')).toContainText('Esta es tu sesión actual');
    await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
    expect(csrfRequests).toBe(scenario === 'csrf' ? 2 : 1);
    if (scenario === 'refresh') {
      await expect(page.getByRole('alert')).toContainText('no se pudo actualizar la lista');
      await page.getByRole('button', { name: 'Actualizar', exact: true }).click();
    }
    await expect(page.getByRole('alert')).toHaveCount(0);
    const restoredSession = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/me');
    await page.reload();
    expect((await restoredSession).status()).toBe(200);
    await expect(page.locator('.welcome-strip')).toContainText('Hola, person');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
    await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
    await expect(page.locator('.sesiones li')).toHaveCount(1);
  });
}
