import { test, expect, type Page } from '@playwright/test';

// Exercises the real UI/services against the public controller contracts, without a real account or database.
async function fixture(page: Page) {
  let authenticated = false;
  let password = 'InitialPassword!123';
  let activeRole: string | null = null;
  const email = 'functional@example.test';
  const account = () => ({ accountId: 1, email, roles: ['COMPRADOR', 'VENDEDOR'], activeRole });
  const current = { id: 'current-fixture', current: true, createdAt: '2026-11-01T12:00:00Z',
    lastAccessedAt: '2026-11-01T12:00:00Z', expiresAt: null, deviceDescription: 'Chrome · Linux' };
  let sessions = [current, { ...current, id: 'other-fixture', current: false }];
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' });
    if (path.startsWith('/api/auth/') && req.method() !== 'GET') {
      expect(req.headers()['x-csrf-token']).toBe('fixture-csrf');
    }
    if (path === '/api/auth/me') return authenticated ? reply(account()) : route.fulfill({ status: 401 });
    if (path === '/api/auth/login') {
      const form = new URLSearchParams(req.postData() ?? '');
      expect(form.get('email')).toBe(email);
      if (form.get('password') !== password) return route.fulfill({ status: 401 });
      authenticated = true; activeRole = null;
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/auth/active-role') {
      expect(req.method()).toBe('PUT');
      activeRole = req.postDataJSON().role;
      return reply(account());
    }
    if (path === '/api/auth/sessions') return reply(sessions);
    if (path === '/api/auth/sessions/other-fixture') {
      expect(req.method()).toBe('DELETE');
      sessions = sessions.filter(s => s.current);
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/auth/password') {
      expect(req.method()).toBe('PUT');
      expect(req.postDataJSON()).toEqual({ currentPassword: password, newPassword: 'ChangedPassword!456' });
      password = req.postDataJSON().newPassword;
      sessions = sessions.filter(s => s.current);
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/auth/logout') {
      authenticated = false;
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/auth/password-recovery/request') {
      expect(req.postDataJSON()).toEqual({ email });
      return reply({ message: 'Solicitud recibida' }, 202);
    }
    if (path === '/api/auth/password-recovery/confirm') {
      const body = req.postDataJSON();
      if (body.token !== 'valid-fixture-token') return reply({ message: 'Token inválido o expirado' }, 400);
      expect(body.newPassword).toBe('RecoveredPassword!789');
      password = body.newPassword; authenticated = false; sessions = [];
      return route.fulfill({ status: 204 });
    }
    if (path === '/api/cart') return reply({ items: [], total: 0 });
    if (path === '/api/recommendations') return reply({ strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
  return { email };
}

async function login(page: Page, email: string, password: string) {
  await page.locator('ion-input[name="correo"] input').fill(email);
  await page.locator('ion-input[name="clave"] input').fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
}

async function role(page: Page, value: string) {
  await page.getByRole('button', { name: /^Cambiar rol activo,/ }).press('Space');
  await page.getByRole('radio', { name: value, exact: true }).click();
  await page.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(page.getByRole('button', { name: 'OK', exact: true })).toBeHidden();
  await expect(page.getByText(/^Rol activo:/)).toHaveText(new RegExp(`Rol activo:\\s*${value}`));
}

test('login, selección y cambio de rol, revocación y cambio de contraseña vuelven al acceso correcto', async ({ page }) => {
  const { email } = await fixture(page);
  await login(page, email, 'InitialPassword!123');
  await expect(page.getByText('Sin seleccionar', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Mis pedidos', exact: true })).toHaveCount(0);
  await role(page, 'VENDEDOR');
  await role(page, 'COMPRADOR');
  await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
  await expect(page.getByText('other-fixture', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Revocar sesión', exact: true }).click();
  await page.getByRole('button', { name: 'Sí, revocar', exact: true }).click();
  await expect(page.getByText('other-fixture', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Esta es tu sesión actual', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cambiar contraseña', exact: true }).click();
  await page.locator('ion-input[name="actual"] input').fill('InitialPassword!123');
  await page.locator('ion-input[name="nueva"] input').fill('ChangedPassword!456');
  await page.locator('ion-input[name="confirmacion"] input').fill('ChangedPassword!456');
  await page.getByRole('button', { name: 'Guardar nueva contraseña', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Contraseña actualizada.' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Acceso a tu cuenta', exact: true })).toBeVisible();
  await login(page, email, 'InitialPassword!123');
  await expect(page.getByRole('alert')).toContainText('credenciales son incorrectas');
  await login(page, email, 'ChangedPassword!456');
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true })).toBeVisible();
});

test('recuperación permite corregir un token rechazado y usar la nueva contraseña', async ({ page }) => {
  const { email } = await fixture(page);
  await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
  await page.locator('ion-input[name="correoRecuperacion"] input').fill(email);
  await page.getByRole('button', { name: 'Solicitar instrucciones', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'intentaremos enviar' })).toBeVisible();
  await page.getByRole('button', { name: 'Ingresar token recibido', exact: true }).click();
  await page.locator('ion-input[name="token"] input').fill('invalid-fixture-token');
  await page.locator('ion-input[name="nueva"] input').fill('RecoveredPassword!789');
  await page.locator('ion-input[name="confirmacion"] input').fill('RecoveredPassword!789');
  await page.getByRole('button', { name: 'Recuperar contraseña', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('No se pudo recuperar la contraseña');
  await page.locator('ion-input[name="token"] input').fill('valid-fixture-token');
  await page.getByRole('button', { name: 'Recuperar contraseña', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Contraseña recuperada.' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Iniciar sesión', exact: true })).toBeVisible();
  await login(page, email, 'RecoveredPassword!789');
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true })).toBeVisible();
});

test('un token rechazado permite solicitar otro conservando el correo y descartando los datos anteriores', async ({ page }) => {
  const { email } = await fixture(page);
  let solicitudes = 0;
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/auth/password-recovery/request') solicitudes++;
  });
  await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
  await page.locator('ion-input[name="correoRecuperacion"] input').fill(email);
  await page.getByRole('button', { name: 'Ingresar token recibido', exact: true }).click();
  await page.locator('ion-input[name="token"] input').fill('expired-fixture-token');
  await page.locator('ion-input[name="nueva"] input').fill('RecoveredPassword!789');
  await page.locator('ion-input[name="confirmacion"] input').fill('RecoveredPassword!789');
  await page.getByRole('button', { name: 'Recuperar contraseña', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('No se pudo recuperar la contraseña');

  await page.getByRole('button', { name: 'Solicitar otro token de recuperación', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Solicitar recuperación', exact: true })).toBeVisible();
  await expect(page.locator('ion-input[name="correoRecuperacion"] input')).toHaveValue(email);
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(solicitudes).toBe(0); // Navegar no envía correos automáticamente.
  await page.getByRole('button', { name: 'Solicitar instrucciones', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'intentaremos enviar' })).toBeVisible();
  expect(solicitudes).toBe(1);
  await page.getByRole('button', { name: 'Ingresar token recibido', exact: true }).click();
  for (const campo of ['token', 'nueva', 'confirmacion']) {
    await expect(page.locator(`ion-input[name="${campo}"] input`)).toHaveValue('');
  }
  await expect(page.getByRole('button', { name: 'Recuperar contraseña', exact: true })).toBeDisabled();
  await page.locator('ion-input[name="token"] input').fill('valid-fixture-token');
  await page.locator('ion-input[name="nueva"] input').fill('RecoveredPassword!789');
  await page.locator('ion-input[name="confirmacion"] input').fill('RecoveredPassword!789');
  await page.getByRole('button', { name: 'Recuperar contraseña', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Contraseña recuperada.' })).toBeVisible();
  await login(page, email, 'RecoveredPassword!789');
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true })).toBeVisible();
});
