import { test, expect } from '@playwright/test';

// UI + real AuthService/interceptor; real session invalidation is covered by the JDBC integration tests.
test.use({ serviceWorkers: 'block' });

for (const rememberMe of [false, true]) {
  for (const scenario of ['success', 'backend', 'network', 'csrf', 'unauthorized', 'csrf-fetch'] as const) {
    test(`logout ${scenario}, rememberMe=${rememberMe}: only confirmed success clears the account`, async ({ page }) => {
      let authenticated = false;
      let logoutRequests = 0;
      let csrfRequests = 0;
      let csrfToken = '';
      let failNextCsrf = false;
      let releaseSuccess: () => void = () => {};
      const pendingSuccess = new Promise<void>(resolve => { releaseSuccess = resolve; });
      const account = { accountId: 1, email: 'person@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
      await page.route('**/api/**', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (path === '/api/auth/csrf') {
          csrfRequests++;
          if (failNextCsrf) {
            failNextCsrf = false;
            return route.fulfill({ status: 503 });
          }
          csrfToken = `csrf-${csrfRequests}`;
          return json(200, { headerName: 'X-CSRF-TOKEN', token: csrfToken });
        }
        if (path === '/api/auth/me') return authenticated ? json(200, account) : route.fulfill({ status: 401 });
        if (path === '/api/auth/login') {
          const form = new URLSearchParams(request.postData() ?? '');
          expect(form.get('rememberMe')).toBe(String(rememberMe));
          expect(request.headers()['x-csrf-token']).toBe(csrfToken);
          authenticated = true;
          return route.fulfill({ status: 204 });
        }
        if (path === '/api/auth/logout') {
          expect(request.method()).toBe('POST');
          expect(request.headers()['x-csrf-token']).toBe(csrfToken);
          logoutRequests++;
          if (logoutRequests === 1) {
            if (scenario === 'success') await pendingSuccess;
            if (scenario === 'network') return route.abort('failed');
            if (scenario === 'backend') return route.fulfill({ status: 500 });
            if (scenario === 'unauthorized') return route.fulfill({ status: 401 });
            if (scenario === 'csrf' || scenario === 'csrf-fetch') {
              // The server rejects the cached token. A user retry must obtain a fresh one.
              csrfToken = 'server-rotated-token';
              failNextCsrf = scenario === 'csrf-fetch';
              return route.fulfill({ status: 403 });
            }
          }
          authenticated = false;
          return route.fulfill({ status: 204 });
        }
        if (path === '/api/cart') return json(200, { items: [], total: 0 });
        if (path === '/api/recommendations') return json(200, { userId: 1, strategy: 'test', products: [] });
        return json(200, []);
      });

      await page.goto('/');
      await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
      await page.locator('ion-input[name="correo"] input').fill(account.email);
      await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
      if (rememberMe) {
        const checkbox = page.getByRole('checkbox', { name: 'Mantener la sesión iniciada' });
        await checkbox.click();
        await expect(checkbox).toBeChecked();
      }
      await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
      const heading = page.getByRole('heading', { name: 'Seguridad de tu cuenta' });
      const logout = page.getByRole('button', { name: 'Cerrar sesión', exact: true });
      await expect(heading).toBeVisible();
      expect(csrfRequests).toBe(2); // Before and after login.
      await logout.click();

      if (scenario === 'success') {
        await expect.poll(() => logoutRequests).toBe(1);
        await expect(heading).toBeVisible();
        await expect(logout).toBeDisabled();
        expect(authenticated).toBe(true); // No premature local logout while the response is pending.
        releaseSuccess();
      } else {
        await expect(page.getByRole('alert')).toContainText(scenario === 'network'
          ? 'No se pudo conectar con el servidor'
          : 'No se pudo confirmar el cierre de sesión');
        await expect(heading).toBeVisible();
        await expect(logout).toBeEnabled();
        await expect(page.getByRole('button', { name: 'Iniciar sesión', exact: true })).toHaveCount(0);
        expect(logoutRequests).toBe(1); // No automatic retry of logout.
        expect(await page.evaluate(async () => (await fetch('/api/auth/me')).status)).toBe(200);
        expect(csrfRequests).toBe(2);

        if (scenario === 'csrf-fetch') {
          await logout.click();
          await expect.poll(() => csrfRequests).toBe(3);
          await expect(logout).toBeEnabled();
          await expect(page.getByRole('alert')).toContainText('No se pudo confirmar el cierre de sesión');
          await expect(heading).toBeVisible();
          expect(logoutRequests).toBe(1); // Fetching CSRF failed; logout was never sent.
          expect(authenticated).toBe(true);
        }
        // A deliberate retry succeeds; a rejected CSRF token must have been replaced first.
        await logout.click();
      }

      await expect(page.getByRole('dialog')).toHaveCount(0);
      expect(authenticated).toBe(false);
      expect(logoutRequests).toBe(scenario === 'success' ? 1 : 2);
      expect(csrfRequests).toBe(scenario === 'csrf-fetch' ? 4 : scenario === 'csrf' ? 3 : 2);
      expect(await page.evaluate(async () => (await fetch('/api/auth/me')).status)).toBe(401);
      await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
      await expect(page.getByRole('button', { name: 'Iniciar sesión', exact: true })).toBeVisible();
      await expect(heading).toHaveCount(0);
      await expect(page.getByRole('alert')).toHaveCount(0);
    });
  }
}
