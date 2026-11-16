import {expect} from '@playwright/test';
import {test,fixture,password,changed,recovered,neutral,panel,login,openLogin,attempt,role,permissions,sessions,independent,invalidated,logout,capture,witness,evidence,action,recoveryRequest,recoveryConfirm,mail,showMail} from './cu08-helpers';

test('CU08 password: cambiar contraseña, validaciones y cierre de otras sesiones',async({page,browser})=>{
 await login(page,fixture.password);const other=await independent(browser,fixture.password);const control=await independent(browser,fixture.control);
 try{
  await page.bringToFront();await panel(page).getByRole('button',{name:'Cambiar contraseña',exact:true}).click();
  const field=(name:string)=>panel(page).locator(`ion-input[name="${name}"] input`);
  const save=panel(page).getByRole('button',{name:'Guardar nueva contraseña',exact:true});
  await test.step('A7 — Contraseña actual incorrecta no modifica la cuenta ni revoca sesiones',async()=>{
   await field('actual').fill('ActualIncorrecta123!');await field('nueva').fill(changed);await field('confirmacion').fill(changed);
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/password'&&r.request().method()==='PUT');await save.click();expect((await response).status()).toBe(403);
   await expect(panel(page).getByText('Contraseña actual incorrecta',{exact:true}).first()).toBeVisible();
   expect(await witness(fixture.password,'password-wrong-current')).toMatchObject({credentialChanged:0,sessions:2});
   await capture(page,'password-01-actual-incorrecta',panel(page).getByText('Contraseña actual incorrecta',{exact:true}).first());
  });
  await test.step('A8 — Nueva débil, UTF-8 excesivo y confirmación distinta se corrigen en pantalla',async()=>{
   await field('actual').fill(password);
   for(const [secret,confirmation,text,label] of [['corta','corta','La nueva contraseña debe tener al menos 12 caracteres.','corta'],['é'.repeat(37),'é'.repeat(37),'La nueva contraseña supera 72 bytes UTF-8; algunos caracteres ocupan varios bytes.','utf8'],[changed,'ConfirmacionDistinta123!','Las contraseñas nuevas deben coincidir.','confirmacion']]){
    await field('nueva').fill(secret);await field('confirmacion').fill(confirmation);await expect(save).toBeDisabled();await expect(panel(page).getByText(text,{exact:true}).first()).toBeVisible();
    await capture(page,'password-02-'+label,panel(page).getByText(text,{exact:true}).first());
   }
   expect((await witness(fixture.password,'password-invalid-new')).credentialChanged).toBe(0);
  });
  await test.step('RF033/A6 — Guardar nueva contraseña y conservar únicamente esta sesión propia',async()=>{
   await field('nueva').fill(changed);await field('confirmacion').fill(changed);
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/password'&&r.request().method()==='PUT');await save.click();expect((await response).status()).toBe(204);
   await expect(panel(page).getByRole('status')).toHaveText('Contraseña actualizada. Esta sesión continúa activa; las demás sesiones de tu cuenta se cerraron.');
   expect(await witness(fixture.password,'password-changed')).toMatchObject({credentialChanged:1,sessions:1});await capture(page,'password-03-actualizada',panel(page).getByRole('status'));
   await invalidated(other.page,'password-04-otra-sesion-invalidada');expect((await control.page.request.get('/api/auth/me')).status()).toBe(200);
  });
  await test.step('RF033 — Credencial anterior rechazada y nueva válida tras volver a acceder',async()=>{
   await page.bringToFront();await logout(page);expect((await attempt(page,fixture.password,password)).status()).toBe(401);
   await capture(page,'password-05-anterior-rechazada',panel(page).getByRole('alert'));
   expect((await attempt(page,fixture.password,changed)).status()).toBe(204);await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();
   await capture(page,'password-06-nueva-valida',panel(page).getByRole('heading',{name:'Seguridad de tu cuenta'}));await evidence('password-account-isolation',{oldCredentialRejected:true,newCredentialAccepted:true,otherOwnSessionRejected:true,unrelatedAccountPreserved:true});
  });
 }finally{await other.context.close();await control.context.close();}
});

test('CU08 recovery: Mailpit, neutralidad, mecanismos inválido/vencido/usado y nueva contraseña',async({page,browser,request})=>{
 const one=await independent(browser,fixture.recovery);const two=await independent(browser,fixture.recovery);const control=await independent(browser,fixture.control);
 try{
  await openLogin(page);await panel(page).getByRole('button',{name:'Olvidé mi contraseña',exact:true}).click();
  await test.step('RF194/A4 — Solicitud neutral para cuenta inexistente y propia; correo real visible',async()=>{
   await recoveryRequest(page,'cu08-missing@example.test');const unknown=await panel(page).getByRole('status').innerText();
   await recoveryRequest(page,fixture.recovery);expect(await panel(page).getByRole('status').innerText()).toBe(unknown);
   await capture(page,'recovery-01-neutral',panel(page).getByRole('status'));
  });
  const first=await mail(request,fixture.recovery,'Recupera');await showMail(browser,first.subject,'recovery-02-mailpit');await page.bringToFront();
  await panel(page).getByRole('button',{name:'Ingresar token recibido',exact:true}).click();
  await test.step('A5 — Rechazar mecanismo incorrecto y vencido sin cambiar credenciales',async()=>{
   await recoveryConfirm(page,'incorrecto',400);await capture(page,'recovery-03-incorrecto',panel(page).getByRole('alert'));
   action('expire-recovery',fixture.recovery);await recoveryConfirm(page,first.token,400);
   expect(await witness(fixture.recovery,'recovery-expired')).toMatchObject({credentialChanged:0,sessions:2});await capture(page,'recovery-04-vencido',panel(page).getByRole('alert'));
  });
  await test.step('A5 — Solicitar otro mecanismo, rechazando también el anterior sustituido',async()=>{
   await panel(page).getByRole('button',{name:'Solicitar otro token de recuperación',exact:true}).click();await recoveryRequest(page,fixture.recovery);
  });
  const second=await mail(request,fixture.recovery,'Recupera',[first.id]);
  await panel(page).getByRole('button',{name:'Ingresar token recibido',exact:true}).click();
  await test.step('RF194/A4 — Confirmar mecanismo válido y cerrar todas las sesiones propias',async()=>{
   await recoveryConfirm(page,first.token,400);await recoveryConfirm(page,second.token);
   expect(await witness(fixture.recovery,'recovery-changed')).toMatchObject({credentialChanged:1,sessions:0});await capture(page,'recovery-05-recuperada',panel(page).getByRole('status'));
   await invalidated(one.page,'recovery-06-primera-invalidada');await invalidated(two.page,'recovery-07-segunda-invalidada');expect((await control.page.request.get('/api/auth/me')).status()).toBe(200);
  });
  await test.step('A5/RF194 — Mecanismo utilizado rechazado; contraseña vieja no funciona y nueva sí',async()=>{
   await page.bringToFront();await panel(page).getByRole('button',{name:'Ya tengo un token de recuperación',exact:true}).click();await recoveryConfirm(page,second.token,400,changed);
   await capture(page,'recovery-08-utilizado',panel(page).getByRole('alert'));await panel(page).getByRole('button',{name:'Volver al inicio de sesión',exact:true}).click();
   expect((await attempt(page,fixture.recovery,password)).status()).toBe(401);expect((await attempt(page,fixture.recovery,recovered)).status()).toBe(204);
   await expect(page.getByRole('dialog',{name:'Seguridad de tu cuenta',exact:true})).toBeVisible();await capture(page,'recovery-09-nueva-valida',panel(page).getByRole('heading',{name:'Seguridad de tu cuenta'}));
   await evidence('recovery-acceptance',{neutralResponsesEqual:true,invalidExpiredReplacedUsedRejected:true,allOwnSessionsRejected:true,unrelatedAccountPreserved:true,oldCredentialRejected:true,newCredentialAccepted:true});
  });
 }finally{await one.context.close();await two.context.close();await control.context.close();}
});

test('CU08 access: credenciales neutrales, cuenta inactiva, correo pendiente y reenvío',async({page,request,browser})=>{
 await openLogin(page);let message='';
 await test.step('A1 — Correo desconocido y contraseña incorrecta producen el mismo rechazo neutral',async()=>{
  for(const [email,secret] of [[fixture.control,'ClaveIncorrecta123!'],['cu08-missing@example.test',password]]){
   expect((await attempt(page,email,secret)).status()).toBe(401);const actual=await panel(page).getByRole('alert').innerText();if(message)expect(actual).toBe(message);message=actual;
  }
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);await capture(page,'access-01-credenciales',panel(page).getByRole('alert'));
 });
 await test.step('A3 — Cuenta INACTIVA no consigue sesión',async()=>{
  expect((await attempt(page,fixture.inactive)).status()).toBe(401);expect((await page.request.get('/api/auth/me')).status()).toBe(401);await capture(page,'access-02-inactiva',panel(page).getByRole('alert'));
 });
 await test.step('A2 — Correo pendiente impide acceso y permite reenviar desde CU08',async()=>{
  const result=await attempt(page,fixture.pending);expect(result.status()).toBe(403);expect(await result.json()).toMatchObject({code:'EMAIL_NOT_VERIFIED'});
  await expect(panel(page).getByRole('button',{name:'Reenviar verificación',exact:true})).toBeVisible();await capture(page,'access-03-pendiente',panel(page).getByRole('alert'));
  const resend=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/email-verification/resend'&&r.request().method()==='POST');await panel(page).getByRole('button',{name:'Reenviar verificación',exact:true}).click();expect((await resend).status()).toBe(202);
 });
 const delivered=await mail(request,fixture.pending,'Verifica');await showMail(browser,delivered.subject,'access-04-mailpit');await page.bringToFront();
 await test.step('A2/paso11 — Verificar desde acceso y entrar con un único rol activo',async()=>{
  await panel(page).getByRole('button',{name:'Verificar correo',exact:true}).click();await panel(page).locator('ion-input[name=verificacion] input').fill(delivered.token);
  const confirm=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/email-verification/confirm'&&r.request().method()==='POST');await panel(page).getByRole('button',{name:'Confirmar correo',exact:true}).click();expect((await confirm).status()).toBe(204);
  await expect(panel(page).getByRole('status')).toHaveText('Correo verificado. Ya puedes iniciar sesión.');await capture(page,'access-05-verificado',panel(page).getByRole('status'));
  expect((await attempt(page,fixture.pending)).status()).toBe(204);expect(await(await page.request.get('/api/auth/me')).json()).toMatchObject({roles:['COMPRADOR'],activeRole:'COMPRADOR'});
  expect((await witness(fixture.pending,'access-pending-verified')).verified).toBe(1);await capture(page,'access-06-rol-unico',panel(page).getByText(/^Rol activo:/));
 });
});

test('CU08 restrictions: rol restringido, restricción global y acceso prohibido',async({page})=>{
 await test.step('A3 — VENDEDOR restringido no impide seleccionar COMPRADOR',async()=>{
  await login(page,fixture.restricted);await role(page,'VENDEDOR',403);await capture(page,'restrictions-01-vendedor-denegado',panel(page).getByRole('alert'));
  await role(page,'COMPRADOR');await permissions(page,204,403,'restrictions-buyer-allowed');await capture(page,'restrictions-02-comprador-permitido',panel(page).getByText(/^Rol activo:/));
 });
 await test.step('A14 — URL de otro rol devuelve403 en el navegador real',async()=>{
  const forbidden=await page.context().newPage();try{expect((await forbidden.request.get('/api/auth/validation/vendedor')).status()).toBe(403);
   let outcome='http403';try{const response=await forbidden.goto(process.env['SUST_BASE_URL']!+'/api/auth/validation/vendedor');expect(response!.status()).toBe(403);}
   catch(error){if(!String(error).includes('net::ERR_HTTP_RESPONSE_CODE_FAILURE'))throw error;outcome='chromiumHTTPErrorPage';}
   await forbidden.bringToFront();await capture(forbidden,'restrictions-03-url-prohibida');await evidence('restrictions-visual-denial',{authorizationStatus:403,browserNavigationOutcome:outcome});}finally{await forbidden.close();await page.bringToFront();}
  await logout(page);
 });
 await test.step('Brecha A3 — Restricción administrativa global permite login, pero deniega ambos roles',async()=>{
  expect((await attempt(page,fixture.global)).status()).toBe(204);await role(page,'COMPRADOR',403);await permissions(page,403,403,'restrictions-global-denied');
  await capture(page,'restrictions-04-global-brecha',panel(page).getByRole('alert'));
  await evidence('restrictions-functional-gap',{globalRestrictionLoginBlocked:false,globalRestrictionRoleFunctionsBlocked:true,singleRoleRestrictionPreservesOtherRole:true});
 });
});

test('CU08 expiration: mantener sesión, restaurar y rechazar sesiones vencidas',async({page,browser})=>{
 await test.step('Mantener sesión — Cookie persistente y plazo JDBC reales',async()=>{
  await login(page,fixture.persistent,password,true);const cookie=(await page.context().cookies()).find(c=>c.name==='SESSION')!;
  expect(cookie.expires>Date.now()/1000).toBe(true);expect(cookie.httpOnly).toBe(true);expect(cookie.sameSite).toBe('Lax');
  expect(await witness(fixture.persistent,'expiration-persistent')).toMatchObject({sessions:1,maxIdleSeconds:604800});
  await page.reload();await expect(page.locator('.welcome-strip')).toContainText('cu08-persistent');await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();await sessions(page);
  await capture(page,'expiration-01-restaurada',panel(page).getByRole('heading',{name:'Sesiones activas',exact:true}));await evidence('expiration-persistent-cookie',{persistent:true,httpOnly:true,sameSite:'Lax',restoredViaRealSession:true});
 });
 await test.step('A9 — Sesión persistente realmente vencida exige nueva autenticación',async()=>{
  action('expire-session',fixture.persistent);await invalidated(page,'expiration-02-persistente-vencida');expect((await witness(fixture.persistent,'expiration-persistent-expired')).sessions).toBe(0);
 });
 const normal=await independent(browser,fixture.expiry);
 try{
  await test.step('A9 — Sesión normal usa30min y también se rechaza tras vencer',async()=>{
   const cookie=(await normal.context.cookies()).find(c=>c.name==='SESSION')!;expect(cookie.expires).toBe(-1);expect((await witness(fixture.expiry,'expiration-normal')).maxIdleSeconds).toBe(1800);
   action('expire-session',fixture.expiry);await invalidated(normal.page,'expiration-03-normal-vencida');expect((await witness(fixture.expiry,'expiration-normal-expired')).sessions).toBe(0);
  });
 }finally{await normal.context.close();}
});

test('CU08 sessions: cerrar todas las demás, aislamiento y revocar la sesión actual',async({page,browser})=>{
 await login(page,fixture.sessions);const one=await independent(browser,fixture.sessions);const two=await independent(browser,fixture.sessions);const control=await independent(browser,fixture.control);
 try{
  await test.step('RF036 — Sólo sesiones propias y rechazo de revocación de cuenta ajena',async()=>{
   const foreign=(await sessions(control.page)).find(s=>s.current)!.id;await page.bringToFront();expect((await sessions(page)).length).toBe(3);
   const csrf=await(await page.request.get('/api/auth/csrf')).json();expect((await page.request.delete('/api/auth/sessions/'+foreign,{headers:{[csrf.headerName]:csrf.token}})).status()).toBe(404);
   expect((await control.page.request.get('/api/auth/me')).status()).toBe(200);await capture(page,'sessions-01-propias',panel(page).getByRole('heading',{name:'Sesiones activas',exact:true}));
  });
  await test.step('RF036 — Cerrar todas las demás desde UI conserva principal y cuenta ajena',async()=>{
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/sessions/revoke-others'&&r.request().method()==='POST');await panel(page).getByRole('button',{name:'Cerrar las demás sesiones',exact:true}).click();expect((await response).status()).toBe(204);
   await expect(panel(page).getByRole('status')).toHaveText('Las demás sesiones se cerraron. Esta sesión continúa activa.');await expect(panel(page).getByRole('listitem')).toHaveCount(1);
   await capture(page,'sessions-02-demas-cerradas',panel(page).getByRole('status'));await invalidated(one.page,'sessions-03-primera-revocada');await invalidated(two.page,'sessions-04-segunda-revocada');
   expect((await page.request.get('/api/auth/me')).status()).toBe(200);expect((await control.page.request.get('/api/auth/me')).status()).toBe(200);expect((await witness(fixture.sessions,'sessions-only-current')).sessions).toBe(1);
  });
  await test.step('RF036 — Revocar la sesión actual exige volver al acceso',async()=>{
   await page.bringToFront();await panel(page).getByRole('button',{name:'Revocar esta sesión',exact:true}).click();await panel(page).getByRole('button',{name:'Sí, revocar',exact:true}).click();
   await expect(panel(page).getByRole('status')).toHaveText('Sesión actual revocada. Vuelve a iniciar sesión para continuar.');expect((await page.request.get('/api/auth/me')).status()).toBe(401);
   expect((await witness(fixture.sessions,'sessions-self-revoked')).sessions).toBe(0);await capture(page,'sessions-05-actual-revocada',panel(page).getByRole('status'));
  });
 }finally{await one.context.close();await two.context.close();await control.context.close();}
});

test('CU08 mail: fallo SMTP explícito en verificación y neutral en recuperación',async({page,request})=>{
 await openLogin(page);expect((await attempt(page,fixture.mail)).status()).toBe(403);
 await test.step('A11 — Indisponibilidad SMTP real y aviso de error en verificación',async()=>{
  action('smtp-stop');
  try{
   const failure=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/email-verification/resend'&&r.request().method()==='POST');await panel(page).getByRole('button',{name:'Reenviar verificación',exact:true}).click();expect((await failure).status()).toBe(503);
   await expect(panel(page).getByRole('alert')).toContainText('No se pudo enviar el correo de verificación');await capture(page,'mail-01-error-explicito',panel(page).getByRole('alert'));
   await panel(page).getByRole('button',{name:'Olvidé mi contraseña',exact:true}).click();await recoveryRequest(page,fixture.mail);await expect(panel(page).getByRole('alert')).toHaveCount(0);await capture(page,'mail-02-recuperacion-neutral',panel(page).getByRole('status'));
   await evidence('mail-functional-gap',{verificationDeliveryFailureStatus:503,recoveryDeliveryFailureStatus:202,recoverySpecificDeliveryErrorVisible:false,neutralityPreserved:true});
  }finally{action('smtp-start');}
 });
 await test.step('A11 — Restaurar correo y volver a pedir un mecanismo real',async()=>{
  await expect.poll(async()=>{try{return(await request.get(process.env['SUST_MAIL_URL']!+'/api/v1/messages',{timeout:3000})).status();}catch{return 0;}},{timeout:20000}).toBe(200);
  // La respuesta neutral no acredita envío. Esperar correo real y solicitar de nuevo sólo si no aparece.
  let received=false;
  await expect.poll(async()=>{
   const box=await(await request.get(process.env['SUST_MAIL_URL']!+'/api/v1/messages')).json();received=box.messages.some((m:any)=>m.To.some((t:any)=>t.Address===fixture.mail)&&m.Subject.includes('Recupera'));
   if(!received)await recoveryRequest(page,fixture.mail);return received;
  },{timeout:45000,intervals:[1000,2000,3000]}).toBe(true);
  const delivered=await mail(request,fixture.mail,'Recupera');expect(!!delivered.token).toBe(true);await capture(page,'mail-03-reintento',panel(page).getByRole('status'));await evidence('mail-delivered-after-retry',{realSmtpDelivery:true,neutralMessage:neutral});
 });
});
