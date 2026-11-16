import { test, expect } from '@playwright/test';

test('CU-04: favoritos, administración de alertas y avisos en un recorrido continuo', async ({ page }) => {
  const products = [
    { id: 1, name: 'Pantalón jean clásico', description: 'Pantalón de mezclilla de corte recto', category: 'Ropa', price: 95000, stock: 8, active: true,
      imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'], variants: [{ name: 'Talla M', price: 95000, stock: 8 }] },
    { id: 2, name: 'Chaqueta ligera', description: 'Chaqueta para clima fresco', category: 'Ropa', price: 145000, stock: 5, active: true,
      imageUrls: ['/assets/catalogo-demo/photo-1551028719-00167b16eac5.jpg'], variants: [{ name: 'Talla M', price: 145000, stock: 0 }, { name: 'Talla S', price: 145000, stock: 5 }] },
  ];
  const saved = new Set<number>();
  const alerts: any[] = [];
  const notices: any[] = [];
  const mutations: string[] = [];
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname, method = req.method();
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'comprador.demo@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (path === '/api/products') return reply(products);
    if (/^\/api\/products\/\d+$/.test(path)) return reply(products.find(p => p.id === Number(path.split('/').pop())));
    if (path === '/api/favorites/ids') return reply([...saved]);
    if (path === '/api/favorites') return reply(products.filter(p => saved.has(p.id)).map(p => ({ ...p, images: p.imageUrls })));
    if (/^\/api\/favorites\/\d+$/.test(path)) {
      const id = Number(path.split('/').pop());
      if (method === 'PUT') saved.add(id); else if (method === 'DELETE') saved.delete(id);
      return reply(null);
    }
    if (path === '/api/favorites/alerts') {
      if (method === 'POST') {
        const data = req.postDataJSON();
        expect(req.headers()['x-csrf-token']).toBe('demo-csrf');
        const product = products.find(p => p.id === data.productId)!;
        const alreadySatisfied = data.kind === 'PRICE' && data.targetPrice >= product.price;
        if (!alreadySatisfied) alerts.push({ ...data, id: `demo-${alerts.length + 1}`, productName: product.name, status: 'ACTIVE', revision: 1 });
        return reply({ alreadySatisfied });
      }
      return reply(alerts.filter(a => a.status !== 'DELETED'));
    }
    if (path.startsWith('/api/favorites/alerts/')) {
      const alert = alerts.find(a => a.id === path.split('/').pop());
      expect(alert).toBeTruthy();
      mutations.push(method);
      if (method === 'PATCH') alert.status = req.postDataJSON().status;
      if (method === 'PUT') Object.assign(alert, req.postDataJSON());
      if (method === 'DELETE') alert.status = 'DELETED';
      return reply({ alreadySatisfied: false });
    }
    if (path === '/api/notifications') return reply({ items: notices, hasMore: false });
    if (path === '/api/recommendations') return reply({ products: [], strategy: 'DEMO_CONTROLADA' });
    if (path === '/api/cart') return reply({ items: [], total: 0 });
    return reply([]);
  });
  const stage = async (text: string) => {
    console.log(text);
    await page.evaluate(text => {
      document.querySelector('#cu04-demo-note')?.remove();
      const note = document.createElement('aside'); note.id = 'cu04-demo-note';
      note.textContent = `Demostración con datos controlados · ${text}`;
      Object.assign(note.style, { position: 'fixed', bottom: '16px', right: '16px', zIndex: '99999', maxWidth: '330px', padding: '14px', background: '#fff4df', color: '#183037', border: '2px solid #f56b4b', borderRadius: '12px', pointerEvents: 'none' });
      document.body.appendChild(note);
    }, text);
    await page.waitForTimeout(2200);
  };
  const openFavorites = async () => {
    await page.getByRole('button', { name: 'Opciones de mis compras', exact: true }).click();
    await page.getByRole('button', { name: 'Mis favoritos y alertas', exact: true }).click();
  };
  const panel = page.getByRole('dialog', { name: 'Mis favoritos y alertas' });
  const article = (name: string, alert = false) => panel.locator('article').filter({ has: page.getByRole('heading', { name, exact: true }) })
    .filter({ has: page.getByRole('button', { name: alert ? 'Eliminar alerta' : 'Configurar alerta', exact: true }) });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await test.step('Explorar y guardar dos prendas en favoritos', async () => {
    for (const product of products) {
      const card = page.locator('#catalogo .product-card').filter({ has: page.getByRole('heading', { name: product.name, exact: true }) });
      await card.scrollIntoViewIfNeeded();
      await expect.poll(() => card.locator('img').first().evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
      await stage(`Guardar ${product.name}`);
      await card.getByRole('button', { name: `Guardar ${product.name}`, exact: true }).click();
      await expect(card.getByRole('button', { name: `Retirar de favoritos ${product.name}`, exact: true })).toHaveAttribute('aria-pressed', 'true');
    }
    await openFavorites();
    await panel.getByLabel('Ordenar favoritos').selectOption('precio');
    await expect(panel.locator('article')).toHaveCount(2);
    await stage('Los dos productos están guardados y ordenados por precio');
  });
  await test.step('Crear, pausar, modificar y reactivar una alerta de precio', async () => {
    await article(products[0].name).getByRole('button', { name: 'Configurar alerta' }).click();
    await panel.getByLabel('Precio objetivo', { exact: true }).fill('90000');
    await stage('Avisar cuando el pantalón baje de $95.000 a $90.000');
    await panel.getByRole('button', { name: 'Crear alerta', exact: true }).click();
    const price = article(products[0].name, true);
    await expect(price).toContainText('Activa');
    await price.getByRole('button', { name: 'Pausar', exact: true }).click();
    await expect(price).toContainText('Pausada');
    await stage('Alerta pausada: no debe generar avisos');
    await price.getByLabel('Nuevo precio', { exact: true }).fill('85000');
    await price.getByRole('button', { name: 'Modificar', exact: true }).click();
    await price.getByRole('button', { name: 'Reactivar', exact: true }).click();
    await expect(price).toContainText('Activa');
    await stage('Alerta reactivada con nuevo objetivo de $85.000');
  });
  await test.step('Seguir una variante agotada', async () => {
    await article(products[1].name).getByRole('button', { name: 'Configurar alerta' }).click();
    await panel.getByLabel('Tipo', { exact: true }).selectOption('AVAILABILITY');
    await panel.getByLabel('Variante agotada', { exact: true }).selectOption('Talla M');
    await stage('Avisar cuando vuelva la chaqueta Talla M, actualmente agotada');
    await panel.getByRole('button', { name: 'Crear alerta', exact: true }).click();
    await expect(article(products[1].name, true)).toContainText(/Disponibilidad.*Talla M.*Activa/);
  });
  await test.step('Mostrar cumplimiento de ambas alertas con escenario controlado', async () => {
    await stage('Escenario controlado: el precio baja a $80.000 y se repone la Talla M');
    products[0].price = 80000;
    products[1].variants[0].stock = 3;
    for (const alert of alerts) {
      alert.status = 'TRIGGERED';
      notices.push({ id: notices.length + 1, title: alert.kind === 'PRICE' ? 'Se cumplió tu alerta de precio' : 'Tu variante vuelve a estar disponible',
        message: alert.productName, productId: alert.productId, orderId: null, createdAt: new Date().toISOString() });
    }
    await panel.getByRole('button', { name: 'Actualizar favoritos y alertas', exact: true }).click();
    await expect(article(products[0].name)).toContainText(/80[,.]000/);
    await expect(article(products[0].name, true)).toContainText('Cumplida');
    await expect(article(products[1].name, true)).toContainText('Cumplida');
    await panel.getByRole('button', { name: 'Cerrar favoritos', exact: true }).click();
    await page.getByRole('button', { name: 'Abrir notificaciones', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Se cumplió tu alerta de precio', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Tu variante vuelve a estar disponible', exact: true })).toBeVisible();
    await stage('Dos notificaciones internas; abrir el producto desde el aviso');
    await page.getByRole('button', { name: 'Ver producto', exact: true }).first().click();
    const detail = page.getByRole('dialog', { name: 'Detalle del producto' });
    await expect(detail).toContainText(products[0].name);
    await stage('El aviso lleva al producto con su precio actualizado');
    await detail.getByRole('button', { name: 'Cerrar detalle', exact: true }).click();
  });
  await test.step('Eliminar alertas y retirar productos guardados', async () => {
    await openFavorites();
    for (const product of products) {
      await article(product.name, true).getByRole('button', { name: 'Eliminar alerta', exact: true }).click();
      await article(product.name).getByRole('button', { name: 'Retirar de favoritos', exact: true }).click();
    }
    await expect(panel.getByText('No tienes alertas registradas.')).toBeVisible();
    await expect(panel.getByText(/Todavía no tienes favoritos/)).toBeVisible();
    expect(saved.size).toBe(0);
    expect(alerts.every(a => a.status === 'DELETED')).toBe(true);
    expect(mutations).toEqual(expect.arrayContaining(['PATCH', 'PUT', 'DELETE']));
    await stage('Recorrido completo: favoritos y alertas eliminados correctamente');
  });
});
