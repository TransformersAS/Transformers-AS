import { test, expect } from '@playwright/test';
import { cerrarPanelCuenta, escribir, iniciarSesion, narrador, pausa } from '../e2e-cu12-17-demo/apoyo';
import { CUENTA_DEMO, api, informar, usarRol } from './real';

/**
 * CU-16 contra la aplicación REAL (frontend, backend y MySQL del Docker local), sin nada simulado. Lo que crea queda
 * guardado en la base: el cupón en `promotions`, activo. El pago real se hace en Mercado Pago, que es externo, así que la
 * demo termina en el total con descuento que calcula el backend real.
 * La cuenta demo@marketplace.local es la cuenta pública de demostración del perfil local: vendedora de la tienda 1 y
 * compradora a la vez.
 */

const CODIGO = process.env['DEMO_CODIGO'] ?? 'CLASE' + Date.now().toString().slice(-5);
const PORCENTAJE = 20;

test(`CU-16 real: el cupón ${CODIGO} se crea, se activa y descuenta en el checkout real`, async ({ page }) => {
  const nota = narrador(page, 'CU-16 · app real');
  await page.goto('/');

  // ---------- Vendedor: crear y activar el cupón ----------
  await nota('Entramos a la aplicación real con la cuenta de la tienda 1.');
  await iniciarSesion(page, CUENTA_DEMO);
  await cerrarPanelCuenta(page);
  await usarRol(page, 'VENDEDOR');
  await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
  await page.getByRole('button', { name: 'Promociones y cupones', exact: true }).click();
  const panel = page.locator('app-promociones');
  await expect(panel.getByRole('button', { name: 'Nuevo cupón' })).toBeVisible();

  await nota(`Creamos el cupón ${CODIGO}: ${PORCENTAJE} % para toda la tienda, por un mes.`);
  await panel.getByRole('button', { name: 'Nuevo cupón' }).click();
  await escribir(panel.getByLabel('Nombre'), `Demo en clase ${CODIGO}`);
  await escribir(panel.getByLabel('Código que escribe el comprador'), CODIGO.toLowerCase());
  await escribir(panel.getByLabel('Porcentaje (1 a 100)'), String(PORCENTAJE));
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón creado');
  // Los cupones de corridas anteriores siguen en la base y pueden nombrar este en su aviso de cruce: se busca por el
  // código exacto del título.
  const cupon = panel.locator('ion-list > ion-item').filter({
    has: page.locator('h2 strong', { hasText: new RegExp(`^${CODIGO}$`) }) });
  await expect(cupon).toContainText('Inactivo');
  await nota('Quedó guardado en MySQL, inactivo. Lo activamos.');
  await cupon.getByRole('button', { name: 'Activar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón activado');
  await expect(cupon).toContainText('Vigente');
  await pausa(page, 1500);
  await panel.getByRole('button', { name: 'Cerrar' }).click();

  // ---------- Compradora: el cupón en el checkout ----------
  await nota('Ahora la misma cuenta compra como compradora.');
  await usarRol(page, 'COMPRADOR');
  const carrito = await api(page, 'get', '/api/cart');
  for (const item of carrito?.items ?? []) await api(page, 'delete', `/api/cart/items/${item.id}`);
  const catalogo = await api(page, 'get', '/api/products');
  const producto = (catalogo as any[]).find(p => (p.storeId ?? 1) === 1 && p.active && p.stock > 0);
  expect(producto, 'no hay productos de la tienda 1 con stock').toBeTruthy();
  await api(page, 'post', '/api/cart/items', { productId: producto.id, quantity: 1 });
  const direcciones = await api(page, 'get', '/api/addresses');
  if (!direcciones?.length) {
    await api(page, 'post', '/api/addresses', { recipientName: 'Demo Local', street: 'Calle 1 # 2-3', city: 'Bogotá',
      department: 'Cundinamarca', postalCode: '110111', phone: '+57 300 123 4567' });
  }

  await page.reload();
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await expect(page.getByText(producto.name).first()).toBeVisible();
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  // Las versiones más nuevas esconden la lista de direcciones detrás de un botón; las anteriores la muestran directo.
  const elegirDireccion = page.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true });
  await expect(page.getByRole('heading', { name: 'Cupón' })).toBeVisible();
  if (await elegirDireccion.isVisible()) await elegirDireccion.click();
  await expect(page.getByRole('radio', { name: /Usar esta/ }).first()).toBeChecked();
  await nota(`Escribe ${CODIGO.toLowerCase()} en el cupón y calcula el total.`);
  await escribir(page.getByPlaceholder('Ej. DESC10'), CODIGO.toLowerCase());
  await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
  const resumen = page.locator('.checkout-summary');
  await expect(resumen.getByText('✓ Cupón aplicado correctamente.')).toBeVisible();
  await nota(`El backend real aplicó el ${PORCENTAJE} % sobre ${producto.name}.`);
  await pausa(page, 3000);

  // El botón de pago abre Mercado Pago, que es externo; la app real no acepta otro medio de pago, así que la demo
  // termina en el total calculado por el backend real. El total también se lee del backend para mostrarlo al final.
  const vista = await api(page, 'post', '/api/checkout/preview', { addressId: (await api(page, 'get', '/api/addresses'))[0].id,
    shippingMethod: 'STANDARD', couponCode: CODIGO.toLowerCase() });
  expect(vista.couponValid).toBe(true);
  expect(Number(vista.discount)).toBeCloseTo(Number(vista.subtotal) * PORCENTAJE / 100, 2);
  informar('CHECKOUT', `subtotal ${vista.subtotal} · descuento ${vista.discount} · envío ${vista.shippingCost} · total ${vista.total}`);
  await nota('El pago seguiría en Mercado Pago. Ahora vemos el cupón guardado en la base de datos.');
  await pausa(page, 2500);
});
