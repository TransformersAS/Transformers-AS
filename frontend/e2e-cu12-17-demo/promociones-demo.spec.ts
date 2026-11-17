import { test, expect, Locator, Page } from '@playwright/test';
import {
  Cuenta, abrirDesdeMenu, cerrarPanelCuenta, cerrarSesion, elegirEnLista, escribir, iniciarSesion, narrador, pausa,
  simularBackend
} from './apoyo';

/**
 * CU-16: recorrido visual de los cupones de una tienda, del diseño al uso en el checkout, en un solo navegador.
 *  1. El vendedor crea un cupón por porcentaje para dos productos; el sistema rechaza un porcentaje mayor a 100 y el
 *     código reservado DESC10. El cupón nace inactivo y lo activa.
 *  2. Crea otro de monto fijo con compra mínima para toda la tienda y ve el aviso de que se cruza con el primero.
 *  3. La compradora escribe el código en el checkout: el descuento solo cuenta los productos incluidos. Un código que
 *     no existe no descuenta y deja seguir sin él.
 *  4. El vendedor desactiva el cupón y deja de aplicar.
 * La API está simulada aquí con estado propio. Las reglas reales (código único por tienda, fechas, productos de la
 * tienda, conflictos y el descuento que se guarda en el pedido) las prueban SellerPromotionTests y CheckoutCouponTests.
 */

const VENDEDOR: Cuenta = { accountId: 4, email: 'ventas@sonidoandino.co', roles: ['VENDEDOR'] };
const COMPRADORA: Cuenta = { accountId: 2, email: 'lucia.compras@example.com', roles: ['COMPRADOR'] };

const PARLANTE = { id: 31, name: 'Parlante Bluetooth Andes', price: 180000 };
const AUDIFONOS = { id: 32, name: 'Audífonos Páramo', price: 120000 };
const CABLE = { id: 33, name: 'Cable auxiliar 3,5 mm', price: 12000 };
const PRODUCTOS = [PARLANTE, AUDIFONOS, CABLE];
const ENVIO = 10000;

/** Fecha local sin zona, como la guarda el backend (LocalDateTime). */
const local = (d: Date) => {
  const n = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:00`;
};

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  const cupones: any[] = [];
  const carrito = new Map<number, number>();
  const vistasPrevias: { couponCode: string; discount: number; total: number }[] = [];

  const vigente = (c: any) => c.active && new Date(c.startsAt) <= new Date() && new Date() < new Date(c.endsAt);
  const estado = (c: any) => !c.active ? 'INACTIVE' : new Date() < new Date(c.startsAt) ? 'SCHEDULED'
    : new Date() >= new Date(c.endsAt) ? 'EXPIRED' : 'RUNNING';
  const cruzan = (a: any, b: any) => new Date(a.startsAt) < new Date(b.endsAt) && new Date(b.startsAt) < new Date(a.endsAt)
    && (a.productIds.length === 0 || b.productIds.length === 0 || a.productIds.some((id: number) => b.productIds.includes(id)));
  const respuesta = (c: any) => ({ ...c, status: estado(c),
    conflicts: cupones.filter(o => o !== c && o.active && cruzan(c, o)).map(o => `${o.name} (${o.code})`) });

  /** Las mismas validaciones que SellerPromotionService; devuelve el mensaje del primer error. */
  const validar = (d: any, id: number | null) => {
    const codigo = String(d.code ?? '').trim().toUpperCase();
    if (codigo === 'DESC10') return 'El código DESC10 está reservado';
    if (cupones.some(c => c.code === codigo && c.id !== id)) return `Ya tienes otro cupón con el código ${codigo}`;
    if (d.discountType === 'PERCENT' && Number(d.discountValue) > 100) return 'El porcentaje no puede ser mayor que 100';
    if (new Date(d.endsAt) <= new Date(d.startsAt)) return 'La fecha de fin debe ser posterior a la de inicio';
    return null;
  };

  const lineas = () => [...carrito].map(([id, cantidad]) => {
    const p = PRODUCTOS.find(x => x.id === id)!;
    return { id, productId: id, productName: p.name, quantity: cantidad, unitPrice: p.price, subtotal: p.price * cantidad };
  });

  return {
    cupones, carrito, vistasPrevias,
    atender: (ruta: string, metodo: string, peticion: any, responder: (cuerpo: unknown, estado?: number) => Promise<void>,
      route: any) => {
      // ---------- Vendedor: productos y cupones ----------
      if (ruta === '/api/seller/products') {
        return responder(PRODUCTOS.map(p => ({ ...p, description: null, stock: 20, category: 'Sonido', brandId: null,
          status: 'ACTIVE', imageUrls: [], attributeValueIds: [], variants: [], unitCost: null })));
      }
      if (ruta === '/api/seller/promotions' && metodo === 'GET') return responder([...cupones].reverse().map(respuesta));
      if (ruta === '/api/seller/promotions' && metodo === 'POST') {
        const d = peticion.postDataJSON();
        const error = validar(d, null);
        if (error) return responder({ message: error }, error.includes('código') ? 409 : 400);
        const c = { id: 70 + cupones.length, name: d.name.trim(), code: d.code.trim().toUpperCase(),
          discountType: d.discountType, discountValue: Number(d.discountValue),
          minPurchase: d.minPurchase ? Number(d.minPurchase) : null, startsAt: local(new Date(d.startsAt)),
          endsAt: local(new Date(d.endsAt)), active: false, productIds: [...d.productIds].sort() };
        cupones.push(c);
        return responder(respuesta(c), 201);
      }
      const accion = ruta.match(/^\/api\/seller\/promotions\/(\d+)\/(activate|deactivate)$/);
      if (accion && metodo === 'POST') {
        const c = cupones.find(x => x.id === Number(accion[1]));
        c.active = accion[2] === 'activate';
        return responder(respuesta(c));
      }

      // ---------- Compradora: catálogo, carrito y checkout ----------
      if (ruta === '/api/products') {
        return responder(PRODUCTOS.map(p => ({ ...p, description: 'Tienda Sonido Andino', category: 'Sonido', stock: 20,
          active: true, storeId: 1, imageUrls: [] })));
      }
      if (ruta === '/api/cart/items' && metodo === 'POST') {
        const { productId, quantity } = peticion.postDataJSON();
        carrito.set(productId, (carrito.get(productId) ?? 0) + quantity);
        return responder({}, 201);
      }
      if (ruta === '/api/cart') {
        const items = lineas();
        return responder({ cartId: 1, items, total: items.reduce((t, i) => t + i.subtotal, 0) });
      }
      if (ruta === '/api/addresses') {
        return responder([{ id: 8, recipientName: 'Lucía Martínez', street: 'Calle 45 # 12-30', city: 'Bogotá',
          department: 'Bogotá D.C.', postalCode: '110111', phone: '3001234567', defaultAddress: true }]);
      }
      if (ruta === '/api/checkout/preview') {
        // Lo que hace CheckoutCouponAspect: el cupón del vendedor descuenta solo sobre los productos que incluye.
        const codigo = String(peticion.postDataJSON().couponCode ?? '').trim().toUpperCase();
        const items = lineas();
        const subtotal = items.reduce((t, i) => t + i.subtotal, 0);
        const c = cupones.find(x => x.code === codigo && vigente(x));
        const aplica = c ? items.filter(i => c.productIds.length === 0 || c.productIds.includes(i.productId))
          .reduce((t, i) => t + i.subtotal, 0) : 0;
        const valido = !!c && aplica > 0 && (!c.minPurchase || aplica >= c.minPurchase);
        const descuento = !valido ? 0 : c.discountType === 'PERCENT'
          ? Math.round(aplica * c.discountValue / 100) : Math.min(c.discountValue, aplica);
        const total = subtotal - descuento + ENVIO;
        vistasPrevias.push({ couponCode: codigo, discount: descuento, total });
        return responder({ subtotal, discount: descuento, shippingCost: ENVIO, total, couponValid: valido });
      }
      return false;
    }
  };
}

const pesos = (valor: number) => '$' + valor.toLocaleString('en-US');

/** Llena el formulario de cupón abierto. */
async function llenarCupon(page: Page, panel: Locator, datos: {
  nombre: string; codigo: string; tipo: 'Porcentaje' | 'Monto fijo'; valor: string; minimo?: string; productos?: string[];
}) {
  await escribir(panel.getByLabel('Nombre'), datos.nombre);
  await escribir(panel.getByLabel('Código que escribe el comprador'), datos.codigo);
  if (datos.tipo === 'Monto fijo') await elegirEnLista(page, panel.locator('ion-select[label="Tipo de descuento"]'), 'Monto fijo');
  const valor = panel.getByLabel(datos.tipo === 'Porcentaje' ? 'Porcentaje (1 a 100)' : 'Monto a descontar (COP)');
  await escribir(valor, datos.valor);
  if (datos.minimo) await escribir(panel.getByLabel('Compra mínima (opcional, COP)'), datos.minimo);
  for (const producto of datos.productos ?? []) {
    await panel.locator('ion-checkbox', { hasText: producto }).click();
  }
}

async function abrirCupones(page: Page) {
  await iniciarSesion(page, VENDEDOR.email);
  await cerrarPanelCuenta(page);
  await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Promociones y cupones');
  const panel = page.locator('app-promociones');
  await expect(panel).toBeVisible();
  return panel;
}

/** Desde el carrito lleno, abre el checkout con la dirección por defecto y calcula el total con el cupón. */
async function calcularConCupon(page: Page, codigo: string) {
  const cupon = page.getByPlaceholder('Ej. DESC10');
  await cupon.fill('');
  await escribir(cupon, codigo);
  await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
  const resumen = page.locator('.checkout-summary');
  await expect(resumen).toBeVisible();
  return resumen;
}

test('CU-16: el vendedor diseña cupones y la compradora los usa en el checkout', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, [VENDEDOR, COMPRADORA], servidor.atender);
  const nota = narrador(page, 'CU-16');
  await page.goto('/');

  // ---------- 1. Cupón por porcentaje ----------
  await nota('El vendedor de Sonido Andino prepara los cupones de la temporada navideña.');
  let panel = await abrirCupones(page);
  await expect(panel.getByText('Aún no has creado cupones.')).toBeVisible();
  await panel.getByRole('button', { name: 'Nuevo cupón' }).click();
  await nota('Diseña un cupón por porcentaje solo para el parlante y los audífonos. Por defecto dura un mes desde hoy.');
  await llenarCupon(page, panel, { nombre: 'Navidad sonora', codigo: 'navidad20', tipo: 'Porcentaje', valor: '150',
    productos: [PARLANTE.name, AUDIFONOS.name] });
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('alert')).toContainText('El porcentaje no puede ser mayor que 100');
  await nota('Se equivocó y escribió 150 %: el sistema no lo deja guardar.');

  await escribir(panel.getByLabel('Porcentaje (1 a 100)'), '20');
  await escribir(panel.getByLabel('Código que escribe el comprador'), 'DESC10');
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('alert')).toContainText('El código DESC10 está reservado');
  await nota('DESC10 es el cupón de prueba del checkout del marketplace: está reservado.');

  await escribir(panel.getByLabel('Código que escribe el comprador'), 'navidad20');
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón creado');
  const navidad = panel.locator('ion-list > ion-item', { hasText: 'NAVIDAD20' });
  await expect(navidad).toContainText('Inactivo');
  await expect(navidad).toContainText('20 % de descuento');
  await expect(navidad).toContainText('2 producto(s)');
  await nota('El código queda en mayúsculas y el cupón nace inactivo: nadie lo puede usar hasta que el vendedor lo active.');
  await navidad.getByRole('button', { name: 'Activar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón activado');
  await expect(navidad).toContainText('Vigente');

  // ---------- 2. Cupón de monto fijo que se cruza ----------
  await panel.getByRole('button', { name: 'Nuevo cupón' }).click();
  await nota('Ahora crea un descuento fijo de 15.000 para toda la tienda, con compra mínima de 100.000.');
  await llenarCupon(page, panel, { nombre: 'Bono fin de año', codigo: 'BONO15K', tipo: 'Monto fijo', valor: '15000',
    minimo: '100000' });
  await panel.getByRole('button', { name: 'Guardar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón creado');
  const bono = panel.locator('ion-list > ion-item', { hasText: 'BONO15K' });
  await expect(bono).toContainText('Toda la tienda');
  await expect(bono).toContainText('Se cruza con: Navidad sonora (NAVIDAD20)');
  await nota('El sistema le avisa que se cruza en fechas y productos con NAVIDAD20. Es un aviso: el comprador solo usa el código que escribe.');
  await pausa(page, 2500);
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- 3. La compradora usa el cupón ----------
  await nota('Lucía compra unos audífonos y un cable.');
  await iniciarSesion(page, COMPRADORA.email);
  await cerrarPanelCuenta(page);
  const catalogo = page.locator('#catalogo');
  await catalogo.scrollIntoViewIfNeeded();
  for (const producto of [AUDIFONOS, CABLE]) {
    await catalogo.locator('.product-card').filter({ hasText: producto.name }).getByRole('button', { name: /Agregar/ }).click();
    await expect.poll(() => servidor.carrito.has(producto.id)).toBe(true);
  }
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  await page.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true }).click();
  await expect(page.getByRole('radio', { name: /Usar esta/ })).toBeChecked();

  await nota('Escribe el cupón en minúsculas, como se lo dijeron.');
  let resumen = await calcularConCupon(page, 'navidad20');
  await expect(resumen.getByText('✓ Cupón aplicado correctamente.')).toBeVisible();
  const subtotal = AUDIFONOS.price + CABLE.price;
  const descuento = AUDIFONOS.price * 0.2;
  await expect(resumen).toContainText(pesos(subtotal));
  await expect(resumen).toContainText(pesos(descuento));
  await expect(resumen.locator('.checkout-total')).toContainText(pesos(subtotal - descuento + ENVIO));
  await nota('El 20 % se aplica solo a los audífonos (24.000): el cable no está en el cupón. Total: 118.000 con envío.');
  await pausa(page, 3000);

  await nota('Si escribe un código que no existe, no descuenta nada y puede seguir comprando sin él.');
  resumen = await calcularConCupon(page, 'REGALO50');
  await expect(resumen.getByText('El cupón no es válido.')).toBeVisible();
  await expect(resumen.locator('.checkout-total')).toContainText(pesos(subtotal + ENVIO));
  await pausa(page, 2000);
  await page.reload();
  await cerrarSesion(page);

  // ---------- 4. El vendedor desactiva el cupón ----------
  await nota('Terminó la promoción: el vendedor desactiva NAVIDAD20.');
  panel = await abrirCupones(page);
  await panel.locator('ion-list > ion-item', { hasText: 'NAVIDAD20' }).getByRole('button', { name: 'Desactivar' }).click();
  await expect(panel.getByRole('status')).toContainText('Cupón desactivado');
  await expect(panel.locator('ion-list > ion-item', { hasText: 'NAVIDAD20' })).toContainText('Inactivo');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  await nota('Lucía vuelve a intentarlo y el cupón ya no aplica.');
  await iniciarSesion(page, COMPRADORA.email);
  await cerrarPanelCuenta(page);
  await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
  await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
  await page.getByRole('button', { name: 'Elegir o cambiar dirección', exact: true }).click();
  resumen = await calcularConCupon(page, 'NAVIDAD20');
  await expect(resumen.getByText('El cupón no es válido.')).toBeVisible();
  await expect(resumen.locator('.checkout-total')).toContainText(pesos(subtotal + ENVIO));

  expect(servidor.vistasPrevias.map(v => [v.couponCode, v.discount])).toEqual([
    ['NAVIDAD20', 24000], ['REGALO50', 0], ['NAVIDAD20', 0]]);
  await nota('Fin: el vendedor controla cuándo se puede usar cada cupón y el checkout lo respeta.');
  await pausa(page, 2500);
});
