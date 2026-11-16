import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test('CU-05: compra entregada, reseña, respuesta del vendedor y valoración pública', async ({ page }) => {
  let seller = false;
  let review: any = null;
  const writes: any[] = [];
  const image = readFileSync(resolve(__dirname, '../src/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'));
  const product = { id: 2, name: 'Pantalón jean clásico', description: 'Pantalón de mezclilla de corte recto', price: 95000, stock: 8,
    category: 'Ropa', active: true, imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'], variants: [], specifications: [] };
  const order = { id: 7, status: 'DELIVERED', total: 105000, shippingMethod: 'STANDARD', createdAt: '2026-11-01T12:00:00',
    items: [{ productId: 2, productName: product.name, quantity: 1, unitPrice: 95000, subtotal: 95000 }], history: [] };
  const summary = () => ({ productId: 2, count: review ? 1 : 0, average: review?.rating ?? 0,
    distribution: [1, 2, 3, 4, 5].map(n => review?.rating === n ? 1 : 0) });
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname, method = req.method();
    const reply = (json: unknown) => route.fulfill({ json });
    const rows = () => review ? [{ ...review, canManageReply: seller }] : [];
    if (path === '/api/auth/me') return reply({ accountId: seller ? 3 : 2, email: seller ? 'vendedor.demo@example.test' : 'comprador.demo@example.test',
      roles: [seller ? 'VENDEDOR' : 'COMPRADOR'], activeRole: seller ? 'VENDEDOR' : 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (path === '/api/products') return reply([{ ...product, ratingAverage: summary().average, reviewCount: summary().count }]);
    if (path === '/api/products/2') return reply(product);
    if (path === '/api/orders') return reply([order]);
    if (path === '/api/orders/7') return reply(order);
    if (path === '/api/cart') return reply({ items: [], total: 0 });
    if (path === '/api/recommendations') return reply({ products: [], strategy: 'DEMO_CONTROLADA' });
    if (path === '/api/notifications') return reply({ items: [], hasMore: false });
    if (path === '/api/reviews/eligibility') return reply({ eligible: true, message: '', review });
    if (path === '/api/reviews/mine' || path === '/api/seller/reviews') return reply(rows());
    if (path === '/api/reviews/products/2') return reply(rows().map(r => ({ ...r, orderId: null })));
    if (path === '/api/reviews/products/2/summary') return reply(summary());
    if (path === '/api/seller/reviews/summary') return reply([summary()]);
    if (path === '/api/reviews/images/1') return route.fulfill({ contentType: 'image/jpeg', body: image });
    if (path === '/api/reviews' || path === '/api/reviews/11') {
      expect(req.headers()['x-csrf-token']).toBe('demo-csrf');
      if (method === 'DELETE') { review = null; return reply(null); }
      const multipart = req.headers()['content-type']?.includes('multipart/form-data');
      const data = multipart ? JSON.parse(req.postDataBuffer()!.toString().match(/name="review"[\s\S]*?\r\n\r\n(\{[^\r\n]+\})/)![1]) : req.postDataJSON();
      writes.push(data);
      review = { id: 11, productName: product.name, createdAt: '2026-11-15T12:00:00', reply: review?.reply ?? null,
        ...data, images: multipart ? [1] : data.keepImageIds };
      return reply(review);
    }
    if (path === '/api/seller/reviews/11/purchase') return reply({ orderId: 7, productId: 2, productName: product.name, quantity: 1, status: 'DELIVERED', createdAt: order.createdAt });
    if (path === '/api/seller/reviews/11/reply') {
      review.reply = method === 'DELETE' ? null : { text: req.postDataJSON().text, updatedAt: '2026-11-15T12:30:00' };
      return reply(null);
    }
    return reply([]);
  });
  const stage = async (text: string) => {
    console.log(text);
    await page.evaluate(text => {
      document.querySelector('#cu05-demo-note')?.remove();
      const note = document.createElement('aside'); note.id = 'cu05-demo-note';
      note.textContent = `Demostración con datos controlados · ${text}`;
      Object.assign(note.style, { position: 'fixed', bottom: '16px', right: '16px', zIndex: '99999', maxWidth: '330px', padding: '14px',
        background: '#fff4df', color: '#183037', border: '2px solid #f56b4b', borderRadius: '12px', pointerEvents: 'none' });
      document.body.appendChild(note);
    }, text);
    await page.waitForTimeout(2400);
  };
  const openReviews = async () => {
    await page.getByRole('button', { name: seller ? 'Opciones de mi negocio' : 'Opciones de mis compras', exact: true }).click();
    await page.getByRole('button', { name: seller ? 'Reseñas recibidas' : 'Mis reseñas', exact: true }).click();
    return page.getByRole('dialog', { name: seller ? 'Reseñas recibidas' : 'Mis reseñas', exact: true });
  };
  const publicReview = async (rating: number, comment?: string, response?: string) => {
    await page.locator('#catalogo .product-card').getByRole('button', { name: 'Ver detalle', exact: true }).click();
    const region = page.getByRole('region', { name: 'Reseñas del producto', exact: true });
    await expect(region).toContainText(`Promedio: ${rating}`);
    if (comment) await expect(region).toContainText(comment);
    if (response) await expect(region).toContainText(response);
    await stage(`Valoración pública: ${rating} / 5${response ? ', con respuesta del vendedor' : ''}`);
    await page.getByRole('button', { name: 'Cerrar detalle', exact: true }).click();
  };
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await test.step('Comprador publica desde una compra entregada', async () => {
    await page.locator('#catalogo .product-card').scrollIntoViewIfNeeded();
    await expect.poll(() => page.locator('#catalogo .product-card img').first().evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
    await stage('Comprador: reseñar el pantalón de una compra entregada');
    await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
    await page.getByRole('button', { name: 'Ver detalle del pedido 7' }).click();
    await expect(page.locator('app-mis-pedidos')).toContainText(product.name);
    await stage('Pedido #7 entregado: permite reseñar este producto');
    await page.getByRole('button', { name: 'Reseñar producto', exact: true }).click();
    const form = page.locator('app-editor-resena');
    await form.getByLabel('Comentario opcional').fill('Buen ajuste y material cómodo.');
    await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
    await expect(form.getByRole('alert')).toContainText('Selecciona una calificación');
    await stage('La calificación es obligatoria; el comentario se conserva');
    await form.getByLabel('Calificación obligatoria').selectOption({ label: '5 estrellas' });
    await form.locator('input[type=file]').setInputFiles({ name: 'pantalon-demo.jpg', mimeType: 'image/jpeg', buffer: image });
    await stage('Publicar 5 estrellas, comentario y una imagen de demostración');
    await form.getByRole('button', { name: 'Publicar reseña', exact: true }).click();
    await expect(form.getByRole('status')).toContainText('Reseña guardada');
    expect(writes[0]).toMatchObject({ orderId: 7, productId: 2, rating: 5 });
    await page.getByRole('button', { name: 'Cerrar pedidos', exact: true }).click();
    await publicReview(5, 'Buen ajuste y material cómodo.');
  });
  await test.step('Comprador modifica su reseña', async () => {
    const view = await openReviews();
    await view.getByRole('button', { name: 'Modificar mi reseña', exact: true }).click();
    await view.getByLabel('Calificación obligatoria').selectOption({ label: '4 estrellas' });
    await view.getByLabel('Comentario opcional').fill('Buen material; la talla queda un poco amplia.');
    await view.getByRole('button', { name: 'Guardar cambios de reseña', exact: true }).click();
    await expect(view).toContainText('Buen material; la talla queda un poco amplia.');
    await stage('Reseña modificada a 4 estrellas; la valoración se actualiza');
    await view.getByRole('button', { name: 'Cerrar reseñas', exact: true }).click();
  });
  await test.step('Vendedor consulta la compra y administra su respuesta', async () => {
    seller = true;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await stage('Cambio de sesión controlado: ahora se muestra el vendedor');
    const view = await openReviews();
    await view.getByLabel('Producto (ID opcional)').fill('2');
    await view.getByRole('combobox', { name: 'Calificación', exact: true }).selectOption('4');
    await view.getByRole('button', { name: 'Filtrar reseñas', exact: true }).click();
    await view.getByRole('button', { name: 'Consultar compra asociada', exact: true }).click();
    await expect(view).toContainText('Pedido #7');
    await stage('Vendedor verifica la compra asociada y la valoración consolidada');
    await view.getByLabel(`Respuesta a ${product.name}`).fill('Gracias por compartir tu experiencia.');
    await view.getByRole('button', { name: 'Publicar respuesta', exact: true }).click();
    await expect(view.getByText('Gracias por compartir tu experiencia.', { exact: true })).toBeVisible();
    await stage('Respuesta publicada por el vendedor');
    await view.getByLabel(`Respuesta a ${product.name}`).fill('Gracias; añadiremos más información sobre las tallas.');
    await view.getByRole('button', { name: 'Guardar respuesta', exact: true }).click();
    await expect(view.getByText('Gracias; añadiremos más información sobre las tallas.', { exact: true })).toBeVisible();
    await view.getByRole('button', { name: 'Cerrar reseñas', exact: true }).click();
  });
  await test.step('Comprador consulta la respuesta y elimina su reseña', async () => {
    seller = false;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await publicReview(4, 'Buen material; la talla queda un poco amplia.', 'Gracias; añadiremos más información sobre las tallas.');
    const view = await openReviews();
    await view.getByRole('button', { name: 'Eliminar mi reseña', exact: true }).click();
    await stage('Eliminar la reseña requiere confirmación del comprador');
    await view.getByRole('button', { name: 'Confirmar eliminación de reseña', exact: true }).click();
    await expect(view).toContainText('No hay reseñas para mostrar');
    expect(review).toBeNull();
    await view.getByRole('button', { name: 'Cerrar reseñas', exact: true }).click();
    await publicReview(0);
    await stage('Recorrido completo: reseña eliminada y valoración recalculada');
  });
});
