import { test, expect } from '@playwright/test';

test('CU07 real: browser registers, receives SMTP code, verifies and logs in as buyer', async ({page,request})=>{
  test.skip(!process.env['CU07_MAIL_URL'], 'Run with scripts/cu07-real-e2e.sh and the disposable SMTP simulator');
  const email=`buyer-${Date.now()}@example.test`;
  await page.goto('/');
  await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
  const values={fullName:'Ana Pérez',birthDate:'2000-05-20',email,phone:'+57 300 123 4567',password:'Cu07RealPassword123!',passwordConfirmation:'Cu07RealPassword123!'};
  for(const [key,value] of Object.entries(values)) await page.locator(`app-registro-comprador ion-input[name="${key}"] input`).fill(value);
  await page.locator('ion-checkbox[name="terms"]').click();await page.locator('ion-checkbox[name="privacy"]').click();
  await page.getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
  await expect(page.locator('app-registro-comprador [role="status"]')).toContainText('Se envió una verificación', {timeout:15000});
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
  let messageId='';
  await expect.poll(async()=>{
    const messages=await (await request.get(`${process.env['CU07_MAIL_URL']}/api/v1/messages`)).json();
    const message=messages.messages.find((m:{To:{Address:string}[]})=>m.To.some(to=>to.Address===email));
    messageId=message?.ID ?? '';return !!messageId;
  }).toBe(true);
  const mail=await (await request.get(`${process.env['CU07_MAIL_URL']}/api/v1/message/${messageId}`)).json();
  const token=(mail.Text as string).match(/(?:^|\s)([A-Za-z0-9_-]{43})(?=\s|$)/m)?.[1];
  // Do not print mailbox contents or codes in assertion messages.
  if(!token) throw new Error('Verification code was not delivered to the test mailbox');
  await page.locator('ion-input[name="token"] input').fill(token);
  await page.getByRole('button',{name:'Confirmar correo',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();
  await page.locator('ion-input[name="correo"] input').fill(email);await page.locator('ion-input[name="clave"] input').fill('Cu07RealPassword123!');
  await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
  const me=await page.request.get('/api/auth/me');expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({email,roles:['COMPRADOR'],activeRole:'COMPRADOR'});
  expect((await page.request.get('/api/orders')).status()).toBe(200);
});

test('CU07 real: an authenticated seller adds buyer without changing identity or active role',async({page})=>{
  test.skip(!process.env['CU07_MAIL_URL'], 'Uses the disposable CU07 seed');
  await page.goto('/');await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();
  await page.locator('ion-input[name="correo"] input').fill('cu07-seller@example.test');
  await page.locator('ion-input[name="clave"] input').fill('Cu08RealTestPassword!');
  await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
  const before=await (await page.request.get('/api/auth/me')).json();
  await page.getByRole('button',{name:'Cerrar panel',exact:true}).click();
  await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
  await page.locator('app-registro-comprador ion-checkbox').nth(0).click();await page.locator('app-registro-comprador ion-checkbox').nth(1).click();
  await page.getByRole('button',{name:'Habilitar COMPRADOR',exact:true}).click();
  await expect(page.locator('app-registro-comprador')).toContainText('ya tiene COMPRADOR');
  const after=await (await page.request.get('/api/auth/me')).json();
  expect(after).toMatchObject({accountId:before.accountId,email:before.email,activeRole:'VENDEDOR'});
  expect(after.roles.sort()).toEqual(['COMPRADOR','VENDEDOR']);
  await page.getByRole('button',{name:'Volver a mi cuenta',exact:true}).click();
  await page.locator('ion-select').click();
  await page.getByRole('radio',{name:'COMPRADOR',exact:true}).click();await page.getByRole('button',{name:'OK',exact:true}).click();
  await expect.poll(async()=> (await (await page.request.get('/api/auth/me')).json()).activeRole).toBe('COMPRADOR');
  expect((await page.request.get('/api/orders')).status()).toBe(200);
});
