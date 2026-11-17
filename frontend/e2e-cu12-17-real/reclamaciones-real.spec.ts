import { test, expect, Page } from '@playwright/test';
import {
  abrirDesdeMenu, cerrarPanelCuenta, cerrarSesion, elegirEnLista, escribir, iniciarSesion, narrador, pausa
} from '../e2e-cu12-17-demo/apoyo';
import { CUENTA_DEMO, api, informar, usarRol } from './real';

/**
 * CU-13 contra la aplicación REAL, con sus tres actores: la compradora reclama sobre una compra entregada, el vendedor
 * de la tienda 1 pide información y propone un reembolso, la compradora escala y soporte concede el reembolso. Queda
 * en MySQL: `claims`, `claim_messages`, `claim_evidences` y el reembolso en `refunds`.
 * scripts/demos/cu13-real.sh crea antes la compra entregada (el pago real solo es por Mercado Pago, que es externo) y
 * las cuentas de la compradora y de soporte, con la contraseña pública de la cuenta demo.
 */

const PEDIDO = Number(process.env['DEMO_PEDIDO']);
const COMPRADORA = process.env['DEMO_COMPRADORA'] ?? 'cu13.compradora@demo.local';
const SOPORTE = process.env['DEMO_SOPORTE'] ?? 'cu13.soporte@demo.local';
const PRODUCTO = process.env['DEMO_PRODUCTO_NOMBRE'] ?? 'Lampara de escritorio demo';
const PAGADO = Number(process.env['DEMO_PAGADO'] ?? 55000);

async function abrirReclamaciones(page: Page, email: string, menu: string, opcion: string) {
  await iniciarSesion(page, email);
  await cerrarPanelCuenta(page);
  if (email === CUENTA_DEMO) await usarRol(page, 'VENDEDOR');
  await abrirDesdeMenu(page, menu, opcion);
  const panel = page.locator('app-reclamaciones');
  await expect(panel).toBeVisible();
  return panel;
}

async function salir(page: Page) {
  await page.locator('app-reclamaciones').getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);
}

test(`CU-13 real: la reclamación del pedido #${PEDIDO} pasa por compradora, vendedor y soporte`, async ({ page }) => {
  expect(PEDIDO, 'falta DEMO_PEDIDO: corre la demo con scripts/demos/cu13-real.sh').toBeGreaterThan(0);
  const nota = narrador(page, 'CU-13 · app real');
  const deEstePedido = (panel: ReturnType<Page['locator']>) => panel.locator('ion-list > ion-item', { hasText: `Compra #${PEDIDO} ` });
  const estado = (panel: ReturnType<Page['locator']>) => panel.locator('h2 + p ion-badge');
  await page.goto('/');

  // ---------- 1. La compradora abre la reclamación ----------
  await nota(`Lucía recibió el pedido #${PEDIDO}, pero la lámpara llegó dañada. Abre una reclamación.`);
  let panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await panel.getByRole('button', { name: 'Nueva reclamación' }).click();
  await elegirEnLista(page, panel.locator('ion-select[label="Compra"]'), new RegExp(`Pedido #${PEDIDO} `));
  await elegirEnLista(page, panel.locator('ion-select[label="Producto afectado"]'), PRODUCTO);
  await escribir(panel.getByLabel('Cuéntanos qué pasó'), 'La lámpara llegó con la base rota y no se sostiene.');
  await escribir(panel.getByLabel('Evidencias (una dirección por línea, máximo 5)'), 'https://fotos.example.com/lampara-rota.jpg');
  await panel.getByRole('button', { name: 'Enviar reclamación' }).click();
  await expect(panel.getByRole('status')).toContainText('Reclamación enviada');
  await expect(deEstePedido(panel)).toContainText('Abierta');
  const reclamacion = (await api(page, 'get', '/api/claims') as any[]).find(r => r.orderId === PEDIDO);
  informar('RECLAMACION', reclamacion.id);
  await nota(`La reclamación #${reclamacion.id} quedó guardada y abierta.`);

  await nota('Si intenta abrir otra por el mismo producto, el sistema no la deja duplicar.');
  await panel.getByRole('button', { name: 'Nueva reclamación' }).click();
  await elegirEnLista(page, panel.locator('ion-select[label="Compra"]'), new RegExp(`Pedido #${PEDIDO} `));
  await elegirEnLista(page, panel.locator('ion-select[label="Producto afectado"]'), PRODUCTO);
  await escribir(panel.getByLabel('Cuéntanos qué pasó'), 'Sigue rota.');
  await panel.getByRole('button', { name: 'Enviar reclamación' }).click();
  await expect(panel.getByRole('alert')).toContainText('Ya hay una reclamación abierta para este producto');
  await salir(page);

  // ---------- 2. El vendedor pide información ----------
  await nota('El vendedor de la tienda 1 revisa la reclamación.');
  panel = await abrirReclamaciones(page, CUENTA_DEMO, 'Opciones de mi negocio', 'Reclamaciones');
  await deEstePedido(panel).click();
  await expect(panel.getByText('https://fotos.example.com/lampara-rota.jpg')).toBeVisible();
  await escribir(panel.getByLabel('Mensaje para el comprador'), '¿La caja también llegó golpeada?');
  await panel.getByRole('button', { name: 'Pedir información' }).click();
  await expect(panel.getByRole('status')).toContainText('Información solicitada');
  await expect(estado(panel)).toContainText('Información solicitada');
  await salir(page);

  // ---------- La compradora responde ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await deEstePedido(panel).click();
  await escribir(panel.getByLabel('Escribe un mensaje o el motivo para escalar'), 'Sí, la caja venía aplastada por un lado.');
  await panel.getByRole('button', { name: 'Enviar mensaje' }).click();
  await expect(panel.getByRole('status')).toContainText('Mensaje enviado');
  await expect(estado(panel)).toContainText('Abierta');
  await nota('Lucía responde y la reclamación vuelve a quedar abierta.');
  await salir(page);

  // ---------- 3. El vendedor propone un reembolso ----------
  panel = await abrirReclamaciones(page, CUENTA_DEMO, 'Opciones de mi negocio', 'Reclamaciones');
  await deEstePedido(panel).click();
  await nota('El vendedor intenta ofrecer más de lo que se pagó por la lámpara.');
  await escribir(panel.getByLabel('Mensaje para el comprador'), 'Te devolvemos el dinero.');
  await escribir(panel.getByLabel('Reembolso que ofreces (COP, opcional; máximo lo pagado)'), String(PAGADO + 40000));
  await panel.getByRole('button', { name: 'Proponer solución' }).click();
  await expect(panel.getByRole('alert')).toContainText('El reembolso no puede superar lo pagado por el producto');
  await escribir(panel.getByLabel('Mensaje para el comprador'), 'Te devolvemos 20.000 por el daño y conservas la lámpara.');
  await escribir(panel.getByLabel('Reembolso que ofreces (COP, opcional; máximo lo pagado)'), '20000');
  await panel.getByRole('button', { name: 'Proponer solución' }).click();
  await expect(panel.getByRole('status')).toContainText('Solución propuesta');
  await nota('Propone un reembolso parcial de 20.000.');
  await salir(page);

  // ---------- 4. La compradora escala ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await deEstePedido(panel).click();
  await expect(panel.getByRole('button', { name: 'Aceptar la solución' })).toBeVisible();
  await nota('Lucía no la acepta: la lámpara no sirve. Escala a soporte.');
  await escribir(panel.getByLabel('Escribe un mensaje o el motivo para escalar'), 'La lámpara no se puede usar; pido el reembolso completo.');
  await panel.getByRole('button', { name: 'Escalar a soporte' }).click();
  await expect(estado(panel)).toContainText('Escalada a soporte');
  await salir(page);

  // ---------- 5. Soporte decide ----------
  await nota('Un agente de soporte revisa las reclamaciones escaladas.');
  panel = await abrirReclamaciones(page, SOPORTE, 'Opciones de soporte', 'Reclamaciones escaladas');
  await deEstePedido(panel).click();
  await expect(panel.locator('ion-list ion-item')).toHaveCount(4);
  await nota('Ve todo el historial y concede el reembolso completo.');
  await pausa(page, 2000);
  await escribir(panel.getByLabel('Monto a reembolsar (COP; máximo lo pagado)'), String(PAGADO));
  await escribir(panel.getByLabel('Explica tu decisión'), 'La evidencia muestra el producto dañado en el envío. Se reembolsa completo.');
  await panel.getByRole('button', { name: 'Registrar decisión' }).click();
  await expect(panel.getByRole('status')).toContainText('Decisión registrada');
  await expect(deEstePedido(panel)).toHaveCount(0);
  await elegirEnLista(page, panel.locator('ion-select[label="Mostrar"]'), 'Resueltas');
  await expect(panel.getByText('Resultado: Soporte concedió un reembolso.')).toBeVisible();
  await salir(page);

  // ---------- La compradora ve el resultado ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await expect(deEstePedido(panel)).toContainText('Resuelta');
  await deEstePedido(panel).click();
  await expect(panel.getByText('Resultado: Soporte concedió un reembolso.')).toBeVisible();
  await nota('Lucía ve su reclamación resuelta con el reembolso. Ahora lo vemos en la base de datos.');
  await pausa(page, 3000);
});
