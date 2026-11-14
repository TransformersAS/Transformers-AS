import { test, expect, Page } from '@playwright/test';

test.use({ serviceWorkers: 'block' });
const field = (page: Page, name: string) => page.locator(`ion-input[name="${name}"] input`);
const general = (page: Page) => page.locator('.panel > ion-text[color="danger"] [role="alert"]');

async function open(page: Page, authenticated: boolean, reply?: { status: number; body?: unknown; network?: boolean }) {
  let requests = 0;
  await page.route('**/api/**', async route => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return authenticated
      ? json(200, { accountId: 1, email: 'person@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' })
      : route.fulfill({ status: 401 });
    if (path === '/api/auth/csrf') return json(200, { headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/auth/password' || path === '/api/auth/login' || path === '/api/auth/password-recovery/confirm') {
      requests++;
      expect(req.headers()['x-csrf-token']).toBe('csrf');
      if (reply?.network) return route.abort('failed');
      return json(reply?.status ?? 401, reply?.body ?? {});
    }
    if (path === '/api/cart') return json(200, { items: [], total: 0 });
    if (path === '/api/recommendations') return json(200, { userId: 1, strategy: 'test', products: [] });
    return json(200, []);
  });
  await page.goto('/');
  if (authenticated) await expect(page.locator('.welcome-strip')).toContainText('Hola, person');
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).first().click();
  if (authenticated) await page.getByRole('button', { name: 'Cambiar contraseña', exact: true }).click();
  return () => requests;
}

async function validPasswords(page: Page) {
  await field(page, 'actual').fill('CurrentPassword!123');
  await field(page, 'nueva').fill('NewPassword!456');
  await field(page, 'confirmacion').fill('NewPassword!456');
}

test('local password errors describe their own input and preserve length, UTF-8 and matching rules', async ({ page }) => {
  const requests = await open(page, true);
  await field(page, 'nueva').fill('short');
  await expect(field(page, 'actual')).toHaveAccessibleDescription('Escribe tu contraseña actual.');
  await expect(field(page, 'nueva')).toHaveAccessibleDescription('La nueva contraseña debe tener al menos 12 caracteres.');
  await expect(field(page, 'nueva')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByRole('button', { name: 'Guardar nueva contraseña', exact: true })).toBeDisabled();
  await field(page, 'actual').fill('x'.repeat(73));
  await expect(field(page, 'actual')).toHaveAccessibleDescription('La contraseña actual supera 72 bytes UTF-8.');
  await field(page, 'actual').fill('CurrentPassword!123');
  await field(page, 'nueva').fill('😀'.repeat(19));
  await expect(field(page, 'nueva')).toHaveAccessibleDescription(/supera 72 bytes UTF-8/);
  await field(page, 'nueva').fill('NewPassword!456');
  await field(page, 'confirmacion').fill('DifferentPassword!');
  await expect(field(page, 'confirmacion')).toHaveAccessibleDescription('Las contraseñas nuevas deben coincidir.');
  await field(page, 'confirmacion').fill('NewPassword!456');
  await expect(field(page, 'confirmacion')).not.toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByRole('button', { name: 'Guardar nueva contraseña', exact: true })).toBeEnabled();
  await field(page, 'nueva').fill('CurrentPassword!123');
  await field(page, 'confirmacion').fill('CurrentPassword!123');
  await expect(field(page, 'nueva')).toHaveAccessibleDescription('La nueva contraseña debe ser diferente de la actual.');
  await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
  await page.getByRole('button', { name: 'Cambiar contraseña', exact: true }).click();
  await expect(field(page, 'actual')).toHaveValue('');
  await expect(field(page, 'actual')).not.toHaveAttribute('aria-invalid', 'true');
  expect(requests()).toBe(0);
});

const cases = [
  { name: 'wrong current', status: 403, message: 'Contraseña actual incorrecta', target: 'actual' },
  { name: 'invalid current', status: 400, message: 'Contraseña actual inválida', target: 'actual' },
  { name: 'invalid new', status: 400, message: 'La nueva contraseña requiere al menos 12 caracteres y un máximo de 72 bytes UTF-8', target: 'nueva' },
  { name: 'same new', status: 400, message: 'La nueva contraseña debe ser diferente', target: 'nueva' },
  { name: 'csrf', status: 403 },
  { name: 'ambiguous forbidden', status: 403, message: 'Acceso denegado' },
  { name: 'wrong path', status: 403, message: 'Contraseña actual incorrecta', wrongPath: true },
  { name: 'session', status: 401 },
  { name: 'network', status: 0, network: true },
];
for (const scenario of cases) test(`password response: ${scenario.name}`, async ({ page }) => {
  const requests = await open(page, true, { status: scenario.status, network: scenario.network,
    body: scenario.message ? { status: scenario.status, path: scenario.wrongPath ? '/api/other' : '/api/auth/password', message: scenario.message } : {} });
  await validPasswords(page);
  await page.getByRole('button', { name: 'Guardar nueva contraseña', exact: true }).click();
  if (scenario.target) {
    await expect(field(page, scenario.target)).toHaveAccessibleDescription(scenario.message!);
    await expect(general(page)).toHaveCount(0);
    await field(page, scenario.target).fill('CorrectedPassword!789');
    await expect(field(page, scenario.target)).not.toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: 'Sesiones activas', exact: true }).click();
    await page.getByRole('button', { name: 'Cambiar contraseña', exact: true }).click();
    await expect(field(page, scenario.target)).toHaveValue('');
  } else {
    if (scenario.name === 'session') {
      await expect(page.getByRole('alert').filter({ hasText: 'Tu sesión ha finalizado. Inicia sesión de nuevo para continuar.' })).toBeVisible();
    } else await expect(general(page)).toBeVisible();
    if (scenario.name === 'network') await expect(general(page)).toContainText('No se pudo conectar');
    if (scenario.name !== 'session') {
      await expect(field(page, 'actual')).not.toHaveAttribute('aria-invalid', 'true');
      await field(page, 'actual').fill('CorrectedPassword!789');
      await expect(general(page)).toHaveCount(0);
    }
  }
  expect(requests()).toBe(1);
});

test('login required errors are local and credential failure remains generic', async ({ page }) => {
  const requests = await open(page, false);
  await field(page, 'correo').focus();
  await field(page, 'clave').focus();
  await field(page, 'clave').blur();
  await expect(field(page, 'correo')).toHaveAccessibleDescription('Escribe tu correo.');
  await expect(field(page, 'clave')).toHaveAccessibleDescription('Escribe tu contraseña.');
  await field(page, 'correo').fill('person@example.com');
  await field(page, 'clave').fill('WrongPassword!123');
  await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(general(page)).toContainText('credenciales son incorrectas');
  await expect(field(page, 'correo')).not.toHaveAttribute('aria-invalid', 'true');
  await field(page, 'clave').fill('AnotherPassword!123');
  await expect(general(page)).toHaveCount(0);
  expect(requests()).toBe(1);
});

test('recovery associates email and new password confirmation errors without sending invalid forms', async ({ page }) => {
  const requests = await open(page, false);
  await page.getByRole('button', { name: 'Olvidé mi contraseña', exact: true }).click();
  await field(page, 'correoRecuperacion').fill('invalid');
  await expect(field(page, 'correoRecuperacion')).toHaveAccessibleDescription('Escribe un correo válido de hasta 254 caracteres.');
  await page.getByRole('button', { name: 'Ingresar token recibido', exact: true }).click();
  await field(page, 'token').fill('token');
  await field(page, 'nueva').fill('short');
  await expect(field(page, 'nueva')).toHaveAccessibleDescription(/al menos 12 caracteres/);
  await field(page, 'nueva').fill('RecoveredPassword!123');
  await field(page, 'confirmacion').fill('different');
  await expect(field(page, 'confirmacion')).toHaveAccessibleDescription('Las contraseñas nuevas deben coincidir.');
  await expect(page.getByRole('button', { name: 'Recuperar contraseña', exact: true })).toBeDisabled();
  expect(requests()).toBe(0);
});
