import { test, expect } from '@playwright/test';
import { cerrarPanelCuenta, escribir, iniciarSesion, narrador, pausa } from '../e2e-cu12-17-demo/apoyo';
import { CUENTA_DEMO, api, informar, usarRol } from './real';

/**
 * CU-15 contra la aplicación REAL: el vendedor de la tienda 1 registra una entrada de mercancía, corrige el conteo
 * físico, configura el nivel mínimo y ve la alerta de stock bajo y el historial. Todo queda en MySQL: el stock y el
 * mínimo en `products` y cada cambio en `stock_movements`.
 */

const PRODUCTO = process.env['DEMO_PRODUCTO'] ?? 'Botella reutilizable demo';
const ENTRADA = 12;
const DANADAS = 2;

test(`CU-15 real: entrada, ajuste y mínimo de «${PRODUCTO}» quedan en MySQL`, async ({ page }) => {
  const nota = narrador(page, 'CU-15 · app real');
  await page.goto('/');

  await nota('Entramos a la aplicación real con la cuenta de la tienda 1, como vendedor.');
  await iniciarSesion(page, CUENTA_DEMO);
  await cerrarPanelCuenta(page);
  await usarRol(page, 'VENDEDOR');

  const inventario = await api(page, 'get', `/api/seller/inventory?q=${encodeURIComponent(PRODUCTO)}`);
  const inicial = (inventario as any[]).find(i => i.name === PRODUCTO);
  expect(inicial, `no existe «${PRODUCTO}» en la tienda 1`).toBeTruthy();
  informar('PRODUCTO_ID', inicial.productId);
  informar('STOCK_INICIAL', inicial.stock);

  await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
  await page.getByRole('button', { name: 'Inventario', exact: true }).click();
  const panel = page.locator('app-inventario');
  await escribir(panel.getByLabel('Buscar por nombre o categoría'), PRODUCTO);
  await panel.getByRole('button', { name: 'Buscar' }).click();
  const fila = panel.locator('ion-list > ion-item').filter({ has: page.locator('h2', { hasText: PRODUCTO }) }).first();
  await expect(fila).toContainText(`Físico ${inicial.stock}`);
  await nota(`«${PRODUCTO}» tiene ${inicial.stock} unidades físicas.`);

  // ---------- Entrada de mercancía ----------
  await nota(`Llega un pedido del proveedor con ${ENTRADA} unidades.`);
  await fila.getByRole('button', { name: 'Registrar entrada' }).click();
  await escribir(panel.getByLabel('Unidades que llegaron'), String(ENTRADA));
  await escribir(panel.getByLabel('Motivo (opcional)'), `Pedido del proveedor (demo ${process.env['DEMO_SELLO'] ?? ''})`);
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status').first()).toContainText('Entrada registrada');
  const conEntrada = inicial.stock + ENTRADA;
  await expect(fila).toContainText(`Físico ${conEntrada}`);

  // ---------- Ajuste por conteo físico ----------
  const conteo = conEntrada - DANADAS;
  await nota(`Al contar la bodega hay ${conteo}: ${DANADAS} llegaron rotas. Se ajusta con un motivo obligatorio.`);
  await fila.getByRole('button', { name: 'Ajustar conteo' }).click();
  await escribir(panel.getByLabel('Conteo real (unidades)'), String(conteo));
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('alert').first()).toBeVisible();
  await nota('Sin motivo el sistema no acepta el ajuste.');
  await escribir(panel.getByLabel('Motivo (obligatorio)'), `${DANADAS} unidades rotas en el transporte`);
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status').first()).toContainText('Ajuste registrado');
  await expect(fila).toContainText(`Físico ${conteo}`);

  // ---------- Nivel mínimo y alerta ----------
  const minimo = conteo + 5;
  await nota(`El vendedor quiere un aviso cuando queden menos de ${minimo} unidades.`);
  await fila.getByRole('button', { name: 'Mínimo' }).click();
  await escribir(panel.getByLabel('Nivel mínimo (unidades)'), String(minimo));
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status').first()).toContainText('Nivel mínimo guardado');
  await expect(fila.getByText('Stock bajo')).toBeVisible();
  await nota('Como el disponible quedó por debajo del mínimo, aparece la alerta de stock bajo.');

  // ---------- Historial ----------
  await fila.getByRole('button', { name: 'Historial' }).click();
  await expect(panel.getByText(`Entrada: +${ENTRADA} → ${conEntrada} unidades`).first()).toBeVisible();
  await expect(panel.getByText(`Ajuste: -${DANADAS} → ${conteo} unidades`).first()).toBeVisible();
  await nota('El historial guarda cada movimiento con su motivo. Ahora lo vemos en la base de datos.');
  await pausa(page, 3000);
});
