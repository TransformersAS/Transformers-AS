import { test, expect, Page } from '@playwright/test';

async function setup(page: Page, existing = false, mailFails = false) {
  let buyer = false;
  let activeRole = 'VENDEDOR';
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const json = (status: number, body: unknown) => route.fulfill({status, contentType:'application/json', body:JSON.stringify(body)});
    if(path==='/api/auth/csrf') return json(200,{headerName:'X-CSRF-TOKEN', token:'test-csrf'});
    if(path==='/api/auth/me') return existing ? json(200,{accountId:42,email:'seller@example.com',roles:buyer?['VENDEDOR','COMPRADOR']:['VENDEDOR'],activeRole}) : route.fulfill({status:401});
    if(path==='/api/buyers/terms') return json(200,{termsVersion:'2026-11',terms:'Términos comprador',privacyVersion:'2026-11',privacy:'Privacidad comprador'});
    if(path==='/api/buyers/register') {
      expect(route.request().headers()['x-csrf-token']).toBe('test-csrf');
      expect(route.request().postDataJSON()).toMatchObject({fullName:'Ana Pérez',birthDate:'2000-05-20',phone:'+57 300 123 4567',acceptTerms:true,acceptPrivacy:true});
      return json(201,{emailVerificationRequired:true, verificationDeliveryFailed:mailFails,message:mailFails?'Tu cuenta quedó pendiente':'Se envió una verificación a tu correo electrónico'});
    }
    if(path==='/api/auth/email-verification/confirm') return route.request().postDataJSON().token==='A'.repeat(43)?route.fulfill({status:204}):json(400,{message:'Código de verificación inválido o vencido'});
    if(path==='/api/auth/email-verification/resend') return route.fulfill({status:202});
    if(path==='/api/buyers/enable') { expect(route.request().postDataJSON()).toEqual({acceptTerms:true,acceptPrivacy:true}); buyer=true; return json(200,{buyerRoleAdded:true,message:'Rol COMPRADOR habilitado.'}); }
    if(path==='/api/auth/active-role') { activeRole=route.request().postDataJSON().role;return json(200,{accountId:42,email:'seller@example.com',roles:['VENDEDOR','COMPRADOR'],activeRole}); }
    return json(200,[]);
  });
  await page.goto('/');
  await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
  await expect(page.locator('app-registro-comprador')).toBeVisible();
}
async function fill(page: Page) {
  const values={fullName:'Ana Pérez',birthDate:'2000-05-20',email:'ana@example.com',phone:'+57 300 123 4567',password:'Cu07Password123!',passwordConfirmation:'Cu07Password123!'};
  for(const [key,value] of Object.entries(values)) await page.locator(`app-registro-comprador ion-input[name="${key}"] input`).fill(value);
  await page.locator('app-registro-comprador ion-checkbox[name="terms"]').click();
  await page.locator('app-registro-comprador ion-checkbox[name="privacy"]').click();
}
test('registro, corrección por campo, verificación inválida y retorno al login',async({page})=>{
  await setup(page);await fill(page);
  await page.locator('ion-input[name="passwordConfirmation"] input').fill('Different123!');
  await page.getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
  await expect(page.locator('app-registro-comprador')).toContainText('Las contraseñas no coinciden');
  await expect(page.locator('ion-input[name="fullName"] input')).toHaveValue('Ana Pérez');
  await page.locator('ion-input[name="passwordConfirmation"] input').fill('Cu07Password123!');
  await page.getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
  await expect(page.getByRole('status').filter({hasText:'Se envió una verificación'})).toBeVisible();
  await page.locator('ion-input[name="token"] input').fill('invalid');await page.getByRole('button',{name:'Confirmar correo',exact:true}).click();
  await expect(page.locator('app-registro-comprador [role="alert"]')).toContainText('Solicita una nueva verificación');
  await page.locator('ion-input[name="token"] input').fill('A'.repeat(43));await page.getByRole('button',{name:'Confirmar correo',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();
});
test('fallo SMTP conserva el flujo y permite reenvío',async({page})=>{
  await setup(page,false,true);await fill(page);await page.getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
  await expect(page.locator('app-registro-comprador [role="alert"]')).toContainText('registro se conservó');
  await page.locator('app-registro-comprador ion-input[autocomplete="current-password"] input').fill('Cu07Password123!');
  await page.getByRole('button',{name:'Reenviar verificación',exact:true}).click();
  await expect(page.getByRole('status').filter({hasText:'Se envió una verificación'})).toBeVisible();
});
test('habilita comprador conservando sesión y rol activo',async({page})=>{
  await setup(page,true);
  await page.locator('app-registro-comprador ion-checkbox').nth(0).click();await page.locator('app-registro-comprador ion-checkbox').nth(1).click();
  await page.getByRole('button',{name:'Habilitar COMPRADOR',exact:true}).click();
  await expect(page.locator('app-registro-comprador')).toContainText('ya tiene COMPRADOR');
  await page.getByRole('button',{name:'Volver a mi cuenta',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toContainText('Rol activo: VENDEDOR');
});
