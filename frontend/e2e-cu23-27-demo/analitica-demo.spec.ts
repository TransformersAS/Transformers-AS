import { test, expect } from '@playwright/test';
import { TIENDA, VENDEDOR, abrirDesdeMenu, iniciarSesion, narrador, pausa, simularBackend } from './apoyo';

/**
 * CU-26: recorrido visual de la analítica de ventas y rentabilidad de una tienda, en un solo navegador.
 * La API está simulada aquí: arma el informe con las ventas de cuatro productos, aplica los filtros y la comparación
 * que pide la pantalla, y falla la primera exportación a propósito para mostrar el reintento. Los cálculos reales
 * (descuentos, reembolsos, costos copiados al comprar) los prueban las pruebas del módulo analytics.
 */

/** Ventas del periodo por producto. `anterior` es la proporción vendida en el periodo de comparación. */
const VENTAS = [
  { id: 31, nombre: 'Audífonos inalámbricos con estuche', categoria: 'Tecnología', unidades: 12, ventas: 2268000,
    reembolsos: 189000, costos: 1440000, sinCosto: 0, anterior: 0.6 },
  { id: 11, nombre: 'Camiseta estampada talla M', categoria: 'Ropa', unidades: 25, ventas: 1300000, reembolsos: 0,
    costos: 650000, sinCosto: 0, anterior: 0.9 },
  { id: 12, nombre: 'Gorra bordada', categoria: 'Ropa', unidades: 9, ventas: 315000, reembolsos: 0, costos: 0,
    sinCosto: 9, anterior: 1.2 },
  { id: 21, nombre: 'Lámpara de escritorio', categoria: 'Hogar', unidades: 6, ventas: 540000, reembolsos: 90000,
    costos: 300000, sinCosto: 0, anterior: 0.8 }
];
const COMPARABLES = ['sales', 'refunds', 'netRevenue', 'costs', 'profit', 'orders', 'units', 'averageTicket'] as const;

const sumar = (valores: number[]) => valores.reduce((total, valor) => total + valor, 0);
const redondear = (valor: number, decimales = 0) => Math.round(valor * 10 ** decimales) / 10 ** decimales;

/** Días del periodo, como yyyy-MM-dd. */
function dias(desde: string, hasta: string): string[] {
  const [a, m, d] = desde.split('-').map(Number);
  const lista: string[] = [];
  for (let i = 0; ; i++) {
    const dia = new Date(a, m - 1, d + i);
    const iso = `${dia.getFullYear()}-${String(dia.getMonth() + 1).padStart(2, '0')}-${String(dia.getDate()).padStart(2, '0')}`;
    if (iso > hasta || i > 800) return lista;
    lista.push(iso);
  }
}

/** Resultado de un periodo con los filtros aplicados, como lo calcula el backend en SQL. */
function periodo(desde: string, hasta: string, productoId: number | null, categoria: string | null, esComparacion: boolean) {
  const lineas = VENTAS
    .filter(v => (productoId === null || v.id === productoId) && (!categoria || v.categoria === categoria))
    .map(v => {
      const f = esComparacion ? v.anterior : 1;
      return { productId: v.id, name: v.nombre, category: v.categoria, units: Math.round(v.unidades * f),
        sales: Math.round(v.ventas * f), refunds: Math.round(v.reembolsos * f), costs: Math.round(v.costos * f),
        profit: Math.round((v.ventas - v.reembolsos - v.costos) * f), unitsWithoutCost: Math.round(v.sinCosto * f) };
    })
    .sort((x, y) => y.units - x.units);
  const ventas = sumar(lineas.map(l => l.sales));
  const reembolsos = sumar(lineas.map(l => l.refunds));
  const costos = sumar(lineas.map(l => l.costs));
  const unidades = sumar(lineas.map(l => l.units));
  const pedidos = Math.round(unidades * 0.7);
  const sinCosto = sumar(lineas.map(l => l.unitsWithoutCost));
  const neto = ventas - reembolsos;
  const fechas = dias(desde, hasta);
  // Reparto de las ventas en el tiempo: más movimiento hacia el fin de semana.
  const pesos = fechas.map((_, i) => 1 + 0.6 * Math.sin(i / 2.2) + (i % 7 >= 5 ? 0.8 : 0));
  const totalPesos = sumar(pesos) || 1;
  const categorias = [...new Set(lineas.map(l => l.category))].map(nombre => ({ category: nombre,
    units: sumar(lineas.filter(l => l.category === nombre).map(l => l.units)),
    sales: sumar(lineas.filter(l => l.category === nombre).map(l => l.sales)) }));
  return {
    from: desde, to: hasta, hasData: lineas.length > 0,
    indicators: { sales: ventas, discounts: Math.round(ventas * 0.04), refunds: reembolsos, netRevenue: neto, costs: costos,
      profit: neto - costos, marginPercent: neto > 0 ? redondear((neto - costos) / neto * 100, 1) : null, orders: pedidos,
      units: unidades, averageTicket: pedidos > 0 ? Math.round(ventas / pedidos) : 0, unitsWithoutCost: sinCosto,
      costComplete: sinCosto === 0 },
    topProducts: lineas, categories: categorias.sort((x, y) => y.sales - x.sales),
    series: fechas.map((inicio, i) => ({ start: inicio, sales: Math.round(ventas * pesos[i] / totalPesos),
      costs: Math.round(costos * pesos[i] / totalPesos), orders: Math.round(pedidos * pesos[i] / totalPesos) }))
  };
}

function informe(params: URLSearchParams) {
  const productoId = params.get('productId') ? Number(params.get('productId')) : null;
  const categoria = params.get('category');
  const actual = periodo(params.get('from')!, params.get('to')!, productoId, categoria, false);
  const comparacion = params.has('compareFrom')
    ? periodo(params.get('compareFrom')!, params.get('compareTo')!, productoId, categoria, true) : null;
  return {
    storeName: TIENDA, productId: productoId, productName: VENTAS.find(v => v.id === productoId)?.nombre ?? null,
    category: categoria, granularity: 'DAY', current: actual, comparison: comparacion,
    comparisons: comparacion ? COMPARABLES.map(indicador => {
      const ahora = actual.indicators[indicador];
      const antes = comparacion.indicators[indicador];
      return { indicator: indicador, current: ahora, previous: antes, difference: ahora - antes,
        percentChange: antes !== 0 ? redondear((ahora - antes) / antes * 100, 1) : null, percentAvailable: antes !== 0 };
    }) : []
  };
}

/** Estado de la API simulada: cuenta las consultas y las exportaciones que pide la pantalla. */
function crearServidor() {
  const consultas: URLSearchParams[] = [];
  let exportaciones = 0;
  return {
    consultas: () => consultas.length,
    ultima: () => consultas[consultas.length - 1],
    exportaciones: () => exportaciones,

    atender(ruta: string, _metodo: string, peticion: any, responder: any, route: any) {
      if (ruta === '/api/seller/analytics/options') {
        return responder({ products: VENTAS.map(v => ({ id: v.id, name: v.nombre, category: v.categoria,
          hasCost: v.sinCosto === 0 })), categories: [...new Set(VENTAS.map(v => v.categoria))].sort() });
      }
      if (ruta === '/api/seller/analytics/export') {
        // La primera exportación falla (A6): los resultados deben seguir en pantalla y poderse reintentar.
        if (++exportaciones === 1) return responder({ status: 500, code: 'EXPORT_FAILED', message: 'No se pudo generar el archivo' }, 500);
        return route.fulfill({ status: 200, body: 'Libro de Excel simulado para la demostración',
          contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
      }
      if (ruta === '/api/seller/analytics') {
        const params = new URL(peticion.url()).searchParams;
        consultas.push(params);
        return responder(informe(params));
      }
      return false;
    }
  };
}

test('CU-26: consultar ventas y rentabilidad, comparar periodos, filtrar, validar el periodo y exportar a Excel', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, (ruta, metodo, peticion, responder, route) =>
    servidor.atender(ruta, metodo, peticion, responder, route));

  const etapa = narrador(page, 'CU-26');
  const panel = page.locator('app-analitica-ventas');
  const indicadores = panel.getByRole('list', { name: 'Indicadores' });
  const periodoActual = panel.getByRole('group', { name: 'Periodo' });
  const filtros = panel.getByRole('group', { name: 'Filtros (opcionales)' });
  const consultar = panel.getByRole('button', { name: 'Consultar', exact: true });

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('La tienda abre la analítica de los últimos 30 días', async () => {
    await etapa('La tienda inicia sesión y abre «Analítica»');
    await iniciarSesion(page, VENDEDOR);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Analítica');
    await expect(indicadores).toContainText('Ventas');
    await expect(indicadores).toContainText('4.423.000');
    await expect(indicadores).toContainText('Ganancia');
    await expect(indicadores).toContainText('1.754.000');
    await expect(indicadores).toContainText('42,3 %');
    await etapa('Ventas, reembolsos, ingresos netos, costos, ganancia y margen del periodo');
    await expect(panel.getByText('9 unidad(es) vendidas no tienen costo unitario registrado')).toBeVisible();
    await etapa('Avisa que 9 unidades no tienen costo: la ganancia solo cuenta las que sí lo tienen');
  });

  await test.step('Revisa las gráficas y el detalle por producto', async () => {
    const grafica = panel.getByRole('img', { name: /Ventas y costos por día/ });
    await grafica.scrollIntoViewIfNeeded();
    await expect(grafica).toBeVisible();
    await etapa('Ventas y costos día a día');
    const masVendidos = panel.getByRole('list', { name: 'Productos más vendidos por unidades' });
    await masVendidos.scrollIntoViewIfNeeded();
    await expect(masVendidos.locator('li').first()).toContainText('Camiseta estampada talla M');
    await expect(panel.getByRole('list', { name: 'Ventas por categoría' })).toContainText('Tecnología');
    const detalle = panel.locator('table.detalle');
    await detalle.scrollIntoViewIfNeeded();
    await expect(detalle.getByRole('row', { name: /Gorra bordada/ })).toContainText('parcial');
    await etapa('Productos más vendidos, ventas por categoría y la ganancia de cada producto');
  });

  await test.step('Compara con el periodo anterior', async () => {
    await etapa('¿Vendió más o menos que en los 30 días anteriores?');
    await panel.getByLabel('Comparar con otro periodo').check();
    await consultar.click();
    await expect(indicadores).toContainText('Antes:');
    await expect(indicadores.locator('li').filter({ hasText: 'Ventas' }).first()).toContainText('+32,4 %');
    expect(servidor.ultima().get('compareTo')! < servidor.ultima().get('from')!).toBe(true);
    await indicadores.scrollIntoViewIfNeeded();
    await etapa('Cada indicador muestra el valor anterior, la diferencia y la variación');
  });

  await test.step('Filtra por producto y por categoría', async () => {
    await filtros.getByLabel('Producto').selectOption({ label: 'Audífonos inalámbricos con estuche' });
    await consultar.click();
    await expect(panel.getByRole('region', { name: 'Resultados' })).toContainText('Producto: Audífonos inalámbricos con estuche');
    await expect(indicadores).toContainText('2.268.000');
    await expect(panel.getByText('no tienen costo unitario registrado')).toBeHidden();
    await etapa('Solo los audífonos: sus ventas, sus reembolsos y su ganancia');
    await filtros.getByLabel('Categoría').selectOption('Ropa');
    await consultar.click();
    await expect(panel.getByText('No hay resultados para esa combinación de producto y categoría en el periodo.')).toBeVisible();
    await etapa('Los audífonos no son de «Ropa»: lo explica en vez de mostrar una pantalla vacía');
    await filtros.getByLabel('Producto').selectOption({ label: 'Todos los productos' });
    await filtros.getByLabel('Categoría').selectOption({ label: 'Todas las categorías' });
  });

  await test.step('Un periodo mal escrito se corrige junto al campo, sin consultar', async () => {
    const desde = await periodoActual.getByLabel('Desde').inputValue();
    const hasta = await periodoActual.getByLabel('Hasta').inputValue();
    const antes = servidor.consultas();
    await periodoActual.scrollIntoViewIfNeeded();
    await periodoActual.getByLabel('Desde').fill(hasta);
    await periodoActual.getByLabel('Hasta').fill(desde);
    await consultar.click();
    await expect(panel.getByText('La fecha inicial no puede ser posterior a la final.')).toBeVisible();
    await expect(periodoActual.getByLabel('Desde')).toHaveAttribute('aria-invalid', 'true');
    expect(servidor.consultas()).toBe(antes);
    await etapa('Fechas al revés: el error aparece junto al campo y no se pierde nada de lo escrito');
    await periodoActual.getByLabel('Desde').fill(desde);
    await periodoActual.getByLabel('Hasta').fill(hasta);
    await panel.getByLabel('Comparar con otro periodo').uncheck();
    await consultar.click();
    await expect(indicadores).toContainText('4.423.000');
  });

  await test.step('Exporta a Excel; si falla, conserva los resultados y reintenta', async () => {
    await etapa('Exporta el informe a Excel');
    await panel.getByRole('button', { name: 'Exportar a Excel', exact: true }).click();
    await expect(panel.getByRole('alert').filter({ hasText: 'No se pudo generar el archivo Excel' })).toBeVisible();
    await expect(indicadores).toContainText('4.423.000');
    await etapa('La exportación falló, pero los resultados siguen en pantalla');
    const descarga = page.waitForEvent('download');
    await panel.getByRole('button', { name: 'Reintentar la exportación', exact: true }).click();
    expect((await descarga).suggestedFilename()).toMatch(/^analitica-ventas-\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.xlsx$/);
    expect(servidor.exportaciones()).toBe(2);
    await etapa('Fin: el reintento descarga el archivo con el mismo periodo y filtros que se ven en pantalla');
    await pausa(page, 4000);
  });
});
