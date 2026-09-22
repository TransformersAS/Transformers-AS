import { test, expect } from '@playwright/test';

test('loads marketplace home page', async ({ page }) => {
  await page.goto('/');

  await expect(
    page.getByRole('link', { name: 'Marketplace Integral, inicio' })
  ).toBeVisible();

  await expect(
    page.getByPlaceholder('¿Qué estás buscando hoy?').first()
  ).toBeVisible();

  await expect(
    page.getByRole('button', { name: 'Ver cuenta' }).first()
  ).toBeVisible();
});
