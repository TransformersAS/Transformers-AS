import { expect } from '@playwright/test';
import { test, openRegistration, fillBuyer, consent, submitRegistration, registration, capture, witness, mailCode, confirm, loginCurrent, evidence } from './cu07-helpers';
test('CU07 principal: registrar, recibir correo real, verificar y acceder',async({page,request,browser})=>{
  const email='registration@example.test';
  await test.step('01 — Abrir el registro de comprador',async()=>{
    await openRegistration(page);
    await capture(page,'main-01-formulario',registration(page).getByRole('heading',{name:'Registro de comprador'}));
  });
  await test.step('02 — Diligenciar datos, contraseña y consentimientos',async()=>{
    await fillBuyer(page,email);await consent(page);
    await capture(page,'main-02-datos-y-consentimientos',registration(page).locator('ion-checkbox[name="terms"]'));
  });
  await test.step('03 — Crear la cuenta y comprobar que queda pendiente',async()=>{
    const response=await submitRegistration(page);expect(response.status()).toBe(201);
    expect(await response.json()).toMatchObject({emailVerificationRequired:true,verificationDeliveryFailed:false});
    await expect(registration(page).getByRole('status')).toHaveText('Se envió una verificación a tu correo electrónico');
    expect(await witness(email,'main-pending')).toMatchObject({accounts:1,verified:0,buyerRoles:1,profiles:1,consents:1,profileMatches:1});
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await capture(page,'main-03-pendiente',registration(page).getByRole('status'));
  });
  const mail=await mailCode(request,email);
  await test.step('04 — Consultar el mensaje entregado al Mailpit real',async()=>{
    const inbox=await browser.newContext({viewport:{width:1440,height:1000},serviceWorkers:'block'});
    try {
      const mailbox=await inbox.newPage();await mailbox.goto(process.env['SUST_MAIL_URL']!);await mailbox.bringToFront();
      const subject=mailbox.getByText(mail.subject,{exact:true}).first();await expect(subject).toBeVisible();
      await capture(mailbox,'main-04-mailpit-bandeja',subject);
      await subject.click();
      await expect(mailbox.getByText(email,{exact:false}).first()).toBeVisible();
      await capture(mailbox,'main-05-mailpit-mensaje',undefined,[mail.token]);
      await evidence('main-mailpit',{delivered:true,visibleInbox:true,realSmtp:true});
    } finally {await inbox.close();await page.bringToFront();}
  });
  await test.step('05 — Confirmar el mecanismo recibido y comprobar activación',async()=>{
    await confirm(page,mail.token,204);
    expect(await witness(email,'main-activated')).toMatchObject({accounts:1,verified:1,buyerRoles:1,consents:1,consumedTokens:1});
    await capture(page,'main-06-activado-login',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
  });
  await test.step('06 — Acceder con las nuevas credenciales como COMPRADOR',async()=>{
    await loginCurrent(page,email);
    const me=await page.request.get('/api/auth/me');expect(me.status()).toBe(200);
    expect(await me.json()).toMatchObject({email,roles:['COMPRADOR'],activeRole:'COMPRADOR'});
    await capture(page,'main-07-comprador-autenticado',page.getByRole('heading',{name:'Seguridad de tu cuenta',exact:true}));
  });
});
