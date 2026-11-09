import { test, expect, Page } from '@playwright/test';

async function fixture(page: Page) {
  let saved = false;
  let alerts: any[] = [];
  const product = { id: 71, name: 'Libreta seguida', price: 10000, stock: 2, active: true, category: 'Hogar', images: [], variants: [{name:'Azul',price:10000,stock:0}] };
  await page.route(/^https:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, r=>r.abort());
  await page.route('**/api/**', async route => {
    const request=route.request(); const path=new URL(request.url()).pathname; const method=request.method();
    const reply=(body: any,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
    if (path==='/api/auth/me') return reply({accountId:2,email:'buyer@example.test',roles:['COMPRADOR'],activeRole:'COMPRADOR'});
    if (path==='/api/auth/csrf') return reply({headerName:'X-CSRF-TOKEN',token:'csrf'});
    if (path==='/api/products') return reply([product]);
    if (path==='/api/products/71') return reply(product);
    if (path==='/api/favorites/ids') return reply(saved?[71]:[]);
    if (path==='/api/favorites/71') { saved=method==='PUT';return reply(null); }
    if (path==='/api/favorites') return reply(saved?[product]:[]);
    if (path==='/api/favorites/alerts') {
      if (method==='POST') {
        const data=request.postDataJSON();expect(request.headers()['x-csrf-token']).toBe('csrf');
        if (data.kind==='PRICE' && data.targetPrice>=10000) return reply({alreadySatisfied:true,alert:null});
        alerts.push({...data,id:'alert-1',productName:product.name,status:'ACTIVE',revision:1});return reply({alreadySatisfied:false});
      }
      return reply(alerts);
    }
    if (path==='/api/favorites/alerts/alert-1') {
      if (method==='PUT') { alerts[0]={...alerts[0],...request.postDataJSON()}; return reply({alreadySatisfied:false}); }
      if (method==='DELETE') alerts=[];
      if (method==='PATCH') alerts[0].status=request.postDataJSON().status;
      return reply(null);
    }
    if (path==='/api/cart') return reply({items:[],total:0});
    if (path==='/api/recommendations') return reply({products:[],strategy:'test'});
    if (path==='/api/notifications') return reply({items:[],hasMore:false});
    return reply([]);
  });
  return product;
}
async function openFavorites(page: Page) {
  await page.getByRole('button',{name:'Opciones de mis compras',exact:true}).click();
  await page.getByRole('button',{name:'Mis favoritos y alertas',exact:true}).click();
}
test('shared product link opens the public detail while restoring the session',async({page})=>{
  await fixture(page);await page.goto('/?producto=71',{waitUntil:'domcontentloaded'});
  await expect(page.getByRole('dialog',{name:'Detalle del producto'})).toContainText('Libreta seguida');
});
test('favorites persist on reload and alerts can be paused and removed',async({page})=>{
  await fixture(page);await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();
  await expect(page.getByRole('button',{name:'Retirar de favoritos Libreta seguida',exact:true})).toHaveAttribute('aria-pressed','true');
  await page.reload({waitUntil:'domcontentloaded'});await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await expect(panel.getByRole('heading',{name:'Libreta seguida'})).toBeVisible();
  await panel.getByRole('button',{name:'Configurar alerta'}).click();
  await panel.getByLabel('Precio objetivo',{exact:true}).fill('8000');
  await panel.getByRole('button',{name:'Crear alerta',exact:true}).click();
  await expect(panel.getByText(/Precio objetivo.*Activa/)).toBeVisible();
  await panel.getByRole('button',{name:'Pausar',exact:true}).click();
  await expect(panel.getByText(/Precio objetivo.*Pausada/)).toBeVisible();
  await panel.getByRole('button',{name:'Reactivar',exact:true}).click();
  await expect(panel.getByText(/Precio objetivo.*Activa/)).toBeVisible();
  await panel.getByLabel('Nuevo precio',{exact:true}).fill('7000');
  await panel.getByRole('button',{name:'Modificar',exact:true}).click();
  await expect(panel.getByText(/Precio objetivo.*7[,.]000.*Activa/)).toBeVisible();
  await panel.getByRole('button',{name:'Eliminar alerta',exact:true}).click();
  await expect(panel.getByText('No tienes alertas registradas.')).toBeVisible();
  await panel.getByRole('button',{name:'Retirar de favoritos',exact:true}).click();
  await expect(panel.getByRole('heading',{name:'Libreta seguida',exact:true})).toHaveCount(0);
  await expect(panel.getByText(/Todavía no tienes favoritos/)).toBeVisible();
});

test('inactive saved publication disables purchase and alert actions',async({page})=>{
  const product=await fixture(page);await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();
  product.active=false;await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await expect(panel.getByText(/Publicación inactiva/)).toBeVisible();
  for(const name of ['Ver producto','Agregar al carrito','Comparar','Configurar alerta'])
    await expect(panel.getByRole('button',{name,exact:true})).toBeDisabled();
  await expect(panel.getByRole('button',{name:'Retirar de favoritos',exact:true})).toBeEnabled();
});

test('availability cannot be created when all variants have stock',async({page})=>{
  const product=await fixture(page);product.variants[0].stock=3;
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await panel.getByRole('button',{name:'Configurar alerta'}).click();
  await panel.getByLabel('Tipo',{exact:true}).selectOption('AVAILABILITY');
  await expect(panel.getByText('Este producto no tiene variantes agotadas.')).toBeVisible();
  await expect(panel.getByRole('button',{name:'Crear alerta',exact:true})).toBeDisabled();
});

test('failed alert creation preserves the form and can be retried',async({page})=>{
  await fixture(page);let fail=true;
  await page.route('**/api/favorites/alerts',async route=>{
    if(route.request().method()==='POST' && fail) {
      fail=false;return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({detail:'Servicio temporalmente no disponible'})});
    }
    return route.fallback();
  });
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await panel.getByRole('button',{name:'Configurar alerta'}).click();
  await panel.getByLabel('Precio objetivo',{exact:true}).fill('8000');
  await panel.getByRole('button',{name:'Crear alerta',exact:true}).click();
  await expect(panel.getByRole('alert')).toContainText('Servicio temporalmente no disponible');
  await expect(panel.getByLabel('Precio objetivo',{exact:true})).toHaveValue('8000');
  await panel.getByRole('button',{name:'Crear alerta',exact:true}).click();
  await expect(panel.getByText(/Precio objetivo.*Activa/)).toBeVisible();
});
test('already satisfied target informs the buyer without an active alert',async({page})=>{
  await fixture(page);await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await panel.getByRole('button',{name:'Configurar alerta'}).click();
  await panel.getByLabel('Precio objetivo',{exact:true}).fill('10000');
  await panel.getByRole('button',{name:'Crear alerta',exact:true}).click();
  await expect(panel.getByRole('status')).toContainText('ya cumple');
  await expect(panel.getByText('No tienes alertas registradas.')).toBeVisible();
});
test('availability alert selects an exhausted variant',async({page})=>{
  await fixture(page);await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Guardar Libreta seguida',exact:true}).click();await openFavorites(page);
  const panel=page.getByRole('dialog',{name:'Mis favoritos y alertas'});
  await panel.getByRole('button',{name:'Configurar alerta'}).click();
  await panel.getByLabel('Tipo',{exact:true}).selectOption('AVAILABILITY');
  await panel.getByLabel('Variante agotada',{exact:true}).selectOption('Azul');
  await panel.getByRole('button',{name:'Crear alerta',exact:true}).click();
  await expect(panel.getByText(/Disponibilidad.*Azul.*Activa/)).toBeVisible();
});
test('open inbox refreshes an alert and opens its product within five seconds',async({page})=>{
  await fixture(page);let published=false;
  await page.route('**/api/notifications?**',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({items:published?[{id:1,title:'Se cumplió tu alerta',message:'Libreta seguida',createdAt:new Date().toISOString(),orderId:null,productId:71}]:[],hasMore:false})}));
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'Abrir notificaciones',exact:true}).click();
  await expect(page.getByText('No hay notificaciones.',{exact:true})).toBeVisible();
  published=true;
  await expect(page.getByRole('heading',{name:'Se cumplió tu alerta',exact:true})).toBeVisible({timeout:5000});
  await page.getByRole('button',{name:'Ver producto',exact:true}).click();
  await expect(page.getByRole('button',{name:'Cerrar detalle',exact:false})).toBeVisible();
});
