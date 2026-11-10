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
  await expect(email).toHaveValue('person@example.com');
  expect(requests).toBe(1);
  await email.fill('missing@example.com');
  await submit.click();
  await expect(notice).toHaveText(message);
  await expect(submit).toBeEnabled();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(requests).toBe(2);
});

for (const failure of ['network', 'unavailable'] as const) {
  test(`recovery preserves the email and allows explicit retry after ${failure}`, async ({ page }) => {
    let requests = 0;
    const email = 'retry@example.test';
    await page.route('**/api/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const json = (status: number, body: unknown) => route.fulfill({
        status, contentType: 'application/json', body: JSON.stringify(body)
      });
      if (path === '/api/auth/me') return route.fulfill({ status: 401 });
      if (path === '/api/auth/csrf') return json(200, { headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' });
      if (path === '/api/auth/password-recovery/request') {
        expect(request.method()).toBe('POST');
        expect(request.headers()['x-csrf-token']).toBe('fixture-csrf');
        expect(request.postDataJSON()).toEqual({ email });
        requests++;
        if (requests === 1) {
          // Transport/service failure, not the SMTP failure masked by the real controller's neutral 202.
          return failure === 'network' ? route.abort('failed') : json(503, {});
        }
        return json(202, { message: 'Solicitud recibida' });
      }
      if (path === '/api/cart') return json(200, { items: [], total: 0 });
      if (path === '/api/recommendations') return json(200, { userId: 0, strategy: 'test', products: [] });
      return json(200, []);
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
    await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
    const input = page.locator('input[name="correoRecuperacion"]');
    const submit = page.getByRole('button', { name: 'Solicitar instrucciones', exact: true });
    await input.fill(email);
    await submit.click();
    await expect(page.getByRole('alert')).toContainText(failure === 'network'
      ? 'No se pudo conectar con el servidor' : 'No se pudo procesar la solicitud de recuperación');
    await expect(input).toHaveValue(email);
    await expect(submit).toBeEnabled();
    await expect(page.getByRole('status').filter({ hasText: 'intentaremos enviar' })).toHaveCount(0);
    expect(requests).toBe(1);
    await submit.click();
    await expect(page.getByRole('status').filter({ hasText: 'intentaremos enviar' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(input).toHaveValue(email);
    expect(requests).toBe(2);
  });
}
