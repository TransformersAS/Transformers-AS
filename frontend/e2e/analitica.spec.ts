import { test, expect, Page } from '@playwright/test';

type Respuesta = { status?: number; body?: unknown };

function indicadores(valores: Partial<Record<string, unknown>> = {}) {
  return {
    sales: 325, discounts: 25, refunds: 30, netRevenue: 295, costs: 180, profit: 115, marginPercent: 39, orders: 2,
    units: 4, averageTicket: 162.5, unitsWithoutCost: 1, costComplete: false, ...valores
  };
}

function periodo(from: string, to: string, conDatos = true) {
  return {
    from, to, hasData: conDatos,
    indicators: conDatos ? indicadores() : indicadores({ sales: 0, discounts: 0, refunds: 0, netRevenue: 0, costs: 0,
      profit: 0, marginPercent: null, orders: 0, units: 0, averageTicket: 0, unitsWithoutCost: 0, costComplete: true }),
    topProducts: conDatos ? [
      { productId: 7, name: 'Lampara', category: 'Hogar', units: 3, sales: 280, refunds: 30, costs: 180, profit: 70, unitsWithoutCost: 0 },
      { productId: 8, name: 'Camiseta', category: 'Ropa', units: 1, sales: 45, refunds: 0, costs: 0, profit: 45, unitsWithoutCost: 1 }
    ] : [],
    categories: conDatos ? [{ category: 'Hogar', units: 3, sales: 280 }, { category: 'Ropa', units: 1, sales: 45 }] : [],
    series: [{ start: from, sales: conDatos ? 225 : 0, costs: conDatos ? 120 : 0, orders: conDatos ? 1 : 0 },
      { start: to, sales: conDatos ? 100 : 0, costs: conDatos ? 60 : 0, orders: conDatos ? 1 : 0 }]
  };
}

/** Informe como lo arma el backend a partir de los parámetros de la consulta. */
function informe(params: URLSearchParams, actual = true, comparacionConDatos = true) {
  const compara = params.has('compareFrom');
  return {
    storeName: 'Tienda principal', productId: params.get('productId') ? Number(params.get('productId')) : null,
    productName: params.get('productId') ? 'Lampara' : null, category: params.get('category'), granularity: 'DAY',
    current: periodo(params.get('from')!, params.get('to')!, actual),
    comparison: compara ? periodo(params.get('compareFrom')!, params.get('compareTo')!, comparacionConDatos) : null,
    comparisons: compara ? [
      { indicator: 'sales', current: 325, previous: comparacionConDatos ? 100 : 0, difference: comparacionConDatos ? 225 : 325,
        percentChange: comparacionConDatos ? 225 : null, percentAvailable: comparacionConDatos },
      { indicator: 'orders', current: 2, previous: comparacionConDatos ? 1 : 0, difference: 1,
        percentChange: comparacionConDatos ? 100 : null, percentAvailable: comparacionConDatos }
    ] : []
  };
}

async function abrirAnalitica(page: Page, analizar: (params: URLSearchParams, llamada: number) => Respuesta,
                              exportar: (llamada: number) => Respuesta = () => ({ body: 'xlsx' })) {
  const consultas: URLSearchParams[] = [];
  let exportaciones = 0;
  await page.route(/^https:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, r => r.abort());
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const reply = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname === '/api/auth/me') {
      return reply({ accountId: 3, email: 'vendedor@example.test', roles: ['VENDEDOR'], activeRole: 'VENDEDOR' });
    }
    if (url.pathname === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (url.pathname === '/api/seller/analytics/options') {
      return reply({ products: [{ id: 7, name: 'Lampara', category: 'Hogar', hasCost: true },
        { id: 8, name: 'Camiseta', category: 'Ropa', hasCost: false }], categories: ['Hogar', 'Ropa'] });
    }
    if (url.pathname === '/api/seller/analytics/export') {
      const respuesta = exportar(++exportaciones);
      return route.fulfill({ status: respuesta.status ?? 200,
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: String(respuesta.body ?? '') });
    }
    if (url.pathname === '/api/seller/analytics') {
      consultas.push(url.searchParams);
      const respuesta = analizar(url.searchParams, consultas.length);
      return reply(respuesta.body ?? {}, respuesta.status ?? 200);
    }
    if (url.pathname.endsWith('/notifications')) return reply({ items: [], hasMore: false });
    if (url.pathname === '/api/recommendations') return reply({ userId: 3, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
  await page.getByRole('button', { name: 'Analítica', exact: true }).click();
  return { consultas, exportaciones: () => exportaciones };
}

test('muestra indicadores, gráficas y la comparación con el periodo anterior', async ({ page }) => {
  const f = await abrirAnalitica(page, params => ({ body: informe(params) }));
  const indicadoresVisibles = page.getByRole('list', { name: 'Indicadores' });
  await expect(indicadoresVisibles).toContainText('Ventas');
  await expect(indicadoresVisibles).toContainText('325');
  await expect(indicadoresVisibles).toContainText('Ganancia');
  await expect(page.getByRole('img', { name: /Ventas y costos por día/ })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Productos más vendidos por unidades' })).toContainText('Lampara');
  await expect(page.getByText(/no tienen costo unitario registrado/)).toBeVisible();

  await page.getByLabel('Comparar con otro periodo').check();
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(indicadoresVisibles).toContainText('+225 %');
  const ultima = f.consultas[f.consultas.length - 1];
  expect(ultima.get('compareFrom')).toBeTruthy();
  expect(ultima.get('compareTo')! < ultima.get('from')!).toBe(true);
});

test('avisa que la variación no se puede calcular cuando la base es cero', async ({ page }) => {
  await abrirAnalitica(page, params => ({ body: informe(params, true, false) }));
  await page.getByLabel('Comparar con otro periodo').check();
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByText('El periodo de comparación no tiene ventas')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Indicadores' })).toContainText('No calculable (base en cero)');
});

test('valida el periodo junto al campo sin borrar lo demás y sin consultar', async ({ page }) => {
  const f = await abrirAnalitica(page, params => ({ body: informe(params) }));
  await expect(page.getByRole('list', { name: 'Indicadores' })).toBeVisible();
  const consultasAntes = f.consultas.length;
  const periodo = page.getByRole('group', { name: 'Periodo' });
  const filtros = page.getByRole('group', { name: 'Filtros (opcionales)' });
  await filtros.getByLabel('Producto').selectOption({ label: 'Lampara' });
  await periodo.getByLabel('Desde').fill('2026-05-20');
  await periodo.getByLabel('Hasta').fill('2026-05-10');
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByText('La fecha inicial no puede ser posterior a la final.')).toBeVisible();
  await expect(periodo.getByLabel('Desde')).toHaveAttribute('aria-invalid', 'true');
  await expect(filtros.getByLabel('Producto').locator('option:checked')).toHaveText('Lampara');
  expect(f.consultas.length).toBe(consultasAntes);
});

test('explica cuando no hubo ventas y cuando el filtro no tiene resultados', async ({ page }) => {
  await abrirAnalitica(page, params => ({ body: informe(params, false) }));
  await expect(page.getByText('No hubo ventas en este periodo')).toBeVisible();
  await page.getByRole('group', { name: 'Filtros (opcionales)' }).getByLabel('Categoría').selectOption('Ropa');
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByText('No hay resultados para esa combinación de producto y categoría')).toBeVisible();
});

test('si la exportación falla conserva los resultados y permite reintentar', async ({ page }) => {
  const f = await abrirAnalitica(page, params => ({ body: informe(params) }),
    llamada => llamada === 1 ? { status: 500 } : { body: 'xlsx' });
  await expect(page.getByRole('list', { name: 'Indicadores' })).toContainText('325');
  await page.getByRole('button', { name: 'Exportar a Excel' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'No se pudo generar el archivo Excel' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Indicadores' })).toContainText('325');
  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Reintentar la exportación' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/^analitica-ventas-\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.xlsx$/);
  expect(f.exportaciones()).toBe(2);
});

test('si la analítica no carga lo informa y se puede reintentar', async ({ page }) => {
  await abrirAnalitica(page, (params, llamada) => llamada === 1 ? { status: 503 } : { body: informe(params) });
  await expect(page.getByRole('alert').filter({ hasText: 'No se pudo cargar la analítica' })).toBeVisible();
  await page.getByRole('button', { name: 'Reintentar', exact: true }).click();
  await expect(page.getByRole('list', { name: 'Indicadores' })).toContainText('325');
});
