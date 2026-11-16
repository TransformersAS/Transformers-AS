import { test, expect } from '@playwright/test';

// CU-18 (RNF-027): cada error de validación se ve junto a su campo, no solo el aviso general. Los ion-input de Ionic 8
// no dibujaban su errorText porque Ionic reemplazaba las clases ion-invalid/ion-touched; ahora el formulario pinta
// su propio mensaje bajo cada campo.
test('Mi tienda muestra el error del plazo y del teléfono junto a cada campo', async ({ page }) => {
  let vistasPrevias = 0;
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown) => route.fulfill({ json: body });
    if (path === '/api/auth/me') return json({ accountId: 4, email: 'vendedor@example.test', roles: ['VENDEDOR'], activeRole: 'VENDEDOR' });
    if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/seller/store/preview') { vistasPrevias++; return json({}); }
    if (path === '/api/seller/store') return json({ id: 2, name: 'Tienda de prueba', description: null, contactEmail: null,
      contactPhone: null, businessHours: null, returnWindowDays: 30, minReturnWindowDays: 30, policyText: null,
      status: 'ACTIVE', statusReason: null, canModify: true, version: 1,
      shippingMethods: { enabled: ['STANDARD'], available: ['STANDARD', 'EXPRESS'] }, images: [] });
    if (path === '/api/recommendations') return json({ products: [] });
    if (path === '/api/cart') return json({ items: [], total: 0 });
    return json([]);
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
  await page.getByRole('button', { name: 'Mi tienda', exact: true }).click();
  const tienda = page.locator('app-mi-tienda');
  await tienda.getByRole('button', { name: 'Editar configuración', exact: true }).click();
  await tienda.getByLabel('Plazo de devolución (días)', { exact: true }).fill('15');
  await tienda.getByLabel('Teléfono de contacto', { exact: true }).fill('12');
  await tienda.getByRole('button', { name: 'Ver vista previa', exact: true }).click();

  await expect(tienda.getByRole('alert').filter({ hasText: 'Revisa los campos marcados' })).toBeVisible();
  await expect(tienda.locator('#error-returnWindowDays')).toBeVisible();
  await expect(tienda.locator('#error-returnWindowDays')).toHaveText(/no puede ser menor a 30 días/);
  await expect(tienda.locator('#error-contactPhone')).toHaveText(/teléfono de 7 a 15 dígitos/);
  await expect(tienda.locator('#error-name')).toHaveCount(0);
  expect(vistasPrevias).toBe(0);

  // Al corregir y volver a intentar, los mensajes desaparecen.
  await tienda.getByLabel('Plazo de devolución (días)', { exact: true }).fill('45');
  await tienda.getByLabel('Teléfono de contacto', { exact: true }).fill('');
  await tienda.getByRole('button', { name: 'Ver vista previa', exact: true }).click();
  await expect(tienda.locator('.error-campo')).toHaveCount(0);
});
