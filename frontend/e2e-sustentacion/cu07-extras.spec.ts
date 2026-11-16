import { expect } from '@playwright/test';
import { test, registration, openRegistration, fillBuyer, consent, submitRegistration, capture, witness, evidence, fixture, fixtureAction, registerPending, mailCode, confirm, resend, loginCurrent, verificationScreen } from './cu07-helpers';

test('CU07 A2 A3 A4: campos, contraseñas y consentimientos impiden el alta',async({page})=>{
  const email='cu07-validation@example.test';let posts=0;
  page.on('request',r=>{if(new URL(r.url()).pathname==='/api/buyers/register'&&r.method()==='POST')posts++;});
  await openRegistration(page);
  const form=registration(page);const submit=form.getByRole('button',{name:'Crear cuenta de comprador',exact:true});
  const error=(text:string)=>form.locator('p.error[role=alert]').filter({hasText:text});
  const field=(name:string)=>form.locator(`ion-input[name="${name}"] input`);
  await test.step('A2/A4 — Campos vacíos: identificar datos y consentimientos obligatorios',async()=>{
    await submit.click();
    for(const text of ['Escribe tu nombre completo.','Indica tu fecha de nacimiento.','Escribe un correo válido','Escribe un teléfono válido','La contraseña requiere','Confirma tu contraseña.','Acepta los términos','Acepta la política']) await expect(error(text)).toBeVisible();
    await capture(page,'extras-validation-01-obligatorios',error('Escribe tu nombre completo.'));
  });
  await fillBuyer(page,email);
  await test.step('Validar fecha futura, correo y teléfono sin perder datos válidos',async()=>{
    for(const [name,value,text,valid] of [
      ['birthDate','2999-01-01','La fecha no puede ser futura.','2000-05-20'],
      ['email','invalido','Escribe un correo válido',email],
      ['phone','abc','Escribe un teléfono válido','3000000000'],
    ]) {
      await field(name).fill(value);await submit.click();await expect(error(text)).toBeVisible();
      await capture(page,'extras-validation-02-'+name,error(text));await field(name).fill(valid);
    }
  });
  await test.step('A2 — Contraseña corta y límite UTF-8',async()=>{
    for(const [value,text,label] of [['corta','La contraseña requiere al menos 12 caracteres.','corta'],['é'.repeat(37),'La contraseña supera 72 bytes UTF-8.','utf8']]) {
      await field('password').fill(value);await field('passwordConfirmation').fill(value);await submit.click();
      await expect(error(text)).toBeVisible();await capture(page,'extras-validation-03-'+label,error(text));
    }
  });
  await test.step('A3 — Confirmación diferente conserva datos válidos',async()=>{
    await field('password').fill(process.env['SUST_PASSWORD']!);await field('passwordConfirmation').fill('ConfirmacionDiferente!');await submit.click();
    await expect(error('Las contraseñas no coinciden.')).toBeVisible();
    await expect(field('fullName')).toHaveValue('Persona Registro');await expect(field('email')).toHaveValue(email);
    await capture(page,'extras-validation-04-confirmacion',error('Las contraseñas no coinciden.'));
    await field('passwordConfirmation').fill(process.env['SUST_PASSWORD']!);
  });
  await test.step('A4 — Ningún consentimiento, sólo términos y sólo privacidad',async()=>{
    await submit.click();await expect(error('Acepta los términos')).toBeVisible();await expect(error('Acepta la política')).toBeVisible();
    await capture(page,'extras-validation-05-sin-aceptaciones',error('Acepta los términos'));
    await form.locator('ion-checkbox[name=terms]').click();await submit.click();await expect(error('Acepta la política')).toBeVisible();
    await capture(page,'extras-validation-06-falta-privacidad',error('Acepta la política'));
    await form.locator('ion-checkbox[name=terms]').click();await form.locator('ion-checkbox[name=privacy]').click();await submit.click();
    await expect(error('Acepta los términos')).toBeVisible();await capture(page,'extras-validation-07-faltan-terminos',error('Acepta los términos'));
    expect(posts).toBe(0);expect(await witness(email,'validation-no-account')).toMatchObject({accounts:0,buyerRoles:0,profiles:0,consents:0});
    await evidence('validation-no-posts',{registrationRequests:posts});
  });
});

test('CU07 A1 A8: cuenta existente, identidad validada, añadir rol y no duplicar',async({page,request})=>{
  const email=fixture.existing;
  const mailUrl=process.env['SUST_MAIL_URL']!;
  const beforeMail=await (await request.get(mailUrl+'/api/v1/messages')).json();
  await test.step('A1/A8 — El correo existente orienta al acceso y resiste el reintento',async()=>{
    await openRegistration(page);await fillBuyer(page,email);await consent(page);
    for(let attempt=1;attempt<=2;attempt++) {
      const response=await submitRegistration(page);expect(response.status()).toBe(409);
      expect(await response.json()).toMatchObject({code:'ACCOUNT_ACCESS_REQUIRED'});
      await expect(registration(page).getByRole('alert')).toContainText('inicia sesión');
      expect(await witness(email,'existing-duplicate-'+attempt)).toMatchObject({accounts:1,verified:1,roles:1,buyerRoles:0,existingProfilePreserved:1,existingCredentialsPreserved:1});
    }
    await capture(page,'extras-existing-01-orientacion',registration(page).getByRole('alert'));
  });
  await test.step('A1 — Validar identidad existente y habilitar COMPRADOR por UI',async()=>{
    await registration(page).getByRole('button',{name:'Ya tengo cuenta',exact:true}).click();
    await loginCurrent(page,email);
    const before=await (await page.request.get('/api/auth/me')).json();expect(before.accountId).toBe(fixture.existingId);
    await page.getByRole('button',{name:'Cerrar panel',exact:true}).click();
    await page.getByRole('button',{name:'Crear cuenta de comprador / Habilitar COMPRADOR',exact:true}).click();
    await expect(registration(page)).toContainText('Habilita COMPRADOR conservando tu cuenta y tu rol activo.');
    await registration(page).locator('ion-checkbox').nth(0).click();await registration(page).locator('ion-checkbox').nth(1).click();
    await capture(page,'extras-existing-02-habilitar',registration(page).getByRole('button',{name:'Habilitar COMPRADOR',exact:true}));
    const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/buyers/enable'&&r.request().method()==='POST');
    await registration(page).getByRole('button',{name:'Habilitar COMPRADOR',exact:true}).click();
    const result=await response;expect(result.status()).toBe(200);expect(await result.json()).toMatchObject({buyerRoleAdded:true});
    await expect(registration(page)).toContainText('Tu cuenta ya tiene COMPRADOR; no necesitas registrarte nuevamente.');
    const after=await (await page.request.get('/api/auth/me')).json();expect(after).toMatchObject({accountId:before.accountId,email,activeRole:before.activeRole});
    expect(after.roles.sort()).toEqual(['COMPRADOR','VENDEDOR']);
  });
  await test.step('RF191/A1 — Conservar correo verificado y mostrar que no requiere otra alta',async()=>{
    expect(await witness(email,'existing-enabled')).toMatchObject({accounts:1,verified:1,roles:2,buyerRoles:1,consents:1,tokens:0,existingProfilePreserved:1,existingCredentialsPreserved:1});
    const afterMail=await (await request.get(mailUrl+'/api/v1/messages')).json();expect(afterMail.messages.length).toBe(beforeMail.messages.length);
    await expect(registration(page).getByRole('button',{name:'Habilitar COMPRADOR',exact:true})).toHaveCount(0);
    await capture(page,'extras-existing-03-ya-comprador',registration(page).getByText('Tu cuenta ya tiene COMPRADOR; no necesitas registrarte nuevamente. Puedes seleccionar el rol activo desde tu cuenta.',{exact:true}));
    await evidence('existing-no-new-verification',{sameAccount:true,emailStillVerified:true,newVerificationMessages:0});
  });
});

test('CU07 A5 A6 A7: pendiente, reenvío y códigos incorrecto, sustituido, vencido y usado',async({page,request})=>{
  const email='cu07-verification@example.test';let first:{token:string;id:string};let next:{token:string;id:string};
  await test.step('A5 — Cuenta nueva pendiente rechaza el acceso con credenciales correctas',async()=>{
    await registerPending(page,email);first=await mailCode(request,email);
    expect(await witness(email,'verification-pending')).toMatchObject({accounts:1,verified:0,buyerRoles:1});
    await registration(page).getByRole('button',{name:'Ir al inicio de sesión',exact:true}).click();
    const response=await loginCurrent(page,email,403);expect(await response.json()).toMatchObject({code:'EMAIL_NOT_VERIFIED'});
    await expect(page.locator('app-acceso [role=alert]')).toBeVisible();
    await capture(page,'extras-verification-01-acceso-pendiente',page.locator('app-acceso [role=alert]'));
    await verificationScreen(page);
  });
  await test.step('A7 — Mecanismo incorrecto no activa la cuenta',async()=>{
    await confirm(page,'mecanismo-invalido',400);
    expect((await witness(email,'verification-invalid')).verified).toBe(0);
    await capture(page,'extras-verification-02-invalido',registration(page).getByRole('alert'));
  });
  await test.step('A6 — Reenviar por UI y recibir un nuevo correo real',async()=>{
    await registration(page).locator('ion-input[type=email] input').fill(email);await resend(page);
    next=await mailCode(request,email,first.id);
    if(next.token===first.token)throw new Error('El mecanismo no fue renovado (contenido reservado)');
    await capture(page,'extras-verification-03-reenvio',registration(page).getByRole('status'));
  });
  await test.step('A7 — Rechazar mecanismo anterior sustituido y actual vencido',async()=>{
    await confirm(page,first.token,400);expect((await witness(email,'verification-replaced')).verified).toBe(0);
    await capture(page,'extras-verification-04-sustituido',registration(page).getByRole('alert'));
    fixtureAction('expire',email);await evidence('verification-expiry-fixture',{ownPendingTokenExpiredBySql:true,securityRulesUnchanged:true});
    await confirm(page,next.token,400);expect((await witness(email,'verification-expired')).verified).toBe(0);
    await capture(page,'extras-verification-05-vencido',registration(page).getByRole('alert'));
  });
  let used='';
  await test.step('A6/A7 — Recuperar con un mecanismo nuevo válido',async()=>{
    await resend(page);const current=await mailCode(request,email,[first.id,next.id]);used=current.token;
    await confirm(page,current.token,204);expect((await witness(email,'verification-recovered')).verified).toBe(1);
    await capture(page,'extras-verification-06-recuperado',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
  });
  await test.step('A7 — Rechazar mecanismo utilizado y conservar la activación legítima',async()=>{
    await verificationScreen(page);await confirm(page,used,400);
    expect(await witness(email,'verification-used')).toMatchObject({accounts:1,verified:1,buyerRoles:1});
    await capture(page,'extras-verification-07-utilizado',registration(page).getByRole('alert'));
  });
});

test('CU07 A6 A8: indisponibilidad SMTP real, sin duplicados y recuperación',async({page,request})=>{
  const email='cu07-recovery@example.test';
  await openRegistration(page);await fillBuyer(page,email);await consent(page);
  await test.step('A8 — Interrumpir sólo Mailpit propio y registrar por UI',async()=>{
    fixtureAction('smtp-stop');
    try {
      const response=await submitRegistration(page);expect(response.status()).toBe(201);
      expect(await response.json()).toMatchObject({emailVerificationRequired:true,verificationDeliveryFailed:true});
      await expect(registration(page).getByRole('alert')).toContainText('El registro se conservó pendiente. Solicita un reenvío.');
      expect(await witness(email,'smtp-pending')).toMatchObject({accounts:1,verified:0,buyerRoles:1,profiles:1,consents:1});
      await capture(page,'extras-smtp-01-conservado-pendiente',registration(page).getByRole('alert'));
    } finally {fixtureAction('smtp-start');}
  });
  await test.step('A8 — Nuevo intento informa cuenta existente sin duplicar',async()=>{
    await expect.poll(async()=>{try{return (await request.get(process.env['SUST_MAIL_URL']!+'/api/v1/messages',{timeout:3000})).status();}catch{return 0;}},{timeout:20000}).toBe(200);
    await openRegistration(page);await fillBuyer(page,email);await consent(page);
    const response=await submitRegistration(page);expect(response.status()).toBe(409);
    await expect(registration(page).getByRole('alert')).toContainText('verificación pendiente');
    expect(await witness(email,'smtp-no-duplicates')).toMatchObject({accounts:1,profiles:1,buyerRoles:1,consents:1,verified:0});
    await capture(page,'extras-smtp-02-no-duplicados',registration(page).getByRole('alert'));
  });
  await test.step('A6 — Reenvío SMTP real, confirmación y activación tras el fallo',async()=>{
    await registration(page).getByRole('button',{name:'Ya tengo un código o necesito reenviarlo',exact:true}).click();
    await registration(page).locator('ion-input[type=email] input').fill(email);
    const statuses=await resend(page,true);await evidence('smtp-resend-attempts',{httpStatuses:statuses,explicitDeliveryRecovery:true});
    const delivered=await mailCode(request,email);await confirm(page,delivered.token,204);
    expect(await witness(email,'smtp-recovered')).toMatchObject({accounts:1,verified:1,buyerRoles:1,consents:1});
    await capture(page,'extras-smtp-03-recuperado',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
  });
});

test('CU07 A8: backend propio indisponible, error visible y reintento sin duplicados',async({page,request})=>{
  const email='cu07-error@example.test';
  await openRegistration(page);await fillBuyer(page,email);await consent(page);
  await test.step('A8 — El backend propio no disponible impide registrar y muestra el problema',async()=>{
    fixtureAction('backend-stop');
    try {
      // El cliente puede fallar al obtener CSRF o en el POST; ambos son peticiones reales.
      const failure=page.waitForResponse(r=>['/api/auth/csrf','/api/buyers/register'].includes(new URL(r.url()).pathname)&&r.status()>=500);
      await registration(page).getByRole('button',{name:'Crear cuenta de comprador',exact:true}).click();
      const result=await failure;expect([502,503,504]).toContain(result.status());
      await expect(registration(page).getByRole('alert')).toContainText('No se pudo completar la operación. Inténtalo nuevamente.');
      await expect(registration(page).locator('ion-input[name=email] input')).toHaveValue(email);
      expect(await witness(email,'backend-unavailable-no-account')).toMatchObject({accounts:0,buyerRoles:0,profiles:0,consents:0});
      await evidence('backend-unavailable-real-http',{status:result.status(),path:new URL(result.url()).pathname,ownBackendStopped:true});
      await capture(page,'extras-backend-01-error-visible',registration(page).getByRole('alert'));
    } finally {fixtureAction('backend-start');}
  });
  await test.step('A8 — Restaurar el backend y repetir la misma alta por UI',async()=>{
    await expect.poll(async()=>{try{return (await request.get('/api/actuator/health/readiness',{timeout:5000})).status();}catch{return 0;}},{timeout:120000,intervals:[1000,2000,3000]}).toBe(200);
    const response=await submitRegistration(page);expect(response.status()).toBe(201);
    expect(await response.json()).toMatchObject({emailVerificationRequired:true,verificationDeliveryFailed:false});
    expect(await witness(email,'backend-retry-single-account')).toMatchObject({accounts:1,verified:0,buyerRoles:1,profiles:1,consents:1});
    await expect(registration(page).getByRole('status')).toHaveText('Se envió una verificación a tu correo electrónico');
    await capture(page,'extras-backend-02-reintento-correcto',registration(page).getByRole('status'));
    const mail=await mailCode(request,email);await confirm(page,mail.token,204);
    expect(await witness(email,'backend-retry-verified')).toMatchObject({accounts:1,verified:1,buyerRoles:1,profiles:1,consents:1});
    await capture(page,'extras-backend-03-cuenta-verificada',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
  });
});
