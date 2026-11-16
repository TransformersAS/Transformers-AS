import {test as base,expect,type Page,type Locator,type Browser,type APIRequestContext} from '@playwright/test';
import {mkdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
export const fixture=JSON.parse(process.env['SUST_FIXTURE']||'{}');
export const password=process.env['SUST_PASSWORD']!;
export const profile=(page:Page)=>page.locator('app-perfil-personal');
export const field=(page:Page,name:string)=>profile(page).locator(`ion-input[name="${name}"] input`);
const secrets:string[]=[];
export async function evidence(name:string,value:unknown){const dir=join(process.env['SUST_REPORT_DIR']!,'cu09-checks');await mkdir(dir,{recursive:true});await writeFile(join(dir,name+'.json'),JSON.stringify(value,null,2)+'\n');}
export function action(kind:'witness'|'expire-email'|'backend-stop'|'backend-start',key:string){return JSON.parse(execFileSync('python3',[resolve(__dirname,'../../scripts/demos/cu09-fixture.py'),kind,key],{encoding:'utf8',timeout:100000}));}
export async function witness(key:string,name:string){const data=action('witness',key);await evidence(name,data);return data;}
export async function capture(page:Page,name:string,focus?:Locator){
 if(focus)await focus.scrollIntoViewIfNeeded();await mkdir(process.env['SUST_OUTPUT']!,{recursive:true});
 await page.screenshot({path:join(process.env['SUST_OUTPUT']!,name+'.png'),fullPage:true,maskColor:'#cbd5e1',mask:[page.locator('input[type=password]'),page.locator('.identificador'),page.locator('iframe'),...secrets.map(s=>page.getByText(s,{exact:false}))]});
 // Exposición solamente; nunca sustituye una comprobación de finalización.
 await page.waitForTimeout(Number(process.env['CU09_DEMO_PAUSE_MS']||'2500'));
}
export const test=base.extend<{cu09Evidence:void}>({cu09Evidence:[async({page},use,info)=>{await use();if(info.status!==info.expectedStatus)await capture(page,'failure-'+info.title.replace(/[^a-zA-Z0-9]/g,'-').slice(0,65)).catch(()=>{});},{auto:true}]});
export async function login(page:Page,email:string){
 await page.goto('/');await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();
 await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();
 await expect(page.locator('ion-input[name=correo]')).toHaveClass(/hydrated/);await expect(page.locator('ion-input[name=clave]')).toHaveClass(/hydrated/);
 await page.locator('ion-input[name=correo] input').fill(email);await page.locator('ion-input[name=clave] input').fill(password);
 const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login'&&r.request().method()==='POST');await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();expect((await response).status()).toBe(204);
 await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();await page.getByRole('button',{name:'Cerrar panel',exact:true}).click();
}
export async function open(page:Page){await page.getByRole('button',{name:'Mi perfil y preferencias',exact:true}).click();await expect(profile(page).getByRole('heading',{name:'Mi perfil y preferencias',exact:true})).toBeVisible();await expect(profile(page).getByRole('button',{name:'Editar perfil y preferencias',exact:true})).toBeEnabled();}
export async function api(page:Page){const r=await page.request.get('/api/account/profile');expect(r.status()).toBe(200);return await r.json();}
export async function edit(page:Page){await profile(page).getByRole('button',{name:'Editar perfil y preferencias',exact:true}).click();await expect(field(page,'fullName')).toBeEditable();}
export async function review(page:Page){await profile(page).getByRole('button',{name:'Revisar cambios',exact:true}).click();await expect(profile(page).getByRole('heading',{name:'Revisa y confirma los cambios',exact:true})).toBeVisible();}
export async function save(page:Page,status=200){const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/account/profile'&&r.request().method()==='PUT');await profile(page).getByRole('button',{name:'Confirmar y guardar',exact:true}).click();const result=await response;expect(result.status()).toBe(status);return result;}
export async function checkbox(page:Page,name:'promotions'|'marketplaceNews',checked:boolean){const input=profile(page).locator(`ion-checkbox[name=${name}]`);if(await input.isChecked()!==checked)await input.click();if(checked)await expect(input).toBeChecked();else await expect(input).not.toBeChecked();}
export async function essential(page:Page){await expect(profile(page)).toContainText('Las comunicaciones esenciales');expect((await api(page)).essentialCommunications).toBe(true);await expect(profile(page).getByRole('checkbox',{name:/esenciales/i})).toHaveCount(0);}
export async function independent(browser:Browser,email:string){const context=await browser.newContext({baseURL:process.env['SUST_BASE_URL'],viewport:{width:1440,height:1000},serviceWorkers:'block'});const page=await context.newPage();try{await login(page,email);return {page,context};}catch(error){await context.close();throw error;}}
export async function newEmail(page:Page,email:string){await edit(page);await field(page,'email').fill(email);await review(page);await field(page,'currentPassword').fill(password);const response=await save(page);expect(await response.json()).toMatchObject({verificationDeliveryFailed:false});await expect(profile(page).getByRole('heading',{name:'Verificar nuevo correo',exact:true})).toBeVisible();}
export async function mail(request:APIRequestContext,email:string,known:string[]=[]){let meta:any;
 await expect.poll(async()=>{const r=await request.get(process.env['SUST_MAIL_URL']!+'/api/v1/messages');if(!r.ok())return false;const value=await r.json();meta=value.messages.find((m:any)=>m.To.some((to:any)=>to.Address===email)&&m.Subject==='Confirma tu nuevo correo — Marketplace'&&!known.includes(m.ID));return !!meta;},{timeout:15000}).toBe(true);
 const response=await request.get(`${process.env['SUST_MAIL_URL']}/api/v1/message/${meta.ID}`);expect(response.status()).toBe(200);const body=await response.json();const token=body.Text.match(/(?:^|\s)([A-Za-z0-9_-]{43})(?=\s|$)/m)?.[1];if(!token)throw new Error('Mecanismo no recibido (contenido reservado)');secrets.push(token);return {token:token as string,id:meta.ID as string,subject:meta.Subject as string};
}
export async function showMail(browser:Browser,subject:string,name:string){const context=await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'});try{const page=await context.newPage();await page.goto(process.env['SUST_MAIL_URL']!);await page.bringToFront();const message=page.getByText(subject,{exact:true}).first();await expect(message).toBeVisible();await message.click();await capture(page,name);}finally{await context.close();}}
export async function confirmEmail(page:Page,token:string,status=204){await field(page,'codigoCorreo').fill(token);const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/account/profile/email/confirm'&&r.request().method()==='POST');await profile(page).getByRole('button',{name:'Confirmar nuevo correo',exact:true}).click();expect((await response).status()).toBe(status);if(status===204)await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();else await expect(profile(page).getByRole('alert').first()).toContainText('código');}
export async function resend(page:Page){await field(page,'claveCorreo').fill(password);const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/account/profile/email/resend'&&r.request().method()==='POST');await profile(page).getByRole('button',{name:'Reenviar cambio de correo',exact:true}).click();const result=await response;expect(result.status()).toBe(200);expect(await result.json()).toMatchObject({verificationDeliveryFailed:false});await expect(profile(page).getByRole('status')).toHaveText('Se envió un nuevo código al correo pendiente.');}
