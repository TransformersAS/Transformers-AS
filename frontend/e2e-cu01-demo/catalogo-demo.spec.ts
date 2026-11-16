import { test, expect } from '@playwright/test';

const productos = [
  { id: 1, name: 'Pantalón jean clásico', description: 'Pantalón de mezclilla de corte recto', price: 95000, stock: 18, category: 'Ropa', active: true,
    brand: 'Marca de prueba', store: { id: 1, name: 'Tienda de prueba', description: 'Prendas de vestir' },
    imageUrls: ['/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg', '/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg'],
    specifications: [{ name: 'Material', value: 'Mezclilla' }, { name: 'Corte', value: 'Recto' }],
    variants: [{ name: 'Talla M', price: 95000, stock: 10 }] },
  { id: 2, name: 'Chaqueta ligera', description: 'Chaqueta para clima fresco', price: 145000, stock: 10, category: 'Ropa', active: true,
    imageUrls: ['/assets/catalogo-demo/photo-1551028719-00167b16eac5.jpg'] },
  { id: 3, name: 'Camiseta basica', description: 'Algodon', price: 35000, stock: 8, category: 'Ropa', active: true }
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
  await expect(page.locator('#catalogo .product-card')).toHaveCount(3);
});

test('CU-01: explorar, buscar, consultar y comparar en un solo recorrido', async ({ page }) => {
  const catalogo = page.locator('#catalogo');
  const buscador = page.getByPlaceholder('¿Qué estás buscando hoy?').first();
  await test.step('Buscar un pantalón', async () => {
    await catalogo.scrollIntoViewIfNeeded();
    await buscador.pressSequentially('pantalón', { delay: 150 });
    await expect(catalogo.locator('.product-card')).toHaveCount(1);
  });
  await test.step('Seleccionar el producto y consultar su información', async () => {
    await catalogo.getByRole('button', { name: 'Ver detalle', exact: true }).click();
    const detalle = page.getByRole('dialog', { name: 'Detalle del producto' });
    await expect(detalle).toContainText('Pantalón jean clásico');
    await expect(detalle).toContainText('Mezclilla');
    await detalle.getByRole('button', { name: 'Ver imagen 2' }).click();
    await expect(detalle.locator('.detalle-imagen')).toHaveAttribute('src', productos[0].imageUrls![1]);
    await detalle.getByRole('button', { name: 'Cerrar detalle' }).click();
  });
  await test.step('Volver al catálogo y filtrar por categoría y precio', async () => {
    await catalogo.getByRole('button', { name: 'Limpiar filtros' }).click();
    await catalogo.getByLabel('Categoría', { exact: true }).selectOption('moda');
    await catalogo.getByLabel('Precio mínimo').fill('90000');
    await catalogo.getByLabel('Precio máximo').fill('150000');
    await catalogo.getByLabel('Ordenar productos').selectOption('precio-asc');
    await expect(catalogo.locator('.product-card')).toHaveCount(2);
    await expect(catalogo.locator('.product-card').first()).toContainText('Pantalón jean clásico');
  });
  await test.step('Comparar el pantalón y la chaqueta de Ropa', async () => {
    await catalogo.locator('.product-card').nth(0).getByRole('button', { name: 'Comparar', exact: true }).click();
    await catalogo.locator('.product-card').nth(1).getByRole('button', { name: 'Comparar', exact: true }).click();
    const tabla = catalogo.getByRole('table');
    await tabla.scrollIntoViewIfNeeded();
    await expect(tabla).toContainText('Pantalón jean clásico');
    await expect(tabla).toContainText('Chaqueta ligera');
    // Pausa exclusiva de presentación para poder leer la comparación final.
    await page.waitForTimeout(5000);
  });
});

