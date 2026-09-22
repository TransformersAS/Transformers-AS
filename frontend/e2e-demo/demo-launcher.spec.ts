import { test, expect } from '@playwright/test';

test('guion CU-08 y CU-11 con cuentas y pedido provisionados por el backend real', async ({ page, request }) => {
  const login = async (email: string) => {
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
    await page.locator('ion-input[name="correo"] input').fill(email);
    await page.locator('ion-input[name="clave"] input').fill('MarketplaceDemo123!');
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true })).toBeVisible();
  };
  await page.goto('/');
  await login('demo@marketplace.local');
  const account = page.getByRole('dialog', { name: 'Seguridad de tu cuenta', exact: true });
  const me = await page.request.get('/api/auth/me');
  expect((await me.json()).roles.sort()).toEqual(['COMPRADOR', 'VENDEDOR']);
  for (const role of ['VENDEDOR', 'COMPRADOR']) {
    await account.getByRole('button', { name: /^Cambiar rol activo,/ }).press('Space');
    await page.getByRole('radio', { name: role, exact: true }).click();
    await page.getByRole('button', { name: 'OK', exact: true }).click();
    await expect(page.getByRole('button', { name: 'OK', exact: true })).toBeHidden();
    await expect(account.getByText(/^Rol activo:/)).toHaveText(new RegExp(`Rol activo:\\s*${role}`));
  }
  await account.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
  await expect(account.getByText('Esta es tu sesión actual', { exact: true })).toBeVisible();
  expect((await page.request.get('/api/auth/sessions')).status()).toBe(200);
  const cookie = (await page.context().cookies()).find(c => c.name === 'SESSION')!;
  await account.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
  expect((await request.get('/api/auth/me', { headers: { Cookie: `SESSION=${cookie.value}` } })).status()).toBe(401);

  await login('comprador.demo@example.com');
  await account.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  const orders = page.getByRole('dialog', { name: 'Mis pedidos', exact: true });
  const detail = orders.getByRole('button', { name: /^Ver detalle del pedido / });
  await expect(detail).toHaveCount(1);
  const orderId = (await detail.getAttribute('aria-label'))!.match(/\d+/)![0];
  await detail.click();
  await expect(orders.getByText('Confirmado', { exact: true })).toBeVisible();
  await expect(orders.getByText('Camiseta demostración CU-11', { exact: true })).toBeVisible();
  await expect(orders.getByText(/^Total:.*Envío: STANDARD$/)).toBeVisible();
  await orders.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await orders.getByRole('combobox', { name: 'Motivo de cancelación' }).selectOption({ label: 'Otro' });
  const confirm = orders.getByRole('button', { name: 'Confirmar cancelación', exact: true });
  await expect(confirm).toBeDisabled();
  const explanation = orders.getByRole('textbox', { name: 'Explica el motivo (obligatorio)' });
  await explanation.fill('   ');
  await expect(confirm).toBeDisabled();
  await explanation.fill('Cancelación de demostración CU-11.');
  const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/orders/${orderId}/cancellation`
    && r.request().method() === 'POST');
  await confirm.click();
  expect(await (await response).json()).toMatchObject({ status: 'CANCELLED', paymentStatus: 'REFUNDED', refund: { status: 'COMPLETED' } });
  await expect(orders.getByRole('status')).toHaveText('Pedido cancelado. Reembolso completado');
  await page.reload();
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  await orders.getByRole('button', { name: `Ver detalle del pedido ${orderId}`, exact: true }).click();
  await expect(orders.getByText('Cancelado', { exact: true })).toBeVisible();
  await expect(orders.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);
});
