import { test, expect, BrowserContext } from '@playwright/test';

async function login(context: BrowserContext, email: string) {
  const first=await (await context.request.get('/api/auth/csrf')).json();
  expect((await context.request.post('/api/auth/login',{form:{email,password:process.env['CU10_PASSWORD']!},headers:{[first.headerName]:first.token}})).status()).toBe(204);
}
async function write(context: BrowserContext,path:string,data:any) {
  const csrf=await (await context.request.get('/api/auth/csrf')).json();
  return context.request.post(path,{data,headers:{[csrf.headerName]:csrf.token}});
}

test('producto, respuesta, lectura, imágenes, archivo, cierre, reporte y persistencia con sesiones reales',async({browser})=>{
  const buyer=await browser.newContext({baseURL:'http://127.0.0.1:14310'});
  const seller=await browser.newContext({baseURL:'http://127.0.0.1:14310'});
  await login(buyer,process.env['CU10_BUYER']!);await login(seller,process.env['CU10_SELLER']!);
  const page=await buyer.newPage();await page.goto('/');
  // Product contact goes through the actual catalog detail.
  await page.getByRole('button',{name:process.env['CU10_PRODUCT_NAME']!,exact:true}).click();
  await page.locator('app-contactar-vendedor').getByRole('button',{name:'Contactar al vendedor'}).click();
  const chat=page.locator('app-conversaciones');await expect(chat.locator('main')).toBeVisible();
  await chat.getByLabel('Mensaje',{exact:true}).fill('CU10 fallo persistencia');
  await chat.getByRole('button',{name:'Enviar mensaje',exact:true}).click();
  await expect(chat.getByRole('alert')).toBeVisible();
  await expect(chat.getByLabel('Mensaje',{exact:true})).toHaveValue('CU10 fallo persistencia');
  await expect(chat.locator('article')).toHaveCount(0);
  await chat.getByLabel('Mensaje',{exact:true}).fill('CU10 pregunta <script>alert(1)</script>');
  await chat.getByRole('button',{name:'Enviar mensaje',exact:true}).click();
  await expect(chat.locator('article')).toContainText('CU10 pregunta <script>alert(1)</script>');
  expect(await chat.locator('script').count()).toBe(0);
  const threads=await (await buyer.request.get('/api/conversations')).json();const id=threads.find((x:any)=>x.productId===Number(process.env['CU10_PRODUCT'])).id;
  expect((await (await seller.request.get(`/api/conversations/${id}`)).json()).unread).toBe(1);
  const sellerPage=await seller.newPage();await sellerPage.goto('/');
  await sellerPage.getByRole('button',{name:'Opciones de mi negocio'}).click();await sellerPage.getByRole('button',{name:'Conversaciones',exact:true}).click();
  const sellerChat=sellerPage.locator('app-conversaciones');await sellerChat.locator('nav button').filter({hasText:process.env['CU10_PRODUCT_NAME']}).click();
  await expect(sellerChat.locator('article')).toContainText('CU10 pregunta');
  await expect.poll(async()=>(await (await seller.request.get(`/api/conversations/${id}`)).json()).unread).toBe(0);
  await sellerChat.getByLabel('Mensaje',{exact:true}).fill('CU10 respuesta');await sellerChat.getByRole('button',{name:'Enviar mensaje',exact:true}).click();
  await expect(chat.locator('article').last()).toContainText('CU10 respuesta');
  // Invalid attachment leaves the valid text draft untouched.
  await chat.getByLabel('Mensaje',{exact:true}).fill('CU10 borrador conservado');
  await chat.locator('input[type=file]').setInputFiles({name:'virus.png',mimeType:'image/png',buffer:Buffer.from('<script>malicious</script>')});
  await chat.getByRole('button',{name:'Enviar mensaje',exact:true}).click();await expect(chat.getByRole('alert')).toBeVisible();
  await expect(chat.getByLabel('Mensaje',{exact:true})).toHaveValue('CU10 borrador conservado');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
  await chat.locator('input[type=file]').setInputFiles({name:'cu10.png',mimeType:'image/png',buffer:png});
  await chat.getByRole('button',{name:'Enviar mensaje',exact:true}).click();await expect(chat.locator('article img')).toBeVisible();
  await chat.getByRole('button',{name:'Cerrar conversación',exact:true}).click();await expect(chat.getByRole('button',{name:'Enviar mensaje',exact:true})).toBeDisabled();
  await sellerChat.getByRole('button',{name:'Reabrir conversación',exact:true}).click();await expect(chat.getByRole('button',{name:'Cerrar conversación',exact:true})).toBeVisible();
  await chat.getByRole('button',{name:'Archivar',exact:true}).click();await expect(chat.locator('nav')).not.toContainText(process.env['CU10_PRODUCT_NAME']!);
  await chat.getByLabel('Archivadas').check();await expect(chat.locator('nav')).toContainText(process.env['CU10_PRODUCT_NAME']!);
  await chat.getByRole('button',{name:'Restaurar',exact:true}).click();await chat.getByLabel('Archivadas').uncheck();
  const report=chat.locator('article').filter({hasText:'CU10 respuesta'}).locator('app-reportar-contenido');
  await report.getByRole('button',{name:'Reportar mensaje',exact:true}).click();
  await report.locator('select').selectOption('CONTENIDO_INAPROPIADO');await report.locator('textarea').fill('CU10 evidencia de comportamiento');
  await report.getByRole('button',{name:'Enviar reporte',exact:true}).click();await expect(report).toContainText('Reporte radicado');
  await page.reload();await page.getByRole('button',{name:'Opciones de mis compras'}).click();await page.getByRole('button',{name:'Conversaciones',exact:true}).click();
  await page.locator('app-conversaciones nav button').filter({hasText:process.env['CU10_PRODUCT_NAME']}).click();
  await expect(page.locator('app-conversaciones article')).toHaveCount(3);
  const notices=await (await buyer.request.get('/api/notifications')).json();expect(notices.items.some((n:any)=>n.conversationId===id)).toBe(true);
  await page.locator('app-conversaciones').getByRole('button',{name:'Salir de mensajería',exact:true}).click();
  await page.getByRole('button',{name:'Abrir notificaciones',exact:true}).click();
  await page.locator('app-notificaciones').getByRole('button',{name:'Abrir conversación',exact:true}).first().click();
  await expect(page.locator('app-conversaciones main')).toContainText(process.env['CU10_PRODUCT_NAME']!);
  await buyer.close();await seller.close();
});

test('pedido con producto seleccionado y aislamiento de IDs',async({browser})=>{
  const context=await browser.newContext({baseURL:'http://127.0.0.1:14310'});await login(context,process.env['CU10_BUYER']!);
  const page=await context.newPage();await page.goto('/');
  // Verify both order lines have their own contact action through the real CU11 panel.
  await page.getByRole('button',{name:'Mis pedidos',exact:true}).click();
  const orders=page.locator('app-mis-pedidos');
  await orders.getByRole('button',{name:new RegExp(`Ver detalle.*${process.env['CU10_ORDER']}`)}).click();
  await expect(orders.locator('app-contactar-vendedor')).toHaveCount(2);
  await orders.locator('app-contactar-vendedor').last().getByRole('button').click();
  await expect(page.locator('app-conversaciones main')).toContainText(`Pedido #${process.env['CU10_ORDER']}`);
  const threads=await (await context.request.get('/api/conversations')).json();
  expect(threads.some((t:any)=>t.orderId===Number(process.env['CU10_ORDER'])&&t.productId===Number(process.env['CU10_OTHER_PRODUCT']))).toBe(true);
  expect((await write(context,'/api/conversations',{productId:process.env['CU10_PRODUCT'],orderId:999999999})).status()).toBe(404);
  await context.close();
});
