import { test, expect } from '@playwright/test';

test('loads marketplace home page', async ({ page }) => {
  // This smoke test checks the local UI; remote images/fonts may stall CI's load event.
  await page.route(/^https:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//,
    route => route.abort());
  await page.goto('/', { waitUntil: 'domcontentloaded' });

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
