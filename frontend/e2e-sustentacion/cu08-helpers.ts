import {test as base,expect,type Page,type Browser,type APIRequestContext,type Locator} from '@playwright/test';
import {mkdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
export const password=process.env['SUST_PASSWORD']!;
export const changed='Cu08PresentationChanged123!';
export const recovered='Cu08PresentationRecovered123!';
export const fixture=JSON.parse(process.env['SUST_FIXTURE']||'{}');
export const panel=(page:Page)=>page.locator('app-acceso');
const secrets:string[]=[];
export const neutral='Si la cuenta está activa, intentaremos enviar las instrucciones de recuperación. Si no las recibes, puedes volver a solicitarlas. Utiliza el último código recibido';
export async function evidence(name:string,data:unknown){
 const dir=join(process.env['SUST_REPORT_DIR']!,'cu08-checks');await mkdir(dir,{recursive:true});await writeFile(join(dir,name+'.json'),JSON.stringify(data,null,2)+'\n');
}
export function action(kind:'witness'|'expire-session'|'expire-recovery'|'smtp-stop'|'smtp-start',email='all'){
 const result=JSON.parse(execFileSync('python3',[resolve(__dirname,'../../scripts/demos/cu08-fixture.py'),kind,email],{encoding:'utf8',timeout:100000}));
 if(kind==='smtp-start')process.env['SUST_MAIL_URL']=result.mailUrl;
 return result;
}
export async function witness(email:string,name:string){const value=action('witness',email);await evidence(name,value);return value;}
export async function capture(page:Page,name:string,focus?:Locator){
 if(focus)await focus.scrollIntoViewIfNeeded();await mkdir(process.env['SUST_OUTPUT']!,{recursive:true});
 await page.screenshot({path:join(process.env['SUST_OUTPUT']!,name+'.png'),fullPage:true,maskColor:'#cbd5e1',mask:[page.locator('input[type=password]'),page.locator('.identificador'),page.locator('iframe'),...secrets.map(s=>page.getByText(s,{exact:false}))]});
 // Pausa de exposición; todas las comprobaciones anteriores dependen de estados reales.
 await page.waitForTimeout(Number(process.env['CU08_DEMO_PAUSE_MS']||'2500'));
}
export const test=base.extend<{cu08Evidence:void}>({cu08Evidence:[async({page},use,info)=>{
 await use();if(info.status!==info.expectedStatus)await capture(page,'failure-'+info.title.replace(/[^a-zA-Z0-9]/g,'-').slice(0,65)).catch(()=>{});
},{auto:true}]});
export async function openLogin(page:Page){
 await page.goto('/');await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();
 await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();
}
export async function attempt(page:Page,email:string,secret=password,remember=false){
 await panel(page).locator('ion-input[name=correo] input').fill(email);await panel(page).locator('ion-input[name=clave] input').fill(secret);
 if(remember){const checkbox=panel(page).getByRole('checkbox',{name:'Mantener la sesión iniciada'});await checkbox.click();await expect(checkbox).toBeChecked();}
 const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login'&&r.request().method()==='POST');
 await panel(page).getByRole('button',{name:'Iniciar sesión',exact:true}).click();return result;
}
export async function login(page:Page,email:string,secret=password,remember=false){
 await openLogin(page);expect((await attempt(page,email,secret,remember)).status()).toBe(204);
 await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
}
export async function role(page:Page,name:'COMPRADOR'|'VENDEDOR',expected=200){
 await panel(page).getByRole('button',{name:/^Cambiar rol activo,/}).press('Space');
 await page.getByRole('radio',{name,exact:true}).click();
 const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/active-role'&&r.request().method()==='PUT');
 await page.getByRole('button',{name:'OK',exact:true}).click();expect((await result).status()).toBe(expected);
 if(expected===200)await expect(panel(page).getByText(/^Rol activo:/)).toHaveText(new RegExp(`Rol activo:\\s*${name}`));
 else await expect(panel(page).getByRole('alert')).toContainText('restricción vigente');
}
export async function permissions(page:Page,buyer:number,seller:number,name:string){
 const statuses=[];for(const [kind,status] of [['comprador',buyer],['vendedor',seller]] as const){const response=await page.request.get('/api/auth/validation/'+kind);expect(response.status()).toBe(status);statuses.push(response.status());}
 await evidence(name,{buyer:statuses[0],seller:statuses[1]});
}
export async function logout(page:Page){
 const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/logout'&&r.request().method()==='POST');
 await panel(page).getByRole('button',{name:'Cerrar sesión',exact:true}).click();expect((await result).status()).toBe(204);
 await expect(page.getByRole('dialog',{name:'Acceso a tu cuenta',exact:true})).toBeVisible();expect((await page.request.get('/api/auth/me')).status()).toBe(401);
}
export async function sessions(page:Page){
 await panel(page).getByRole('button',{name:'Sesiones activas',exact:true}).click();
 await expect(panel(page).getByText('Esta es tu sesión actual',{exact:true})).toBeVisible();
 const response=await page.request.get('/api/auth/sessions');expect(response.status()).toBe(200);return await response.json() as {id:string;current:boolean;deviceDescription:string;lastAccessedAt:string}[];
}
export async function independent(browser:Browser,email:string,secret=password){
 const context=await browser.newContext({baseURL:process.env['SUST_BASE_URL'],viewport:{width:1440,height:1000},serviceWorkers:'block'});
 const page=await context.newPage();try{await login(page,email,secret);return {context,page};}catch(e){await context.close();throw e;}
}
export async function invalidated(page:Page,name:string){
 await page.bringToFront();const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/sessions');
 await panel(page).getByRole('button',{name:'Sesiones activas',exact:true}).click();expect((await result).status()).toBe(401);
 await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');
 await expect(page.getByRole('alert').filter({hasText:'Tu sesión ha finalizado.'})).toBeVisible();
 await capture(page,name,page.getByRole('alert').filter({hasText:'Tu sesión ha finalizado.'}));
}
export async function recoveryRequest(page:Page,email:string){
 await panel(page).locator('ion-input[name=correoRecuperacion] input').fill(email);
 const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/password-recovery/request'&&r.request().method()==='POST');
 await panel(page).getByRole('button',{name:'Solicitar instrucciones',exact:true}).click();const response=await result;expect(response.status()).toBe(202);
 expect(await response.json()).toMatchObject({message:neutral});await expect(panel(page).getByRole('status')).toHaveText(neutral);
}
export async function mail(request:APIRequestContext,email:string,subjectPart:string,known:string[]=[]){
 let meta:any;
 await expect.poll(async()=>{const r=await request.get(process.env['SUST_MAIL_URL']!+'/api/v1/messages',{timeout:3000});if(!r.ok())return false;
 const data=await r.json();meta=data.messages.find((m:any)=>m.To.some((t:any)=>t.Address===email)&&m.Subject.includes(subjectPart)&&!known.includes(m.ID));return !!meta;},{timeout:20000}).toBe(true);
 const response=await request.get(`${process.env['SUST_MAIL_URL']}/api/v1/message/${meta.ID}`);expect(response.status()).toBe(200);
 const body=await response.json();const token=body.Text.match(/(?:^|\s)([A-Za-z0-9_-]{43})(?=\s|$)/m)?.[1];if(!token)throw new Error('No se recibió mecanismo (contenido reservado)');secrets.push(token);
 return {token:token as string,id:meta.ID as string,subject:meta.Subject as string};
}
export async function showMail(browser:Browser,subject:string,name:string){
 const context=await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'});
 try{const page=await context.newPage();await page.goto(process.env['SUST_MAIL_URL']!);await page.bringToFront();
 const item=page.getByText(subject,{exact:true}).first();await expect(item).toBeVisible();await item.click();await capture(page,name);}
 finally{await context.close();}
}
export async function recoveryConfirm(page:Page,token:string,status=204,secret=recovered){
 await panel(page).locator('ion-input[name=token] input').fill(token);await panel(page).locator('ion-input[name=nueva] input').fill(secret);await panel(page).locator('ion-input[name=confirmacion] input').fill(secret);
 const result=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/password-recovery/confirm'&&r.request().method()==='POST');
 await panel(page).getByRole('button',{name:'Recuperar contraseña',exact:true}).click();expect((await result).status()).toBe(status);
 if(status===400)await expect(panel(page).getByRole('alert')).toContainText(/token/i);
 else await expect(panel(page).getByRole('button',{name:'Iniciar sesión',exact:true})).toBeVisible();
}
