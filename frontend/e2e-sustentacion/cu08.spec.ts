import {expect} from '@playwright/test';
import {test,fixture,panel,login,role,permissions,independent,sessions,invalidated,logout,capture,witness,evidence} from './cu08-helpers';
test('CU08 principal: acceso, roles, sesiones independientes, revocación y cierre',async({page,browser,request})=>{
 await test.step('01 — Iniciar sesión: varios roles requieren selección',async()=>{
  await login(page,fixture.main);expect((await(await page.request.get('/api/auth/me')).json()).activeRole).toBeNull();
  await permissions(page,403,403,'main-no-role');await capture(page,'main-01-seleccionar-rol',panel(page).getByText(/^Rol activo:/));
 });
 const cookie=(await page.context().cookies()).find(c=>c.name==='SESSION')!;
 await test.step('02 — Seleccionar y cambiar rol, aplicando permisos en la misma sesión',async()=>{
  await role(page,'VENDEDOR');await permissions(page,403,204,'main-seller');await capture(page,'main-02-vendedor',panel(page).getByText(/^Rol activo:/));
  await role(page,'COMPRADOR');await permissions(page,204,403,'main-buyer');await capture(page,'main-03-comprador',panel(page).getByText(/^Rol activo:/));
  expect((await page.context().cookies()).find(c=>c.name==='SESSION')!.value===cookie.value).toBe(true);
 });
 const other=await test.step('03 — Abrir otra ventana y autenticar una sesión independiente',()=>independent(browser,fixture.main));
 try{
  let otherId='';
  await test.step('04 — Comparar sesiones reales, navegador y actividad',async()=>{
   const cookie2=(await other.context.cookies()).find(c=>c.name==='SESSION')!;expect(cookie2.value!==cookie.value).toBe(true);
   await other.page.bringToFront();const data=await sessions(other.page);otherId=data.find(s=>s.current)!.id;
   await capture(other.page,'main-04-otro-contexto',panel(other.page).getByText('Esta es tu sesión actual',{exact:true}));
   await page.bringToFront();const list=await sessions(page);expect(list.length).toBe(2);expect(list.every(s=>!!s.deviceDescription&&!!s.lastAccessedAt)).toBe(true);
   expect(await witness(fixture.main,'main-two-sessions')).toMatchObject({sessions:2});await capture(page,'main-05-dos-sesiones',panel(page).getByRole('heading',{name:'Sesiones activas',exact:true}));
  });
  await test.step('05 — Revocar únicamente la otra sesión desde la UI',async()=>{
   const item=panel(page).getByRole('listitem').filter({has:page.getByText(otherId,{exact:true})});
   await item.getByRole('button',{name:'Revocar sesión',exact:true}).click();
   await panel(page).getByRole('group',{name:'Confirmar revocación'}).getByRole('button',{name:'Sí, revocar',exact:true}).click();
   await expect(panel(page).getByRole('status')).toHaveText('Sesión revocada.');await expect(item).toHaveCount(0);
   await capture(page,'main-06-revocacion',panel(page).getByRole('status'));await invalidated(other.page,'main-07-acceso-revocado');
   expect((await page.request.get('/api/auth/me')).status()).toBe(200);expect(await witness(fixture.main,'main-after-revocation')).toMatchObject({sessions:1});
  });
 }finally{await other.context.close();await page.bringToFront();}
 await test.step('06 — Cerrar sesión actual y comprobar invalidación en servidor',async()=>{
  await logout(page);expect((await request.get('/api/auth/me',{headers:{Cookie:`SESSION=${cookie.value}`}})).status()).toBe(401);
  expect(await witness(fixture.main,'main-logged-out')).toMatchObject({sessions:0});await evidence('main-cookie-invalidated',{oldCookieRejected:true,otherContextWasIndependent:true});
  await capture(page,'main-08-cierre',page.getByRole('heading',{name:'Acceso a tu cuenta',exact:true}));
 });
});
