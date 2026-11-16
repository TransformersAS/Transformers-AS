import { test as base, expect, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
export const fixture = JSON.parse(process.env['SUST_FIXTURE']!);
export const password = process.env['SUST_PASSWORD']!;
export async function capture(page: Page, name: string) {
  const dir = process.env['SUST_OUTPUT']!;
  await mkdir(dir, { recursive: true });
  await page.screenshot({path: join(dir, name + '.png'), fullPage: true,
    maskColor: '#cbd5e1',
    mask: [page.locator('input[type=password]'), page.locator('ion-input[name=token] input'), page.locator('app-acceso dd') ]});
  // Pausa de presentación; las comprobaciones previas esperan condiciones reales.
  await page.waitForTimeout(1200);
}
export async function login(page: Page, email: string) {
  await page.goto('/');
  await page.getByRole('button', {name:'Ver cuenta',exact:true}).click();
  await page.locator('ion-input[name="correo"] input').fill(email);
  await page.locator('ion-input[name="clave"] input').fill(password);
  await page.getByRole('button', {name:'Iniciar sesión',exact:true}).click();
  await expect(page.getByRole('dialog', {name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
  await page.getByRole('button', {name:'Cerrar panel',exact:true}).click();
}

export const test = base.extend<{ evidence: void }>({
  evidence: [async ({page}, use, info) => {
    await use();
    if(info.status !== info.expectedStatus) await capture(page,'failure').catch(()=>{});
  }, {auto:true}],
});
