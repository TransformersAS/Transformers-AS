import { test, expect } from '@playwright/test';

// Real component, AuthService and interceptor; API responses are deliberately simulated.
const scenarios = [
  { name: 'seconds', retry: '30', wait: 'Espera 30 segundos' },
  { name: 'one second', retry: '1', wait: 'Espera 1 segundo' },
  { name: 'one minute', retry: '60', wait: 'Espera aproximadamente 1 minuto' },
  { name: 'fifteen minutes', retry: '900', wait: 'Espera aproximadamente 15 minutos' },
  { name: 'round minutes up', retry: '61', wait: 'Espera aproximadamente 2 minutos' },
  { name: 'HTTP date', retry: () => new Date(Date.now() + 180_000).toUTCString(), wait: 'Espera aproximadamente 3 minutos' },
  { name: 'missing or browser-hidden header', retry: undefined },
  { name: 'invalid text', retry: 'invalid' },
  { name: 'negative', retry: '-1' },
  { name: 'fraction', retry: '1.5' },
  { name: 'zero', retry: '0' },
  { name: 'unsafe integer', retry: '999999999999999999999999999999' },
  { name: 'expired HTTP date', retry: 'Sun, 01 Jan 2023 00:00:00 GMT' },
  { name: 'invalid HTTP date', retry: 'Mon, 31 Feb 2025 12:00:00 GMT' },
];

for (const scenario of scenarios) {
  test(`blocked login: ${scenario.name}`, async ({ page }) => {
    let logins = 0;
    let csrfRequests = 0;
    await page.route('**/api/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/me') return route.fulfill({ status: 401 });
      if (path === '/api/auth/csrf') {
        csrfRequests++;
        return json({ headerName: 'X-CSRF-TOKEN', token: `csrf-${csrfRequests}` });
      }
      if (path === '/api/auth/login') {
        logins++;
        expect(request.headers()['x-csrf-token']).toBe(`csrf-${csrfRequests}`);
        expect(new URLSearchParams(request.postData() ?? '').get('email')).toBe('fixture@example.test');
        const retry = typeof scenario.retry === 'function' ? scenario.retry() : scenario.retry;
        return route.fulfill({ status: 429,
          headers: retry === undefined ? {} : { 'Retry-After': retry },
          contentType: 'application/json',
          body: JSON.stringify({ code: 'LOGIN_TEMPORARILY_UNAVAILABLE', message: 'UNTRUSTED_ACCOUNT_OR_IP_DETAILS' }),
        });
      }
      if (path === '/api/recommendations') return json({ strategy: 'test', products: [] });
      return json([]);
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    const panel = page.getByRole('dialog', { name: 'Acceso a tu cuenta', exact: true });
    await page.locator('ion-input[name="correo"] input').fill('fixture@example.test');
    await page.locator('ion-input[name="clave"] input').fill('FixturePassword!123');
    const submit = panel.getByRole('button', { name: 'Iniciar sesión', exact: true });
    await submit.click();
    const error = panel.getByRole('alert');
    await expect(error).toContainText('El acceso está limitado temporalmente.');
    await expect(error).toContainText(scenario.wait ?? 'Inténtalo de nuevo más tarde.');
    if (!scenario.wait) await expect(error).not.toContainText(/Espera|segundos|minutos/);
    await expect(error).not.toContainText(/credenciales|UNTRUSTED_ACCOUNT_OR_IP_DETAILS|fixture@example/);
    await expect(submit).toBeEnabled();
    await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
    expect(logins).toBe(1);
    expect(csrfRequests).toBe(1); // No retry, success refresh or token invalidation caused by 429.

    // Only a deliberate user action makes another attempt, with fresh CSRF as usual.
    await submit.click();
    await expect.poll(() => logins).toBe(2);
    await expect(submit).toBeEnabled();
    expect(csrfRequests).toBe(2);
  });
}
