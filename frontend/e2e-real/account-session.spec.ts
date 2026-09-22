import { test, expect } from '@playwright/test';

test('verified account logs in through the UI and logout invalidates its real JDBC session', async ({ page, request }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await page.locator('ion-input[name="correo"] input').fill('cu08-real@example.test');
  await page.locator('ion-input[name="clave"] input').fill('Cu08RealTestPassword!');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true })).toBeVisible();
  await expect(page.locator('.welcome-strip')).toContainText('Hola, cu08-real');

  // page.request shares the browser cookie jar; requests go through the real Nginx /api proxy.
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({ email: 'cu08-real@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
  const cookie = (await page.context().cookies()).find(value => value.name === 'SESSION');
  expect(cookie).toBeDefined();
  expect(cookie!.httpOnly).toBe(true);

  const logout = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/logout'
    && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  expect((await logout).status()).toBe(204);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
  // A separate client replays the OLD cookie: server invalidation, not only browser cookie deletion.
  expect((await request.get('/api/auth/me', { headers: { Cookie: `SESSION=${cookie!.value}` } })).status()).toBe(401);
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Acceso a tu cuenta', exact: true })
    .getByRole('button', { name: 'Iniciar sesión', exact: true })).toBeVisible();
});
