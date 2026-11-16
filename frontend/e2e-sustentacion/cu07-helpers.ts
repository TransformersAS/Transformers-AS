import { test as base, expect, type Page, type Locator, type APIRequestContext } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
export const password=process.env['SUST_PASSWORD']!;
export const fixture=JSON.parse(process.env['SUST_FIXTURE'] || '{}');
export const pauseMs=Number(process.env['CU07_DEMO_PAUSE_MS'] || '2500');
export const registration=(page:Page)=>page.locator('app-registro-comprador');
export async function evidence(name:string,data:unknown) {
  const dir=join(process.env['SUST_REPORT_DIR']!,'cu07-checks');await mkdir(dir,{recursive:true});
  await writeFile(join(dir,name+'.json'),JSON.stringify(data,null,2)+'\n');
}
export function fixtureAction(action:'witness'|'expire'|'smtp-stop'|'smtp-start'|'backend-stop'|'backend-start',email='') {
  const result=JSON.parse(execFileSync('python3',[resolve(__dirname,'../../scripts/demos/cu07-fixture.py'),action,email],{encoding:'utf8',timeout:100000}));
  if(action==='smtp-start') process.env['SUST_MAIL_URL']=result.mailUrl;
  return result;
}
export async function witness(email:string,name:string) {
  const data=fixtureAction('witness',email);await evidence(name,data);return data;
}
export async function capture(page:Page,name:string,focus?:Locator,secrets:string[]=[]) {
  if(focus) await focus.scrollIntoViewIfNeeded();
  await mkdir(process.env['SUST_OUTPUT']!,{recursive:true});
  await page.screenshot({path:join(process.env['SUST_OUTPUT']!,name+'.png'),fullPage:true,maskColor:'#cbd5e1',
    mask:[page.locator('input[type=password]'),page.locator('iframe'),page.locator('app-acceso dd'),...secrets.map(s=>page.getByText(s,{exact:false}))]});
  // Sólo presentación. Las operaciones se verifican antes con condiciones reales.
  await page.waitForTimeout(pauseMs);
}
export const test=base.extend<{cu07Evidence:void}>({
  cu07Evidence:[async({page},use,info)=>{
    await use();
    if(info.status!==info.expectedStatus) await capture(page,'failure-'+info.title.replace(/[^a-zA-Z0-9]/g,'-').slice(0,70)).catch(()=>{});
  },{auto:true}],
});
export async function openRegistration(page:Page) {
  await page.goto('/');
  await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
  await expect(registration(page).getByRole('heading',{name:'Registro de comprador',exact:true})).toBeVisible();
  await expect(registration(page).getByRole('button',{name:'Crear cuenta de comprador',exact:true})).toBeEnabled();
}
export async function fillBuyer(page:Page,email:string) {
  for(const [key,value] of Object.entries({fullName:'Persona Registro',birthDate:'2000-05-20',email,phone:'3000000000',password,passwordConfirmation:password})) {
    await registration(page).locator(`ion-input[name="${key}"] input`).fill(value);
  }
}
export async function consent(page:Page) {
  await registration(page).locator('ion-checkbox[name="terms"]').click();
  await registration(page).locator('ion-checkbox[name="privacy"]').click();
}
export async function submitRegistration(page:Page) {
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/buyers/register'&&r.request().method()==='POST');
  await registration(page).getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
  return response;
}
export async function registerPending(page:Page,email:string) {
  await openRegistration(page);await fillBuyer(page,email);await consent(page);
  const response=await submitRegistration(page);expect(response.status()).toBe(201);
  expect(await response.json()).toMatchObject({emailVerificationRequired:true,verificationDeliveryFailed:false});
  await expect(registration(page).getByRole('heading',{name:'Verifica tu correo',exact:true})).toBeVisible();
  await expect(registration(page).getByRole('status')).toHaveText('Se envió una verificación a tu correo electrónico');
}
export async function mailbox(request:APIRequestContext,email:string) {
  const url=process.env['SUST_MAIL_URL']!;let messages:any[]=[];
  await expect.poll(async()=>{
    const response=await request.get(url+'/api/v1/messages');if(!response.ok())return 0;
    const data=await response.json();messages=(data.messages||[]).filter((m:any)=>m.To.some((t:any)=>t.Address===email));
    return messages.length;
  },{timeout:15000}).toBeGreaterThan(0);
  return messages;
}
export async function mailCode(request:APIRequestContext,email:string,afterId?:string|string[]) {
  const known=Array.isArray(afterId)?afterId:afterId?[afterId]:[];
  let messages:any[]=[];
  await expect.poll(async()=>{
    messages=await mailbox(request,email);return messages.some(m=>!known.includes(m.ID));
  },{timeout:15000}).toBe(true);
  const meta=messages.find(m=>!known.includes(m.ID));
  const response=await request.get(`${process.env['SUST_MAIL_URL']}/api/v1/message/${meta.ID}`);
  expect(response.status()).toBe(200);
  const mail=await response.json();const token=mail.Text.match(/(?:^|\s)([A-Za-z0-9_-]{43})(?=\s|$)/m)?.[1];
  if(!token)throw new Error('No se recibió mecanismo de verificación (contenido reservado)');
  return {token:token as string,id:meta.ID as string,subject:meta.Subject as string};
}
export async function confirm(page:Page,token:string,status:number) {
  await registration(page).locator('ion-input[name="token"] input').fill(token);
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/email-verification/confirm'&&r.request().method()==='POST');
  await registration(page).getByRole('button',{name:'Confirmar correo',exact:true}).click();
  expect((await response).status()).toBe(status);
  if(status===400)await expect(registration(page).getByRole('alert')).toContainText('Solicita una nueva verificación');
  else await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();
}
export async function resend(page:Page,recoverUnavailable=false) {
  const statuses:number[]=[];
  const attempt=async()=>{
    await registration(page).locator('ion-input[autocomplete="current-password"] input').fill(password);
    const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/email-verification/resend'&&r.request().method()==='POST');
    await registration(page).getByRole('button',{name:'Reenviar verificación',exact:true}).click();
    const status=(await response).status();statuses.push(status);
    if(status===503 && recoverUnavailable) {
      // Recuperación real: repetir por UI sólo tras el error explícito de entrega.
      await expect(registration(page).getByRole('alert')).toContainText('No se pudo enviar el correo de verificación');
      return status;
    }
    expect(status).toBe(202);
    await expect(registration(page).getByRole('status')).toHaveText('Se envió una verificación a tu correo electrónico. Usa el último código.');
    return status;
  };
  if(recoverUnavailable) await expect.poll(attempt,{timeout:45000,intervals:[1000,2000,3000]}).toBe(202);
  else await attempt();
  return statuses;
}
export async function loginCurrent(page:Page,email:string,status=204) {
  await page.locator('ion-input[name="correo"] input').fill(email);
  await page.locator('ion-input[name="clave"] input').fill(password);
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login'&&r.request().method()==='POST');
  await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();
  const result=await response;expect(result.status()).toBe(status);
  if(status===204)await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
  return result;
}
export async function verificationScreen(page:Page) {
  await page.getByRole('button',{name:'Cerrar panel',exact:true}).click();
  await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
  await registration(page).getByRole('button',{name:'Ya tengo un código o necesito reenviarlo',exact:true}).click();
  await expect(registration(page).getByRole('heading',{name:'Verifica tu correo',exact:true})).toBeVisible();
}
