import { test, expect } from '@playwright/test';

test.use({ serviceWorkers: 'block' });

test('recovery uses a neutral delivery message and allows manual retry', async ({ page }) => {
  const message = 'Si la cuenta está activa, intentaremos enviar las instrucciones de recuperación. Si no las recibes, puedes volver a solicitarlas. Utiliza el último código recibido';
  let requests = 0;
  await page.route('**/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return route.fulfill({ status: 401 });
    if (path === '/api/auth/csrf') return json(200, { headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/auth/password-recovery/request') {
      expect(request.method()).toBe('POST');
      expect(request.headers()['x-csrf-token']).toBe('csrf');
      expect(request.postDataJSON()).toEqual({ email: requests === 0 ? 'person@example.com' : 'missing@example.com' });
      requests++;
      // SMTP failure and response uniformity are covered by the backend tests.
      return json(202, { message });
    }
    if (path === '/api/cart') return json(200, { items: [], total: 0 });
    if (path === '/api/recommendations') return json(200, { userId: 0, strategy: 'test', products: [] });
    return json(200, []);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
  await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
  const email = page.locator('input[name="correoRecuperacion"]');
  const submit = page.getByRole('button', { name: 'Solicitar instrucciones', exact: true });
  const notice = page.getByRole('status').filter({ hasText: message });
  await email.fill('person@example.com');
  await submit.click();
  await expect(notice).toHaveText(message);
  await expect(submit).toBeEnabled();
  expect(requests).toBe(1);
  await email.fill('missing@example.com');
  await submit.click();
  await expect(notice).toHaveText(message);
  await expect(submit).toBeEnabled();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(requests).toBe(2);
});
