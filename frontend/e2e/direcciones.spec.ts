import { test,expect,Page } from '@playwright/test';

async function fixture(page:Page,empty=false,many=0){
 let account=1,failSave=false,blocked=false,nextId=4;
 let rows:any[]=empty?[]:[
  {id:1,owner:1,recipientName:'Ana Casa',street:'Calle 1',city:'Bogotá',department:'Cundinamarca',postalCode:'110111',phone:'3001234567',defaultAddress:true},
  {id:2,owner:1,recipientName:'Ana Oficina',street:'Carrera 2',city:'Bogotá',department:'Cundinamarca',postalCode:'',phone:'3001234567',defaultAddress:false},
  {id:3,owner:2,recipientName:'Bruno',street:'Calle privada B',city:'Cali',department:'Valle',postalCode:'',phone:'3001234567',defaultAddress:true}];
 for(let i=0;i<many;i++)rows.push({id:100+i,owner:1,recipientName:`Entrega ${i}`,street:`Calle adicional ${i}`,city:'Bogotá',department:'Cundinamarca',phone:'3001234567',defaultAddress:false});
 const previews:any[]=[],creates:any[]=[];
 await page.route('https://**',r=>r.abort());
 await page.route('**/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname,method=request.method();
  const reply=(data:unknown,status=200)=>route.fulfill({status,json:data});
  if(path==='/api/auth/me')return reply({accountId:account,email:`buyer${account}@example.test`,roles:['COMPRADOR'],activeRole:'COMPRADOR'});
  if(path==='/api/auth/csrf')return reply({headerName:'X-CSRF-TOKEN',token:'csrf'});
  if(path==='/api/cart')return reply({cartId:account,items:[{id:1,productId:2,productName:'Jean',quantity:1,unitPrice:95000,subtotal:95000}],total:95000});
  if(path==='/api/recommendations')return reply({userId:account,strategy:'test',products:[]});
  if(path==='/api/checkout/preview'){previews.push(request.postDataJSON());return reply({subtotal:95000,discount:0,shippingCost:10000,total:105000,couponValid:false});}
  if(path==='/api/addresses'){
   if(method==='GET')return reply(rows.filter(r=>r.owner===account).map(({owner,...row})=>row));
   expect(request.headers()['x-csrf-token']).toBe('csrf');
   if(failSave){failSave=false;return reply({},503);}
   const data=request.postDataJSON();creates.push(data);const row={...data,id:nextId++,owner:account,defaultAddress:!rows.some(r=>r.owner===account)};rows.push(row);return reply(row,201);
  }
  if(path.startsWith('/api/addresses/')){
   expect(request.headers()['x-csrf-token']).toBe('csrf');
   const id=Number(path.split('/')[3]),row=rows.find(r=>r.id===id&&r.owner===account);
   if(!row)return reply({message:'Dirección no disponible'},404);
   if(path.endsWith('/default')){rows.filter(r=>r.owner===account).forEach(r=>r.defaultAddress=r.id===id);return reply(row);}
   if(method==='DELETE'){
    if(blocked)return reply({code:'ADDRESS_IN_USE',message:'No puedes eliminar esta dirección: tiene un pedido en proceso.'},409);
    rows=rows.filter(r=>r.id!==id);if(row.defaultAddress){const first=rows.find(r=>r.owner===account);if(first)first.defaultAddress=true;}
    return route.fulfill({status:204});
   }
   if(failSave){failSave=false;return reply({message:'street: Revisa la dirección ingresada'},400);}
   Object.assign(row,request.postDataJSON());return reply(row);
  }
  return reply([]);
 });
 await page.goto('/',{waitUntil:'domcontentloaded'});
 return {creates,previews,failNext:()=>failSave=true,block:()=>blocked=true,otherBuyer:()=>account=2};
}
async function book(page:Page){await page.getByRole('button',{name:'Opciones de mis compras',exact:true}).click();await page.getByRole('button',{name:'Mis direcciones',exact:true}).click();return page.getByRole('dialog',{name:'Mis direcciones',exact:true});}
async function fill(page:Page){const form=page.getByRole('form',{name:'Formulario de dirección'});for(const [label,value] of [['Destinatario','Ana nueva'],['Dirección','Calle 10 # 3-4'],['Ciudad','Medellín'],['Departamento','Antioquia'],['Teléfono','+57 300 123 4567']])await form.getByLabel(label,{exact:true}).fill(value);return form;}

for(const width of [1280,390])test(`selector compacto busca entre muchas direcciones sin alargar checkout (${width}px)`,async({page})=>{
 await page.setViewportSize({width,height:844});
 const state=await fixture(page,false,40);
 await page.getByRole('button',{name:'Abrir carrito',exact:true}).click();
 await page.getByRole('button',{name:'Continuar compra',exact:true}).click();
 const panel=page.getByRole('region',{name:'Direcciones para la compra',exact:true});
 const toggle=panel.getByRole('button',{name:'Elegir o cambiar dirección',exact:true});
 await expect(toggle).toHaveAttribute('aria-expanded','false');
 await expect(panel.locator('article')).toHaveCount(0);
 await expect(panel.locator('.address-summary')).toContainText('Calle 1');
 expect((await panel.boundingBox())!.height).toBeLessThan(350);
 await toggle.click();
 const search=panel.getByRole('searchbox',{name:'Buscar dirección'});
 await search.fill('ninguna coincidencia');
 await expect(panel).toContainText('No hay direcciones que coincidan');
 await search.fill('carrera 2');
 await expect(panel.locator('article')).toHaveCount(1);
 await panel.getByRole('radio',{name:'Usar esta dirección: Carrera 2',exact:true}).click();
 await expect(panel.locator('article')).toHaveCount(0);
 await expect(panel.locator('.address-summary')).toContainText('Carrera 2');
 await expect(panel.getByRole('button',{name:'Elegir o cambiar dirección',exact:true})).toBeFocused();
 await page.getByRole('button',{name:'Calcular total',exact:true}).click();
 await expect.poll(()=>state.previews.length).toBe(1);
 expect(state.previews[0].addressId).toBe(2);
 expect(state.creates).toHaveLength(0);
});

test('registra datos reales, persiste al recargar y hace predeterminada la primera',async({page})=>{
 const state=await fixture(page,true);let panel=await book(page);await expect(panel).toContainText('No tienes direcciones guardadas');await panel.getByRole('button',{name:'Nueva dirección',exact:true}).click();const form=await fill(page);await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(panel).toContainText('Predeterminada');expect(state.creates[0]).toMatchObject({recipientName:'Ana nueva',city:'Medellín',department:'Antioquia',phone:'+57 300 123 4567'});
 await page.reload();panel=await book(page);await expect(panel).toContainText('Calle 10 # 3-4');
});
test('campos inválidos muestran errores y conservan los datos válidos',async({page})=>{
 const state=await fixture(page,true),panel=await book(page);await panel.getByRole('button',{name:'Nueva dirección',exact:true}).click();const form=await fill(page);await form.getByLabel('Teléfono',{exact:true}).fill('123');await form.getByLabel('Ciudad',{exact:true}).fill('');await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(form.getByRole('alert')).toHaveCount(2);await expect(form.getByLabel('Destinatario')).toHaveValue('Ana nueva');expect(state.creates).toHaveLength(0);
});
test('error al guardar permite reintentar sin perder los campos',async({page})=>{
 const state=await fixture(page,true),panel=await book(page);state.failNext();await panel.getByRole('button',{name:'Nueva dirección',exact:true}).click();const form=await fill(page);await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('puedes reintentar');await expect(form.getByLabel('Ciudad')).toHaveValue('Medellín');await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(panel).toContainText('Dirección guardada.');expect(state.creates).toHaveLength(1);
});
test('edita una dirección y mantiene una única predeterminada',async({page})=>{
 await fixture(page);const panel=await book(page);await panel.getByRole('button',{name:'Editar dirección de Ana Oficina',exact:true}).click();const form=page.getByRole('form',{name:'Formulario de dirección'});await form.getByLabel('Dirección',{exact:true}).fill('Avenida actualizada');await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(panel).toContainText('Avenida actualizada');await panel.getByRole('button',{name:'Usar como predeterminada: Avenida actualizada',exact:true}).click();await expect(panel.locator('article').filter({hasText:'Ana Oficina'})).toContainText('Predeterminada');await expect(panel.locator('strong').filter({hasText:'Predeterminada'})).toHaveCount(1);
});
test('confirmación de eliminación y restricción de pedido en curso',async({page})=>{
 const state=await fixture(page),panel=await book(page);state.block();await panel.getByRole('button',{name:'Eliminar dirección de Ana Casa',exact:true}).click();await panel.getByRole('button',{name:'Conservar dirección',exact:true}).click();await expect(panel.locator('article')).toHaveCount(2);await panel.getByRole('button',{name:'Eliminar dirección de Ana Casa',exact:true}).click();await panel.getByRole('button',{name:'Confirmar eliminación de dirección',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('pedido en proceso');await expect(panel.locator('article')).toHaveCount(2);
});
test('elimina una dirección permitida después de confirmar',async({page})=>{
 await fixture(page);const panel=await book(page);await panel.getByRole('button',{name:'Eliminar dirección de Ana Casa',exact:true}).click();await panel.getByRole('button',{name:'Confirmar eliminación de dirección',exact:true}).click();await expect(panel.locator('article')).toHaveCount(1);await expect(panel).toContainText('Ana Oficina');await expect(panel).toContainText('Predeterminada');
});
test('checkout selecciona predeterminada, permite cambiarla y no crea duplicados al calcular',async({page})=>{
 const state=await fixture(page);await page.getByRole('button',{name:'Abrir carrito',exact:true}).click();await page.getByRole('button',{name:'Continuar compra',exact:true}).click();await page.getByRole('button',{name:'Elegir o cambiar dirección',exact:true}).click();await expect(page.getByRole('radio',{name:'Usar esta dirección: Calle 1',exact:true})).toBeChecked();await page.getByRole('radio',{name:'Usar esta dirección: Carrera 2',exact:true}).click();
 for(let i=0;i<2;i++){await page.getByRole('button',{name:'Calcular total',exact:true}).click();await expect(page.getByRole('button',{name:'Continuar a Mercado Pago',exact:true})).toBeVisible();}
 expect(state.creates).toHaveLength(0);expect(state.previews).toHaveLength(2);expect(state.previews.every(r=>r.addressId===2)).toBeTruthy();
});
test('otro comprador solo recibe su propio libro de direcciones',async({page})=>{
 const state=await fixture(page);await book(page);await expect(page.getByRole('dialog',{name:'Mis direcciones',exact:true})).toContainText('Ana Casa');state.otherBuyer();await page.reload();const panel=await book(page);await expect(panel).toContainText('Bruno');await expect(panel).not.toContainText('Ana Casa');await expect(panel).not.toContainText('Ana Oficina');
});

test('permite registrar una dirección completa dentro del checkout',async({page})=>{
 const state=await fixture(page,true);await page.getByRole('button',{name:'Abrir carrito',exact:true}).click();await page.getByRole('button',{name:'Continuar compra',exact:true}).click();const section=page.getByRole('region',{name:'Direcciones para la compra',exact:true});await section.getByRole('button',{name:'Nueva dirección',exact:true}).click();const form=await fill(page);await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(section.locator('.address-summary')).toContainText('Calle 10 # 3-4');await expect(section.getByRole('button',{name:'Elegir o cambiar dirección',exact:true})).toHaveAttribute('aria-expanded','false');await page.getByRole('button',{name:'Calcular total',exact:true}).click();await expect.poll(()=>state.previews.length).toBe(1);expect(state.previews[0].addressId).toBe(4);expect(state.creates[0].recipientName).toBe('Ana nueva');
});

test('error de validación del servidor se muestra junto al campo sin perder los demás',async({page})=>{
 const state=await fixture(page),panel=await book(page);await panel.getByRole('button',{name:'Editar dirección de Ana Casa',exact:true}).click();const form=page.getByRole('form',{name:'Formulario de dirección'});await form.getByLabel('Ciudad',{exact:true}).fill('Medellín');state.failNext();await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(form.getByRole('alert')).toContainText('Revisa la dirección ingresada');await expect(form.getByLabel('Ciudad',{exact:true})).toHaveValue('Medellín');await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(panel).toContainText('Dirección guardada.');
});

test('descarta el total que llega tarde si la dirección seleccionada fue editada',async({page})=>{
 await fixture(page);await page.getByRole('button',{name:'Abrir carrito',exact:true}).click();await page.getByRole('button',{name:'Continuar compra',exact:true}).click();await page.getByRole('button',{name:'Elegir o cambiar dirección',exact:true}).click();await expect(page.getByRole('radio',{name:'Usar esta dirección: Calle 1',exact:true})).toBeChecked();
 let release!:()=>void;const pending=new Promise<void>(resolve=>release=resolve);
 await page.route('**/api/checkout/preview',async route=>{await pending;await route.fulfill({json:{subtotal:95000,discount:0,shippingCost:10000,total:105000,couponValid:false}});});
 const started=page.waitForRequest(r=>new URL(r.url()).pathname==='/api/checkout/preview');await page.getByRole('button',{name:'Calcular total',exact:true}).click();await started;
 await page.getByRole('button',{name:'Editar dirección de Ana Casa',exact:true}).click();const form=page.getByRole('form',{name:'Formulario de dirección'});await form.getByLabel('Dirección',{exact:true}).fill('Dirección modificada');await form.getByRole('button',{name:'Guardar dirección',exact:true}).click();await expect(page.locator('.address-summary')).toContainText('Dirección modificada');
 const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/checkout/preview');release();await response;
 await expect(page.getByRole('button',{name:'Continuar a Mercado Pago',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Calcular total',exact:true})).toBeEnabled();
});
