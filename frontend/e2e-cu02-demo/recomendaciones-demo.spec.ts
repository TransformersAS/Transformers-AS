import { test, expect } from '@playwright/test';

const productos = [
  { id: 1, name: 'Pantalón jean clásico', description: 'Pantalón de mezclilla de corte recto', price: 95000, stock: 18, category: 'Ropa', active: true,
    brand: 'Marca de prueba', store: { id: 1, name: 'Tienda de prueba', description: 'Prendas de vestir' },
    imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg', '/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'],
    specifications: [{ name: 'Material', value: 'Mezclilla' }, { name: 'Corte', value: 'Recto' }],
    variants: [{ name: 'Talla M', price: 95000, stock: 10 }] },
  { id: 2, name: 'Chaqueta ligera', description: 'Chaqueta para clima fresco', price: 145000, stock: 10, category: 'Ropa', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1551028719-00167b16eac5.jpg'] },
  { id: 3, name: 'Camiseta basica', description: 'Algodon', price: 35000, stock: 8, category: 'Ropa', active: true },
  { id: 4, name: 'Audífonos bluetooth', description: 'Sonido inalámbrico para todos los días', price: 99000, stock: 15, category: 'Tecnologia', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1505740420928-5e560c06d30e.jpg'] },
  { id: 5, name: 'Teclado USB', description: 'Teclado para trabajar y estudiar', price: 65000, stock: 20, category: 'Tecnologia', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1587829741301-dc798b83add3.jpg'] },
  { id: 6, name: 'Cafetera de goteo', description: 'Prepara tu café en casa', price: 85000, stock: 12, category: 'Hogar', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1495474472287-4d71bcdd2085.jpg'] },
  { id: 7, name: 'Lámpara de escritorio', description: 'Iluminación para leer y estudiar', price: 55000, stock: 16, category: 'Hogar', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1507473885765-e6ed057f782c.jpg'] },
  { id: 8, name: 'Mochila urbana', description: 'Lleva tus objetos de uso diario', price: 85000, stock: 19, category: 'Accesorios', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1553062407-98eeb64c6a62.jpg'] }
];

test.beforeEach(async ({ page }) => {
  await page.route('https://images.unsplash.com/**', route => route.abort());
  await page.route('https://fonts.googleapis.com/**', route => route.abort());
  await page.route('https://fonts.gstatic.com/**', route => route.abort());
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = {};
    if (path === '/api/auth/me') body = { accountId: 1, email: 'comprador@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
    else if (path === '/api/auth/csrf') body = { headerName: 'X-CSRF-TOKEN', token: 'test-token' };
    else if (path === '/api/products') body = productos;
    else if (path.startsWith('/api/products/')) body = productos.find(p => p.id === Number(path.split('/').pop()));
    else if (path.includes('recommendations')) body = { products: [] };
    else if (path.includes('cart')) body = { items: [], total: 0 };
    await route.fulfill({ json: body });
  });
  await page.goto('/');
  await expect(page.locator('#catalogo .product-card')).toHaveCount(productos.length);
});

test('CU-02: consultar recomendaciones y explorar productos sin cerrar el navegador', async ({ page }) => {
  let term = '';
  const interactions: { interactionType: string; productId?: number; searchTerm?: string }[] = [];
  await page.route('**/api/interactions', async route => {
    const interaction = route.request().postDataJSON();
    interactions.push(interaction);
    if (interaction.interactionType === 'SEARCH') term = interaction.searchTerm;
    await route.fulfill({ json: {} });
  });
  await page.route('**/api/recommendations', async route => {
    // Respuestas controladas: no se invoca a un proveedor de IA en esta demo.
    const candidates = term === 'chaqueta' ? [2, 1, 3]
      : term === 'audífonos' ? [4, 5]
      : term === 'cafetera' ? [6, 7]
      : term === 'mochila' ? [8]
      : term === 'inexistente' ? [] : [1, 2, 3];
    const recommended = candidates.map(id => productos.find(product => product.id === id)!);
    await route.fulfill({ json: { userId: 1, strategy: 'DEMO_CONTROLADA', products: recommended } });
  });
  await page.reload();
  const section = page.locator('.recommendations-section');
  const search = page.getByPlaceholder('¿Qué estás buscando hoy?').first();
  await test.step('Consultar las recomendaciones iniciales de Ropa', async () => {
    await expect(section.locator('.recommendation-card')).toHaveCount(3);
    await section.scrollIntoViewIfNeeded();
    await page.waitForTimeout(2500);
  });
  await test.step('Buscar una chaqueta y consultar las recomendaciones actualizadas', async () => {
    await search.pressSequentially('chaqueta', { delay: 150 });
    await search.press('Enter');
    await expect(section.locator('.recommendation-card')).toHaveCount(3);
    await expect(section).toContainText('Chaqueta ligera');
    await section.scrollIntoViewIfNeeded();
    expect(interactions.some(i => i.interactionType === 'SEARCH' && i.searchTerm === 'chaqueta')).toBe(true);
  });
  await test.step('Abrir el producto recomendado y volver', async () => {
    await section.getByRole('heading', { name: 'Chaqueta ligera', exact: true }).click();
    const detail = page.getByRole('dialog', { name: 'Detalle del producto' });
    await expect(detail).toContainText('Chaqueta para clima fresco');
    await expect.poll(() => interactions.some(i => i.interactionType === 'VIEW' && i.productId === 2)).toBe(true);
    await page.waitForTimeout(2500);
    await detail.getByRole('button', { name: 'Cerrar detalle' }).click();
  });
  for (const visit of [
    { search: 'audífonos', ids: [4, 5] },
    { search: 'cafetera', ids: [6, 7] },
    { search: 'mochila', ids: [8] },
  ]) {
    await test.step(`Buscar ${visit.search} y explorar sus recomendaciones`, async () => {
      await search.fill('');
      await search.pressSequentially(visit.search, { delay: 150 });
      await search.press('Enter');
      await expect(section.locator('.recommendation-card')).toHaveCount(visit.ids.length);
      await section.scrollIntoViewIfNeeded();
      for (const id of visit.ids) {
        const product = productos.find(p => p.id === id)!;
        await section.getByRole('heading', { name: product.name, exact: true }).click();
        const detail = page.getByRole('dialog', { name: 'Detalle del producto' });
        await expect(detail).toContainText(product.description);
        await expect.poll(() => interactions.some(i => i.interactionType === 'VIEW' && i.productId === id)).toBe(true);
        await page.waitForTimeout(2000);
        await detail.getByRole('button', { name: 'Cerrar detalle' }).click();
      }
      expect(interactions.some(i => i.interactionType === 'SEARCH' && i.searchTerm === visit.search)).toBe(true);
    });
  }
  await test.step('Continuar explorando cuando no hay recomendaciones', async () => {
    await search.fill('inexistente');
    await search.press('Enter');
    await expect(section.locator('.recommendation-card')).toHaveCount(0);
    await expect(page.locator('#catalogo')).toContainText('No hay productos que coincidan');
    await page.locator('#catalogo').getByRole('button', { name: 'Limpiar filtros' }).click();
    await expect(page.locator('#catalogo .product-card')).toHaveCount(productos.length);
    await page.locator('#catalogo').scrollIntoViewIfNeeded();
    await page.waitForTimeout(5000);
  });
});

