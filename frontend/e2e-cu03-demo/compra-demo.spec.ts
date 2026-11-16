import { test, expect } from '@playwright/test';

test('CU-03: del catálogo al pago aprobado en un recorrido continuo controlado', async ({ page, baseURL }) => {
  const checkout = '33333333-3333-4333-8333-333333333333';
  const productos = [
    { id: 1, name: 'Pantalón jean clásico', description: 'Pantalón de mezclilla', category: 'Ropa', price: 95000, stock: 18, active: true,
      imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'] },
    { id: 2, name: 'Chaqueta ligera', description: 'Chaqueta para clima fresco', category: 'Ropa', price: 145000, stock: 10, active: true,
      imageUrls: ['/assets/catalogo-demo/photo-1551028719-00167b16eac5.jpg'] },
  ];
  const cart = new Map<number, number>();
  const requests: unknown[] = [];
  let approved = false;
  const subtotal = () => [...cart].reduce((total, [id, quantity]) => total + productos.find(p => p.id === id)!.price * quantity, 0);
  const total = 260000;
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    const reply = (json: unknown) => route.fulfill({ json });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'comprador.demo@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (path === '/api/products') return reply(productos);
    if (path.startsWith('/api/products/')) return reply(productos.find(p => p.id === Number(path.split('/').pop())));
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'DEMO_CONTROLADA', products: [] });
    if (path === '/api/notifications') return reply({ items: [], hasMore: false });
    if (path === '/api/cart/items' && req.method() === 'POST') {
      const input = req.postDataJSON();
      expect(productos.some(p => p.id === input.productId)).toBe(true);
      cart.set(input.productId, (cart.get(input.productId) ?? 0) + input.quantity);
      return reply({});
    }
    if (path === '/api/cart') return reply({ cartId: 1, items: [...cart].map(([id, quantity]) => {
      const p = productos.find(p => p.id === id)!;
      return { id, productId: id, productName: p.name, quantity, unitPrice: p.price, subtotal: p.price * quantity };
    }), total: subtotal() });
    if (path === '/api/addresses') return reply([
      { id: 8, recipientName: 'Comprador Demo', street: 'Calle Demo 45 # 12-30', city: 'Bogotá', department: 'Bogotá D.C.', phone: '3001234567', defaultAddress: true },
      { id: 9, recipientName: 'Oficina Demo', street: 'Carrera Demo 7 # 4-21', city: 'Bogotá', department: 'Bogotá D.C.', phone: '3007654321', defaultAddress: false },
    ]);
    if (path === '/api/checkout/preview') {
      expect(req.postDataJSON()).toMatchObject({ addressId: 9, shippingMethod: 'EXPRESS' });
      expect(subtotal()).toBe(240000);
      return reply({ subtotal: subtotal(), discount: 0, shippingCost: 20000, total, couponValid: false });
    }
    if (path === '/api/payments/mercadopago/checkouts') {
      expect(req.headers()['x-csrf-token']).toBe('demo-csrf');
      expect(req.postDataJSON()).toMatchObject({ addressId: 9, shippingMethod: 'EXPRESS' });
      requests.push(req.postDataJSON());
      return reply({ id: checkout, status: 'PENDING', orderId: null, total,
        checkoutUrl: 'https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=demo-controlada' });
    }
    if (path.endsWith('/refresh')) {
      expect(req.postDataJSON()).toEqual({ paymentId: '456' });
      approved = true;
      cart.clear();
    }
    if (path.includes('/mercadopago/checkouts/')) return reply({ id: checkout, status: approved ? 'APPROVED' : 'PENDING', orderId: approved ? 99 : null, total });
    return reply([]);
  });
  await page.route('https://sandbox.mercadopago.com.co/**', route => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: `<html lang="es"><meta charset="utf-8"><title>Demostración controlada de pago</title><main style="font:22px sans-serif;max-width:720px;margin:80px auto;padding:30px;border:2px solid #f56b4b;border-radius:20px"><h1>Demostración de pago</h1><p>Esta pantalla es un sustituto de prueba. No es el checkout real de Mercado Pago y no realiza cobros.</p><p>Pantalón y chaqueta: $240.000<br>Envío Express: $20.000<br>Total: $260.000 COP</p><a style="display:inline-block;padding:20px;background:#183037;color:white;border-radius:12px" href="${baseURL}/?checkout=${checkout}&payment_id=456">Completar demostración y volver a la tienda</a></main></html>`,
  }));
  await page.goto('/');
  const catalogo = page.locator('#catalogo');
  await test.step('Agregar pantalón y chaqueta al carrito', async () => {
    await expect(catalogo.locator('.product-card')).toHaveCount(2);
    await catalogo.scrollIntoViewIfNeeded();
    for (const name of productos.map(p => p.name)) {
      await catalogo.locator('.product-card').filter({ hasText: name }).getByRole('button', { name: /Agregar/ }).click();
      await expect.poll(() => cart.size).toBe(productos.findIndex(p => p.name === name) + 1);
    }
    await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Continuar compra', exact: true })).toBeVisible();
    await page.waitForTimeout(2500);
    await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  });
  await test.step('Seleccionar dirección y envío Express y calcular el total', async () => {
    await page.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true }).click();
    await page.getByRole('searchbox', { name: 'Buscar dirección' }).fill('oficina');
    await page.getByRole('radio', { name: 'Usar esta dirección: Carrera Demo 7 # 4-21', exact: true }).click();
    await page.getByRole('radio', { name: /Express/ }).check();
    await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true })).toBeVisible();
    await page.waitForTimeout(3000);
  });
  await test.step('Continuar al sustituto de pago y regresar con estado consultado', async () => {
    await page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Demostración de pago', exact: true })).toBeVisible();
    expect(requests).toHaveLength(1);
    await page.waitForTimeout(3000);
    await page.getByRole('link', { name: 'Completar demostración y volver a la tienda' }).click();
    const status = page.getByRole('region', { name: 'Estado del pago' });
    await expect(status).toContainText('Tu pedido es el #99');
    expect(approved).toBe(true);
    await status.getByRole('button', { name: 'Consultar estado del pago' }).click();
    await expect(status).toContainText('Tu pedido es el #99');
    expect(requests).toHaveLength(1);
    await page.waitForTimeout(5000);
  });
});
