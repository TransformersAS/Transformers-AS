import { test, expect } from '@playwright/test';

test.use({ serviceWorkers: 'block' });

// Browser integration with a controlled API: backend + real SMTP are tested separately in Java.
test('pending email blocks login, offers resend, handles delivery failure and verifies before login', async ({ page }) => {
  let verified = false;
  let authenticated = false;
  let resendAttempts = 0;
  const account = { accountId: 1, email: 'pending@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/csrf') return json(200, { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' });
    if (path === '/api/auth/me') return authenticated ? json(200, account) : route.fulfill({ status: 401 });
    if (path === '/api/auth/login') {
      const credentials = new URLSearchParams(route.request().postData() ?? '');
      if (credentials.get('password') !== 'CorrectPassword123!') return route.fulfill({ status: 401 });
      if (!verified) return json(403, { code: 'EMAIL_NOT_VERIFIED', message: 'Debes verificar tu correo' });
      authenticated = true;
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/auth/email-verification/resend') {
      expect(route.request().postDataJSON()).toEqual({ email: account.email, password: 'CorrectPassword123!' });
      expect(route.request().headers()['x-csrf-token']).toBe('test-csrf');
      resendAttempts++;
      return resendAttempts === 1 ? json(503, { message: 'No se pudo enviar' }) : route.fulfill({ status: 202 });
    }
    if (path === '/api/auth/email-verification/confirm') {
      if (route.request().postDataJSON().token !== 'A'.repeat(43)) return json(400, { code: 'INVALID_VERIFICATION_TOKEN' });
      verified = true;
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/cart') return json(200, { items: [], total: 0 });
    if (path === '/api/recommendations') return json(200, { userId: 1, strategy: 'test', products: [] });
    return json(200, []);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
  await page.locator('ion-input[name="correo"] input').fill(account.email);
  await page.locator('ion-input[name="clave"] input').fill('WrongPassword123!');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('credenciales');
  await expect(page.getByRole('button', { name: 'Reenviar verificación', exact: true })).toHaveCount(0);
  await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Debes verificar tu correo');
  await page.getByRole('button', { name: 'Reenviar verificación', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('No se pudo enviar el correo');
  await page.getByRole('button', { name: 'Reenviar verificación', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('enviamos un nuevo código');
  await page.getByRole('button', { name: 'Verificar correo', exact: true }).click();
  await page.locator('ion-input[name="verificacion"] input').fill('invalid');
  await page.getByRole('button', { name: 'Confirmar correo', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Código de verificación inválido');
  await page.locator('ion-input[name="verificacion"] input').fill('A'.repeat(43));
  await page.getByRole('button', { name: 'Confirmar correo', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Correo verificado');
  await page.locator('ion-input[name="clave"] input').fill('CorrectPassword123!');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Seguridad de tu cuenta' })).toBeVisible();
});
