import { test, expect } from '@playwright/test';

test('CU-06: registrar, editar, seleccionar dirección y eliminar con restricciones', async ({ page }) => {
  let nextId = 2;
  let rows: any[] = [{ id: 1, recipientName: 'Compradora Demo · Casa', street: 'Calle 10 # 20-30', city: 'Bogotá',
    department: 'Bogotá D.C.', postalCode: '110111', phone: '3001234567', defaultAddress: true }];
  const historicalStreet = 'Calle 10 # 20-30';
  const previews: any[] = [], creations: any[] = [];
  let blockedDeletes = 0;
  const product = { id: 2, name: 'Pantalón jean clásico', price: 95000, stock: 8, active: true, category: 'Ropa',
    description: 'Pantalón de mezclilla', imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'], variants: [] };
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname, method = req.method();
    const reply = (json: unknown, status = 200) => route.fulfill({ status, json });
    if (path === '/api/auth/me') return reply({ accountId: 2, email: 'compradora.demo@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (path === '/api/products') return reply([product]);
    if (path === '/api/products/2') return reply(product);
    if (path === '/api/cart') return reply({ cartId: 2, items: [{ id: 1, productId: 2, productName: product.name, quantity: 1, unitPrice: 95000, subtotal: 95000 }], total: 95000 });
    if (path === '/api/recommendations') return reply({ products: [], strategy: 'DEMO_CONTROLADA' });
    if (path === '/api/notifications') return reply({ items: [], hasMore: false });
    if (path === '/api/checkout/preview') {
      const input = req.postDataJSON();
      expect(rows.some(row => row.id === input.addressId)).toBe(true);
      previews.push(input);
      return reply({ subtotal: 95000, discount: 0, shippingCost: 10000, total: 105000, couponValid: false });
    }
    if (path === '/api/addresses') {
      if (method === 'GET') return reply(rows);
      expect(req.headers()['x-csrf-token']).toBe('demo-csrf');
      const data = req.postDataJSON(); creations.push(data);
      const row = { ...data, id: nextId++, defaultAddress: rows.length === 0 };
      rows.push(row); return reply(row, 201);
    }
    if (path.startsWith('/api/addresses/')) {
      expect(req.headers()['x-csrf-token']).toBe('demo-csrf');
      const id = Number(path.split('/')[3]), row = rows.find(row => row.id === id);
      if (!row) return reply({ message: 'Dirección no disponible' }, 404);
      if (path.endsWith('/default')) { rows.forEach(row => row.defaultAddress = row.id === id); return reply(row); }
      if (method === 'DELETE') {
        if (id === 1) { blockedDeletes++; return reply({ code: 'ADDRESS_IN_USE', message: 'No puedes eliminar esta dirección: tiene un pedido en proceso.' }, 409); }
        rows = rows.filter(row => row.id !== id);
        if (row.defaultAddress && rows.length) rows[0].defaultAddress = true;
        return route.fulfill({ status: 204 });
      }
      Object.assign(row, req.postDataJSON()); return reply(row);
    }
    return reply([]);
  });
  const stage = async (text: string) => {
    console.log(text);
    await page.evaluate(text => {
      document.querySelector('#cu06-demo-note')?.remove();
      const note = document.createElement('aside'); note.id = 'cu06-demo-note';
      note.textContent = `Demostración con datos controlados · ${text}`;
      Object.assign(note.style, { position: 'fixed', bottom: '16px', right: '16px', zIndex: '99999', maxWidth: '330px', padding: '14px',
        background: '#fff4df', color: '#183037', border: '2px solid #f56b4b', borderRadius: '12px', pointerEvents: 'none' });
      document.body.appendChild(note);
    }, text);
    await page.waitForTimeout(2400);
  };
  const openBook = async () => {
    await page.getByRole('button', { name: 'Opciones de mis compras', exact: true }).click();
    await page.getByRole('button', { name: 'Mis direcciones', exact: true }).click();
    return page.getByRole('dialog', { name: 'Mis direcciones', exact: true });
  };
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await stage('Compradora: administrar las direcciones de entrega');
  let book = await openBook();
  await expect(book).toContainText('Compradora Demo · Casa');
  await test.step('Registrar dirección con validación visible', async () => {
    await book.getByRole('button', { name: 'Nueva dirección', exact: true }).click();
    const form = page.getByRole('form', { name: 'Formulario de dirección' });
    for (const [label, value] of [['Destinatario', 'Compradora Demo · Oficina'], ['Dirección', 'Carrera 7 # 45-20'], ['Ciudad', 'Bogotá'],
      ['Departamento', 'Bogotá D.C.'], ['Código postal (opcional)', '110231'], ['Teléfono', '123']]) await form.getByLabel(label, { exact: true }).fill(value);
    await form.getByRole('button', { name: 'Guardar dirección', exact: true }).click();
    await expect(form.getByRole('alert')).toContainText('Ingresa un teléfono de 7 a 15 dígitos');
    await expect(form.getByLabel('Destinatario', { exact: true })).toHaveValue('Compradora Demo · Oficina');
    expect(creations).toHaveLength(0);
    await stage('Teléfono inválido: se indica el error y se conservan los demás datos');
    await form.getByLabel('Teléfono', { exact: true }).fill('3007654321');
    await form.getByRole('button', { name: 'Guardar dirección', exact: true }).click();
    await expect(book.locator('article')).toHaveCount(2);
    await stage('Dirección de oficina registrada junto a la dirección de casa');
  });
  await test.step('Editar y marcar una única predeterminada', async () => {
    await book.getByRole('button', { name: 'Editar dirección de Compradora Demo · Oficina', exact: true }).click();
    const form = page.getByRole('form', { name: 'Formulario de dirección' });
    await form.getByLabel('Dirección', { exact: true }).fill('Carrera 7 # 45-21 Oficina 302');
    await form.getByRole('button', { name: 'Guardar dirección', exact: true }).click();
    await book.getByRole('button', { name: 'Usar como predeterminada: Carrera 7 # 45-21 Oficina 302', exact: true }).click();
    await expect(book.locator('article').filter({ hasText: 'Compradora Demo · Oficina' })).toContainText('Predeterminada');
    await expect(book.locator('article strong')).toHaveCount(1);
    expect(rows.filter(row => row.defaultAddress)).toHaveLength(1);
    await stage('Oficina actualizada y predeterminada; solo una dirección tiene esa condición');
    await book.getByRole('button', { name: 'Cerrar direcciones', exact: true }).click();
  });
  await test.step('Seleccionar desde el desplegable del checkout', async () => {
    await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
    await expect(page.locator('.cart-panel')).toContainText(product.name);
    await stage('Carrito de demostración: comprar el pantalón con una dirección guardada');
    await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
    const addresses = page.getByRole('region', { name: 'Direcciones para la compra', exact: true });
    await expect(addresses.locator('.address-summary')).toContainText('Oficina');
    await expect(addresses.locator('article')).toHaveCount(0);
    await addresses.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true }).click();
    await addresses.getByRole('searchbox', { name: 'Buscar dirección' }).fill('casa');
    await expect(addresses.locator('article')).toHaveCount(1);
    await addresses.getByRole('radio', { name: 'Usar esta dirección: Calle 10 # 20-30', exact: true }).click();
    await expect(addresses.locator('.address-summary')).toContainText('Casa');
    await stage('El desplegable permite buscar y cambiar la dirección sin alargar el checkout');
    await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true })).toBeVisible();
    expect(previews).toHaveLength(1);
    expect(previews[0].addressId).toBe(1);
    expect(creations).toHaveLength(1);
    await stage('Total calculado con la dirección seleccionada; no se duplican direcciones ni se realiza pago');
    await page.getByRole('button', { name: 'Cerrar checkout', exact: true }).click();
  });
  await test.step('Restricción por pedido en proceso y eliminación permitida', async () => {
    book = await openBook();
    await book.getByRole('button', { name: 'Eliminar dirección de Compradora Demo · Casa', exact: true }).click();
    await book.getByRole('button', { name: 'Confirmar eliminación de dirección', exact: true }).click();
    await expect(book.getByRole('alert')).toContainText('pedido en proceso');
    await expect(book.locator('article')).toHaveCount(2);
    await stage('Escenario controlado: Casa no se elimina porque tiene un pedido en proceso');
    await book.getByRole('button', { name: 'Conservar dirección', exact: true }).click();
    await book.getByRole('button', { name: 'Eliminar dirección de Compradora Demo · Oficina', exact: true }).click();
    await stage('Oficina no tiene pedidos en proceso: se solicita confirmar su eliminación');
    await book.getByRole('button', { name: 'Confirmar eliminación de dirección', exact: true }).click();
    await expect(book.locator('article')).toHaveCount(1);
    await expect(book).not.toContainText('Oficina');
    await expect(book.locator('article')).toContainText('Predeterminada');
    expect(blockedDeletes).toBe(1);
    expect(rows[0].street).toBe(historicalStreet);
    await stage('Dirección permitida eliminada; Casa permanece y se mantiene una única predeterminada');
  });
});
