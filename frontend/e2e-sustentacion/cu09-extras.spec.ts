import {expect} from '@playwright/test';
import {test,fixture,password,profile,field,login,open,edit,review,save,api,checkbox,essential,capture,witness,evidence,action,independent,newEmail,mail,showMail,confirmEmail,resend} from './cu09-helpers';

test('CU09 validation: campos, fecha, identidad y correo ocupado sin modificar datos',async({page})=>{
 await login(page,fixture.validation);await open(page);await edit(page);
 const next=profile(page).getByRole('button',{name:'Revisar cambios',exact:true});
 await test.step('A1/A6 — Vacíos y formatos inválidos identifican el campo y conservan el borrador',async()=>{
  await field(page,'fullName').fill('Nombre Borrador');
  for(const [name,value,text,valid,label] of [
   ['fullName','   ','Escribe tu nombre completo.','Nombre Borrador','nombre-vacio'],
   ['fullName','X'.repeat(151),'El nombre admite hasta 150 caracteres.','Nombre Borrador','nombre-largo'],
   ['phone','abc','Escribe un teléfono válido.','3000000000','telefono'],
   ['phone','','Escribe un teléfono válido.','3000000000','telefono-vacio'],
   ['email','invalido','Escribe un correo válido',fixture.validation,'correo'],
   ['email','','Escribe un correo válido',fixture.validation,'correo-vacio'],
   ['birthDate','','Indica tu fecha de nacimiento.','2000-01-01','fecha-vacia'],
   ['birthDate','2999-01-01','La fecha no puede ser futura.','2000-01-01','fecha-futura'],
  ]){
   await field(page,name).fill(value);await next.click();await expect(profile(page).locator('#error-perfil-'+name)).toContainText(text);
   if(name!=='fullName')await expect(field(page,'fullName')).toHaveValue('Nombre Borrador');
   await capture(page,'validation-01-'+label,profile(page).locator('#error-perfil-'+name));await field(page,name).fill(valid);
  }
  expect(await witness('validation','validation-no-mutation')).toMatchObject({originalData:1,version:0,oldEmail:1});
 });
 await test.step('RF032/A5 — Exigir contraseña y rechazar la incorrecta sin aplicar cambios',async()=>{
  await review(page);await profile(page).getByRole('button',{name:'Confirmar y guardar',exact:true}).click();
  await expect(profile(page).getByRole('alert').filter({hasText:'Confirma tu identidad'})).toBeVisible();
  await capture(page,'validation-02-identidad-requerida',profile(page).getByRole('alert').filter({hasText:'Confirma tu identidad'}));
  await field(page,'currentPassword').fill('ConfirmacionIncorrecta123!');const result=await save(page,403);expect(await result.json()).toMatchObject({code:'CURRENT_PASSWORD_INVALID'});
  await expect(profile(page).getByRole('alert').filter({hasText:'La contraseña actual es incorrecta'}).first()).toBeVisible();
  expect(await witness('validation','validation-wrong-password')).toMatchObject({originalData:1,version:0,oldEmail:1});await capture(page,'validation-03-contrasena-incorrecta',profile(page).getByRole('alert').first());
 });
 await test.step('A3 — Correo ya ocupado se rechaza sin cambiar la cuenta propia ni la ajena',async()=>{
  await profile(page).getByRole('button',{name:'Volver a editar',exact:true}).click();await field(page,'email').fill(fixture.control);await review(page);await field(page,'currentPassword').fill(password);
  const result=await save(page,409);expect(await result.json()).toMatchObject({code:'EMAIL_ALREADY_REGISTERED'});
  await expect(profile(page).getByRole('alert').first()).toContainText('El correo no está disponible');
  expect(await witness('validation','validation-email-occupied')).toMatchObject({originalData:1,version:0,oldEmail:1,pendingEmail:0});
  expect(await witness('control','validation-control-unchanged')).toMatchObject({originalData:1,version:0,oldEmail:1});await capture(page,'validation-04-correo-ocupado',profile(page).getByRole('alert').first());
 });
});

test('CU09 email: mantener anterior, reenviar por Mailpit y verificar nueva dirección',async({page,request,browser})=>{
 await login(page,fixture.email);await open(page);
 await test.step('A2/A4 — Solicitar cambio con identidad y conservar dirección anterior',async()=>{
  await newEmail(page,fixture.newEmail);expect(await api(page)).toMatchObject({email:fixture.email,pendingEmail:fixture.newEmail});
  expect(await witness('email','email-pending')).toMatchObject({oldEmail:1,pendingEmail:1});await capture(page,'email-01-pendiente',profile(page).getByRole('heading',{name:'Verificar nuevo correo'}));
  await page.reload();await open(page);expect(await api(page)).toMatchObject({email:fixture.email,pendingEmail:fixture.newEmail});await capture(page,'email-02-anterior-vigente',profile(page).getByText(fixture.email,{exact:true}));
 });
 const other=await independent(browser,fixture.email);let first=await mail(request,fixture.newEmail);
 try{
  await test.step('Verificación — Rechazar mecanismo incorrecto/vencido sin cambiar el correo',async()=>{
   await page.bringToFront();await confirmEmail(page,'incorrecto',400);action('expire-email','email');await confirmEmail(page,first.token,400);
   expect((await witness('email','email-expired')).oldEmail).toBe(1);await capture(page,'email-03-mecanismo-rechazado',profile(page).getByRole('alert').first());
  });
  await test.step('A4 — Reenviar desde el perfil y mostrar el mensaje real en Mailpit',async()=>{
   await resend(page);await capture(page,'email-04-reenvio',profile(page).getByRole('status'));
  });
  const second=await mail(request,fixture.newEmail,[first.id]);await showMail(browser,second.subject,'email-05-mailpit');await page.bringToFront();
  await test.step('A2 — Confirmar el nuevo mecanismo y revocar las sesiones de correo anterior',async()=>{
   await confirmEmail(page,first.token,400);await confirmEmail(page,second.token);
   expect(await witness('email','email-confirmed')).toMatchObject({oldEmail:0,pendingEmail:0,sessions:0});expect((await other.page.request.get('/api/account/profile')).status()).toBe(401);
   await capture(page,'email-06-verificado-login',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
  });
  await test.step('RF032 — Dirección anterior ya no accede; la nueva abre el perfil persistido',async()=>{
   await page.locator('ion-input[name=correo] input').fill(fixture.email);await page.locator('ion-input[name=clave] input').fill(password);
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login'&&r.request().method()==='POST');await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();expect((await response).status()).toBe(401);
   await login(page,fixture.newEmail);await open(page);expect(await api(page)).toMatchObject({email:fixture.newEmail,pendingEmail:null});await expect(profile(page).getByText(fixture.newEmail,{exact:true})).toBeVisible();
   await capture(page,'email-07-nueva-direccion',profile(page).getByText(fixture.newEmail,{exact:true}));await evidence('email-acceptance',{oldAddressPreservedUntilVerification:true,newAddressVerifiedThroughRealMail:true,oldLoginRejected:true,newLoginAccepted:true,oldSessionsRejected:true});
  });
 }finally{await other.context.close();}
});

test('CU09 preferences: opcionales on/off y correo esencial pese al opt-out',async({page,request,browser})=>{
 await login(page,fixture.preferences);await open(page);
 await test.step('RF034 — Activar ambas preferencias y comprobar persistencia sin pedir contraseña',async()=>{
  await edit(page);await checkbox(page,'promotions',true);await checkbox(page,'marketplaceNews',true);await essential(page);await review(page);
  await expect(field(page,'currentPassword')).toHaveCount(0);await save(page);await page.reload();await open(page);expect(await api(page)).toMatchObject({promotions:true,marketplaceNews:true,essentialCommunications:true});
  expect(await witness('preferences','preferences-on')).toMatchObject({promotions:1,news:1});await capture(page,'preferences-01-activadas',profile(page).getByRole('heading',{name:'Mi perfil y preferencias'}));
 });
 await test.step('RF034 — Desactivar ambas opcionales conservando esenciales',async()=>{
  await edit(page);await checkbox(page,'promotions',false);await checkbox(page,'marketplaceNews',false);await review(page);await save(page);await page.reload();await open(page);
  expect(await api(page)).toMatchObject({promotions:false,marketplaceNews:false,essentialCommunications:true});await essential(page);
  expect(await witness('preferences','preferences-off')).toMatchObject({promotions:0,news:0});await capture(page,'preferences-02-desactivadas',profile(page).getByRole('heading',{name:'Mi perfil y preferencias'}));
 });
 await test.step('Esencial de seguridad — Solicitar cambio de correo con opt-out y recibir correo real',async()=>{
  await newEmail(page,fixture.preferencesEmail);const delivered=await mail(request,fixture.preferencesEmail);await showMail(browser,delivered.subject,'preferences-03-esencial-mailpit');await page.bringToFront();
  expect(await api(page)).toMatchObject({promotions:false,marketplaceNews:false,essentialCommunications:true,email:fixture.preferences});
  await evidence('preferences-essential-delivery',{optionalFlagsBothFalse:true,realSecurityEmailDelivered:true,essentialCommunications:true,optionalCampaignFilteringNotImplemented:true,otherFourEssentialCategoriesNotExecuted:true});
 });
});

test('CU09 blocked: cierre impedido por pedidos, reclamaciones y devoluciones',async({page})=>{
 await login(page,fixture.blocked);await open(page);
 await test.step('A7/A8 — Mostrar consecuencias, impedimentos y ausencia de acción de cierre',async()=>{
  await profile(page).getByRole('button',{name:'Solicitar cierre de cuenta',exact:true}).click();await expect(profile(page)).toContainText('Se conservarán pedidos');
  const response=await page.request.get('/api/account/profile/closure');expect(response.status()).toBe(200);const result=await response.json();expect(result.allowed).toBe(false);
  expect(result.blockers.map((b:any)=>b.code).sort()).toEqual(['CLAIMS','ORDERS','RETURNS']);
  for(const text of ['Pedidos sin finalizar','Reclamaciones o sus reembolsos pendientes','Devoluciones de productos sin finalizar'])await expect(profile(page).getByRole('listitem').filter({hasText:text})).toBeVisible();
  await expect(profile(page).getByRole('button',{name:'Desactivar mi cuenta',exact:true})).toHaveCount(0);await expect(field(page,'claveCierre')).toHaveCount(0);
  expect(await witness('blocked','blocked-account-retained')).toMatchObject({active:1,orders:3,claims:1,returns:1,profiles:1});
  await evidence('blocked-reasons',{allowed:false,blockerCodes:result.blockers.map((b:any)=>b.code),otherSevenBlockerTypesNotClaimed:true});await capture(page,'blocked-01-impedimentos',profile(page).getByRole('heading',{name:'Cierre de cuenta',exact:true}));
 });
});

test('CU09 closure: desactivar sólo cuenta elegible y conservar historia',async({page,browser})=>{
 await login(page,fixture.closure);await open(page);const other=await independent(browser,fixture.closure);
 try{
  await page.bringToFront();
  await test.step('A7/A9 — Explicar consecuencias y exigir confirmación y contraseña nuevamente',async()=>{
   expect(await witness('closure','closure-before')).toMatchObject({active:1,orders:1,items:1,history:1,claims:1,returns:1,refunds:1,activeProducts:1});
   await profile(page).getByRole('button',{name:'Solicitar cierre de cuenta',exact:true}).click();await expect(profile(page)).toContainText('Se conservarán pedidos');
   const data=await(await page.request.get('/api/account/profile/closure')).json();expect(data).toMatchObject({allowed:true,blockers:[]});
   await expect(profile(page).getByRole('button',{name:'Desactivar mi cuenta',exact:true})).toBeDisabled();await field(page,'claveCierre').fill(password);
   await expect(profile(page).getByRole('button',{name:'Desactivar mi cuenta',exact:true})).toBeDisabled();
   const check=profile(page).getByRole('checkbox',{name:'Comprendo las consecuencias y confirmo el cierre'});await check.click();await expect(check).toBeChecked();
   await capture(page,'closure-01-consecuencias',profile(page).getByRole('heading',{name:'Cierre de cuenta',exact:true}));
  });
  await test.step('A9 — Desactivar mediante UI y verificar historial, perfil y referencias conservados',async()=>{
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/account/profile/closure'&&r.request().method()==='POST');await profile(page).getByRole('button',{name:'Desactivar mi cuenta',exact:true}).click();expect((await response).status()).toBe(204);
   await expect(page.locator('.welcome-strip')).toContainText('Hola, visitante');expect((await page.request.get('/api/account/profile')).status()).toBe(401);expect((await other.page.request.get('/api/account/profile')).status()).toBe(401);
   expect(await witness('closure','closure-history-preserved')).toMatchObject({inactive:1,profiles:1,orders:1,items:1,history:1,claims:1,returns:1,refunds:1,sessions:0,suspendedStores:1,activeProducts:0});
   expect(await witness('control','closure-control-unchanged')).toMatchObject({active:1,originalData:1,version:0});await capture(page,'closure-02-desactivada',page.locator('.welcome-strip'));
  });
  await test.step('A9 — La cuenta desactivada no puede iniciar sesión',async()=>{
   await page.goto('/');await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();await page.locator('ion-input[name=correo] input').fill(fixture.closure);await page.locator('ion-input[name=clave] input').fill(password);
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login'&&r.request().method()==='POST');await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();expect((await response).status()).toBe(401);
   await capture(page,'closure-03-acceso-rechazado',page.locator('app-acceso [role=alert]'));
  });
 }finally{await other.context.close();}
});

test('CU09 failure: error real de infraestructura conserva datos y permite reintento',async({page,request})=>{
 await login(page,fixture.failure);await open(page);await edit(page);await field(page,'fullName').fill('Persona Actualizada');await field(page,'birthDate').fill('1995-06-15');await field(page,'phone').fill('3100000000');await review(page);await field(page,'currentPassword').fill(password);
 await test.step('A10 — Guardar con el backend propio detenido muestra error sin aplicar cambios',async()=>{
  action('backend-stop','failure');
  try{
   const failure=page.waitForResponse(r=>['/api/account/profile','/api/auth/csrf'].includes(new URL(r.url()).pathname)&&r.status()>=500);
   await profile(page).getByRole('button',{name:'Confirmar y guardar',exact:true}).click();const result=await failure;expect([502,503,504]).toContain(result.status());
   await expect(profile(page).getByRole('alert').first()).toContainText('Tus cambios sin guardar se conservan');await expect(field(page,'fullName')).toHaveValue('Persona Actualizada');
   expect(await witness('failure','failure-original-preserved')).toMatchObject({originalData:1,version:0,promotions:0,news:1});
   await evidence('failure-real-http',{status:result.status(),path:new URL(result.url()).pathname,method:result.request().method(),ownBackendStopped:true,midTransactionFailureNotClaimed:true});
   await capture(page,'failure-01-error-real',profile(page).getByRole('alert').first());
  }finally{action('backend-start','failure');}
 });
 await test.step('A10 — Restaurar servicio, comprobar datos anteriores y repetir el guardado por UI',async()=>{
  await expect.poll(async()=>{try{return(await request.get('/api/actuator/health/readiness',{timeout:5000})).status();}catch{return 0;}},{timeout:120000,intervals:[1000,2000,3000]}).toBe(200);
  expect(await api(page)).toMatchObject({fullName:'Persona Demostración',birthDate:'2000-01-01',phone:'3000000000'});
  await save(page);await expect(profile(page).getByRole('status')).toHaveText('Perfil y preferencias actualizados.');expect(await witness('failure','failure-retry-persisted')).toMatchObject({updatedData:1,version:1});
  await capture(page,'failure-02-reintento-exitoso',profile(page).getByRole('status'));await page.reload();await open(page);await expect(profile(page).getByText('Persona Actualizada',{exact:true})).toBeVisible();
 });
});
