import {expect} from '@playwright/test';
import {test,fixture,password,profile,field,login,open,edit,review,save,api,checkbox,essential,capture,witness,evidence} from './cu09-helpers';
test('CU09 principal: datos personales, preferencias, identidad y persistencia',async({page})=>{
 await test.step('01 — Acceder y consultar los cuatro datos y las preferencias',async()=>{
  await login(page,fixture.main);await open(page);for(const text of ['Persona Demostración','2000-01-01',fixture.main,'3000000000'])await expect(profile(page).getByText(text,{exact:true})).toBeVisible();
  expect(await api(page)).toMatchObject({promotions:false,marketplaceNews:true,essentialCommunications:true});await capture(page,'main-01-original',profile(page).getByRole('heading',{name:'Mi perfil y preferencias'}));
 });
 await test.step('02 — Editar nombre, fecha y teléfono; configurar ambas preferencias',async()=>{
  await edit(page);await field(page,'fullName').fill('Persona Actualizada');await field(page,'birthDate').fill('1995-06-15');await field(page,'phone').fill('3100000000');
  await checkbox(page,'promotions',true);await checkbox(page,'marketplaceNews',false);await essential(page);await capture(page,'main-02-edicion',profile(page).locator('ion-checkbox[name=promotions]'));
 });
 await test.step('03 — Revisar y confirmar identidad antes del guardado',async()=>{
  await review(page);await expect(field(page,'currentPassword')).toBeVisible();await field(page,'currentPassword').fill(password);await capture(page,'main-03-revision',profile(page).getByRole('button',{name:'Confirmar y guardar',exact:true}));
 });
 await test.step('04 — Guardar y mostrar inmediatamente los datos actualizados',async()=>{
  await save(page);await expect(profile(page).getByRole('status')).toHaveText('Perfil y preferencias actualizados.');
  for(const text of ['Persona Actualizada','1995-06-15','3100000000'])await expect(profile(page).getByText(text,{exact:true})).toBeVisible();await essential(page);await capture(page,'main-04-guardado',profile(page).getByRole('status'));
 });
 await test.step('05 — Recargar y comprobar persistencia visual, API y MySQL',async()=>{
  await page.reload();await open(page);for(const text of ['Persona Actualizada','1995-06-15','3100000000'])await expect(profile(page).getByText(text,{exact:true})).toBeVisible();
  expect(await api(page)).toMatchObject({fullName:'Persona Actualizada',birthDate:'1995-06-15',phone:'3100000000',email:fixture.main,promotions:true,marketplaceNews:false,essentialCommunications:true});
  expect(await witness('main','main-persisted')).toMatchObject({updatedData:1,promotions:1,news:0,oldEmail:1,active:1});expect(await witness('control','main-control-unchanged')).toMatchObject({originalData:1,version:0,promotions:0,news:1});
  await evidence('main-essential',{essentialCommunications:true,optionalPreferencesPersisted:true,allFiveEssentialDeliveryCategoriesNotClaimed:true});await capture(page,'main-05-persistencia',profile(page).getByRole('heading',{name:'Mi perfil y preferencias'}));
 });
});
