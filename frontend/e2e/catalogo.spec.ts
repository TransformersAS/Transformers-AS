import { test, expect } from '@playwright/test';

const productos = [
  { id: 1, name: 'Cafetera compacta', description: 'Cafe para casa', price: 75000, stock: 18, category: 'Hogar', active: true,
    brand: 'Marca de prueba', store: { id: 1, name: 'Tienda de prueba', description: 'Articulos para el hogar' },
    imageUrls: ['https://images.unsplash.com/cafetera-frente', 'https://images.unsplash.com/cafetera-lateral'],
    specifications: [{ name: 'Capacidad', value: '600 ml' }, { name: 'Filtro', value: 'Reutilizable' }],
    variants: [{ name: 'Negra', price: 75000, stock: 10 }] },
  { id: 2, name: 'Lampara LED', description: 'Luz de lectura', price: 55000, stock: 10, category: 'Hogar', active: true },
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

test('muestra errores del catalogo y del detalle', async ({ page }) => {
  await page.route('**/api/products/1', route => route.fulfill({ status: 404, json: { message: 'No encontrado' } }));
  await page.locator('#catalogo .product-card').first().getByRole('button', { name: 'Ver detalle' }).click();
  await expect(page.getByRole('dialog')).toContainText('El producto ya no está disponible');
  await page.getByRole('button', { name: 'Cerrar detalle' }).click();
  await page.route('**/api/products', route => route.fulfill({ status: 500, json: {} }));
  await page.reload();
  await expect(page.locator('#catalogo').getByRole('alert')).toContainText('No se pudo cargar el catálogo');
});

test('el visitante explora el catalogo y solo inicia sesion para comprar', async ({ page }) => {
  await page.route('**/api/auth/me', route => route.fulfill({ status: 401, json: {} }));
  await page.reload();
  const catalogo = page.locator('#catalogo');
  await expect(catalogo.locator('.product-card')).toHaveCount(3);
  await expect(catalogo).toContainText('Inicia sesión para comprar');
  await expect(catalogo.getByRole('button', { name: 'Reportar' })).toHaveCount(0);
  await catalogo.locator('.product-card').first().getByRole('button', { name: 'Ver detalle' }).click();
  await expect(page.getByRole('dialog')).toContainText('Cafetera compacta');
  await page.getByRole('button', { name: 'Cerrar detalle' }).click();
  await catalogo.locator('.product-card').first().getByRole('button', { name: /Agregar/ }).click();
  await expect(page.getByRole('dialog', { name: /cuenta/i })).toBeVisible();
});

test('busca, combina filtros y ordena el catalogo', async ({ page }) => {
  const catalogo = page.locator('#catalogo');
  await page.getByPlaceholder('¿Qué estás buscando hoy?').first().fill('CAFE');
  await expect(catalogo.locator('.product-card')).toHaveCount(1);
  await expect(catalogo.locator('.product-card')).toContainText('Cafetera compacta');
  await catalogo.getByRole('button', { name: 'Limpiar filtros' }).click();
  await catalogo.getByLabel('Categoría', { exact: true }).selectOption('hogar');
  await catalogo.getByLabel('Ordenar productos').selectOption('precio-asc');
  await expect(catalogo.locator('.product-card').first()).toContainText('Lampara LED');
  await catalogo.getByLabel('Precio mínimo').fill('60000');
  await expect(catalogo.locator('.product-card')).toHaveCount(1);
  await catalogo.getByLabel('Precio máximo').fill('50000');
  await expect(catalogo.getByRole('alert')).toContainText('mínimo');
  await catalogo.getByRole('button', { name: 'Limpiar filtros' }).click();
  await page.getByPlaceholder('¿Qué estás buscando hoy?').first().fill('inexistente');
  await expect(catalogo).toContainText('No hay productos que coincidan');
});

test('consulta detalle y compara solo productos de la misma categoria', async ({ page }) => {
  const catalogo = page.locator('#catalogo');
  await catalogo.locator('.product-card').first().getByRole('button', { name: 'Ver detalle' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Detalle del producto' });
  await expect(dialogo).toContainText('Cafe para casa');
  await expect(dialogo).toContainText('18 unidades');
  await expect(dialogo).toContainText('Marca de prueba');
  await expect(dialogo).toContainText('600 ml');
  await expect(dialogo).toContainText('Vendido por Tienda de prueba');
  await expect(dialogo).toContainText('Opciones publicadas');
  await expect(dialogo.getByRole('button', { name: 'Ver imagen 2' })).toBeVisible();
  await dialogo.getByRole('button', { name: 'Ver imagen 2' }).click();
  await expect(dialogo.locator('.detalle-imagen')).toHaveAttribute('src', 'https://images.unsplash.com/cafetera-lateral');
  await dialogo.getByRole('button', { name: 'Cerrar detalle' }).click();
  await catalogo.locator('.product-card').nth(0).getByRole('button', { name: 'Comparar', exact: true }).click();
  await catalogo.locator('.product-card').nth(2).getByRole('button', { name: 'Comparar', exact: true }).click();
  await expect(catalogo.getByRole('alert')).toContainText('misma categoría');
  await catalogo.locator('.product-card').nth(1).getByRole('button', { name: 'Comparar', exact: true }).click();
  await expect(catalogo.getByRole('table')).toContainText('Cafetera compacta');
  await expect(catalogo.getByRole('table')).toContainText('Lampara LED');
  await expect(catalogo.getByRole('table')).not.toContainText('Camiseta basica');
});
