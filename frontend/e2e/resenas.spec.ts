import { test, expect, Page } from '@playwright/test';

// HTTP controlado: verifica la interfaz; MySQL, permisos e imágenes reales se prueban en Java.
async function fixture(page: Page, seller = false, status = 'DELIVERED') {
  let review: any = null;
  let failures = 0;
  const writes: any[] = [];
  const filters: string[] = [];
  const product = { id: 2, name: 'Jean de prueba', description: 'Producto', price: 80000, stock: 5,
    category: 'Ropa', active: true, imageUrls: [], variants: [], specifications: [], ratingAverage: 0, reviewCount: 0 };
  const order = { id: 7, status, total: 90000, shippingMethod: 'STANDARD', createdAt: '2026-10-01T12:00:00',
    items: [{ productId: 2, productName: product.name, quantity: 1, unitPrice: 80000, subtotal: 80000 }], history: [] };
  const sample = () => ({ id: 11, productId: 2, productName: product.name, rating: 5, comment: '', images: [],
    reply: null, orderId: 7, createdAt: '2026-11-09T12:00:00', canManageReply: seller });
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const reply = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'test@example.test', roles: [seller ? 'VENDEDOR' : 'COMPRADOR'], activeRole: seller ? 'VENDEDOR' : 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [], total: 0 });
    if (path === '/api/products') return reply([product]);
    if (path === '/api/products/2') return reply(product);
    if (path === '/api/orders') return reply([order]);
    if (path === '/api/orders/7') return reply(order);
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    if (path === '/api/reviews/eligibility') return reply({ eligible: status === 'DELIVERED', message: 'El pedido debe estar entregado.', review });
    if (path === '/api/reviews/mine') return reply(review ? [review] : []);
    if (path === '/api/reviews/products/2') return reply(review ? [{ ...review, orderId: null }] : []);
    if (path === '/api/reviews' || path === '/api/reviews/11') {
      if (request.method() === 'DELETE') { review = null; return reply(null); }
      if (failures-- > 0) return route.fulfill({ status: 503, contentType: 'application/json', body: '{}' });
      const data = request.postDataJSON(); writes.push(data);
      review = { ...sample(), ...data }; return reply(review);
    }
    if (path === '/api/seller/reviews') { filters.push(url.search); return reply(review ? [review] : []); }
    if (path.endsWith('/summary')) return reply(path.includes('/seller/') ? [] : { productId: 2, count: review ? 1 : 0, average: review?.rating ?? 0, distribution: [0, 0, 0, 0, review ? 1 : 0] });
    if (path === '/api/seller/reviews/11/purchase') return reply({ orderId: 7, productId: 2, productName: product.name, quantity: 1, status: 'DELIVERED', createdAt: order.createdAt });
    if (path === '/api/seller/reviews/11/reply') {
      review.reply = request.method() === 'DELETE' ? null : { text: request.postDataJSON().text, updatedAt: order.createdAt };
      return reply(null);
    }
    return reply([]);
  });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  return { writes, filters, seed: () => review = sample(), failNext: () => failures = 1 };
}

async function editor(page: Page) {
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await page.getByRole('button', { name: 'Reseñar producto', exact: true }).click();
  return page.locator('app-editor-resena');
}
async function panel(page: Page, seller = false) {
  await page.getByRole('button', { name: seller ? 'Opciones de mi negocio' : 'Opciones de mis compras', exact: true }).click();
  await page.getByRole('button', { name: seller ? 'Reseñas recibidas' : 'Mis reseñas', exact: true }).click();
  return page.getByRole('dialog', { name: seller ? 'Reseñas recibidas' : 'Mis reseñas', exact: true });
}

test('publica solo calificación desde una compra entregada y conserva su asociación', async ({ page }) => {
  const state = await fixture(page); const form = await editor(page);
  await form.getByLabel('Calificación obligatoria').selectOption({ label: '5 estrellas' });
  await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
  await expect(form.getByRole('status')).toContainText('Reseña guardada');
  expect(state.writes[0]).toMatchObject({ orderId: 7, productId: 2, rating: 5, comment: '' });
});

test('calificación requerida e imagen inválida conservan el comentario', async ({ page }) => {
  const state = await fixture(page); const form = await editor(page);
  await form.getByLabel('Comentario opcional').fill('Mi experiencia');
  await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
  await expect(form.getByRole('alert')).toContainText('Selecciona una calificación');
  await form.locator('input[type=file]').setInputFiles({ name: 'no.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') });
  await expect(form.getByText('Selecciona hasta 3 imágenes', { exact: false })).toBeVisible();
  await expect(form.getByLabel('Comentario opcional')).toHaveValue('Mi experiencia');
  expect(state.writes).toHaveLength(0);
});

test('un error de publicación permite reintentar con los mismos datos', async ({ page }) => {
  const state = await fixture(page); state.failNext(); const form = await editor(page);
  await form.getByLabel('Calificación obligatoria').selectOption({ label: '4 estrellas' });
  await form.getByLabel('Comentario opcional').fill('Comentario conservado');
  await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
  await expect(form.getByRole('alert')).toContainText('Conservamos tus datos');
  await expect(form.getByLabel('Comentario opcional')).toHaveValue('Comentario conservado');
  await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
  await expect(form.getByRole('status')).toContainText('Reseña guardada');
  expect(state.writes).toHaveLength(1);
});

test('modifica y elimina una reseña propia con confirmación', async ({ page }) => {
  const state = await fixture(page); state.seed(); const view = await panel(page);
  await view.getByRole('button', { name: 'Modificar mi reseña', exact: true }).click();
  await view.getByLabel('Calificación obligatoria').selectOption({ label: '3 estrellas' });
  await view.getByLabel('Comentario opcional').fill('Opinión actualizada');
  await view.getByRole('button', { name: 'Guardar cambios de reseña', exact: true }).click();
  await expect(view).toContainText('Opinión actualizada');
  expect(state.writes[0].rating).toBe(3);
  await view.getByRole('button', { name: 'Eliminar mi reseña', exact: true }).click();
  await view.getByRole('button', { name: 'Confirmar eliminación de reseña', exact: true }).click();
  await expect(view).toContainText('No hay reseñas para mostrar');
});

test('pedido no entregado no muestra la acción de reseñar', async ({ page }) => {
  await fixture(page, false, 'CONFIRMED');
  await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
  await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
  await expect(page.getByRole('button', { name: 'Reseñar producto', exact: true })).toHaveCount(0);
});

test('detalle público muestra valoración y comentario como texto y permite iniciar reporte', async ({ page }) => {
  const state = await fixture(page); state.seed();
  await page.route(/\/api\/reviews\/products\/2(?:\?|$)/, route => route.fulfill({ json: [{
    id: 11, productId: 2, productName: 'Jean de prueba', rating: 5,
    comment: '<script>window.reviewUnsafe=true</script>', images: [], reply: null,
    orderId: null, createdAt: '2026-11-09T12:00:00', canManageReply: false,
  }] }));
  await page.locator('#catalogo .product-card').first().getByRole('button', { name: 'Ver detalle', exact: true }).click();
  const reviews = page.getByRole('region', { name: 'Reseñas del producto', exact: true });
  await expect(reviews).toContainText('Promedio: 5');
  await expect(reviews).toContainText('<script>window.reviewUnsafe=true</script>');
  expect(await page.evaluate(() => (window as any).reviewUnsafe)).toBeUndefined();
  await reviews.getByRole('button', { name: 'Reportar reseña', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Reportar reseña', exact: true })).toBeVisible();
});

test('vendedor filtra, verifica compra y publica, edita y elimina su respuesta', async ({ page }) => {
  const state = await fixture(page, true); state.seed(); const view = await panel(page, true);
  await view.getByLabel('Producto (ID opcional)').fill('2');
  await view.getByRole('combobox', { name: 'Calificación', exact: true }).selectOption('5');
  await view.getByRole('button', { name: 'Filtrar reseñas', exact: true }).click();
  await expect.poll(() => state.filters.at(-1)).toContain('productId=2');
  await view.getByRole('button', { name: 'Consultar compra asociada', exact: true }).click();
  await expect(view).toContainText('Pedido #7');
  await view.getByLabel('Respuesta a Jean de prueba').fill('Gracias por tu opinión');
  await view.getByRole('button', { name: 'Publicar respuesta', exact: true }).click();
  await expect(view.getByText('Gracias por tu opinión', { exact: true })).toBeVisible();
  await view.getByLabel('Respuesta a Jean de prueba').fill('Respuesta actualizada');
  await view.getByRole('button', { name: 'Guardar respuesta', exact: true }).click();
  await expect(view.getByText('Respuesta actualizada', { exact: true })).toBeVisible();
  await view.getByRole('button', { name: 'Eliminar mi respuesta', exact: true }).click();
  await expect(view.getByText('Respuesta del vendedor', { exact: true })).toHaveCount(0);
});
