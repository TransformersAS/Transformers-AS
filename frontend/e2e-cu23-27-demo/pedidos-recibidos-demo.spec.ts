import { test, expect } from '@playwright/test';
import {
  VENDEDOR, abrirDesdeMenu, elegirEnLista, escribir, fecha, iniciarSesion, narrador, pausa, simularBackend
} from './apoyo';

/**
 * CU-23: recorrido visual de la tienda que prepara y despacha los pedidos recibidos, en un solo navegador.
 * La API está simulada aquí con estado propio: cada acción cambia lo que se ve después. El proveedor logístico falla la
 * primera vez a propósito para mostrar el reintento del envío. Las reglas reales del backend las prueban las pruebas
 * de pedidos del vendedor (módulo orders).
 */

const NOVEDAD = 'La caja de la camiseta llegó rasgada; se cambia por otra unidad de la bodega.';
const DETALLE_CANCELACION = 'La última unidad se vendió en el punto físico antes de sincronizar el inventario.';

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  const entrega = { recipientName: 'Laura Pérez', street: 'Calle 45 # 13-20, apto 502', city: 'Bogotá',
    department: 'Cundinamarca', postalCode: '110231', phone: '300 555 0142' };
  const pedido = (id: number, total: number, envio: string, lineas: any[]) => ({
    id, status: 'CONFIRMED', paymentStatus: 'APPROVED', total, createdAt: fecha(-3), shippingMethod: envio,
    delivery: entrega, items: lineas, shipment: null as any, openIssues: [] as any[],
    history: [{ fromStatus: null, toStatus: 'CONFIRMED', actorType: 'SYSTEM', actorId: null,
      reason: 'Pago aprobado', correlationId: 'demo', createdAt: fecha(-3) }]
  });
  const pedidos = [
    pedido(2301, 149000, 'STANDARD', [
      { productId: 11, name: 'Camiseta estampada talla M', quantity: 2, unitPrice: 52000, subtotal: 104000,
        currentStock: 18, inventoryConsistent: true },
      { productId: 12, name: 'Gorra bordada', quantity: 1, unitPrice: 35000, subtotal: 35000, currentStock: 7,
        inventoryConsistent: true }]),
    pedido(2302, 209000, 'EXPRESS', [
      { productId: 31, name: 'Audífonos inalámbricos con estuche', quantity: 1, unitPrice: 189000, subtotal: 189000,
        currentStock: 0, inventoryConsistent: true }])
  ];
  let siguienteNovedad = 1;

  const buscar = (id: number) => pedidos.find(p => p.id === id)!;
  const mover = (p: any, a: string, actor: string, motivo: string | null = null) => {
    p.history.push({ fromStatus: p.status, toStatus: a, actorType: actor, actorId: actor === 'SELLER' ? VENDEDOR.accountId : null,
      reason: motivo, correlationId: 'demo', createdAt: fecha() });
    p.status = a;
  };
  const cambio = (p: any) => ({ orderId: p.id, status: p.status, paymentStatus: p.paymentStatus });

  return {
    estado: (id: number) => buscar(id).status,
    guia: (id: number) => buscar(id).shipment?.trackingCode ?? null,

    atender(ruta: string, metodo: string, peticion: any, responder: any) {
      if (ruta === '/api/seller/orders') {
        const params = new URL(peticion.url()).searchParams;
        const estados = params.getAll('status');
        const numero = params.get('orderId');
        const visibles = pedidos
          .filter(p => (estados.length > 0 ? estados : ['CONFIRMED', 'IN_PREPARATION']).includes(p.status))
          .filter(p => !numero || p.id === Number(numero));
        return responder({ page: 0, size: 20, totalElements: visibles.length, totalPages: visibles.length > 0 ? 1 : 0,
          content: visibles.map(p => ({ id: p.id, status: p.status, paymentStatus: p.paymentStatus, total: p.total,
            shippingMethod: p.shippingMethod, itemCount: p.items.length, createdAt: p.createdAt })) });
      }
      const partes = ruta.match(/^\/api\/seller\/orders\/(\d+)(\/.*)?$/);
      if (!partes) return false;
      const p: any = buscar(Number(partes[1]));
      const accion = partes[2] ?? '';

      if (accion === '' && metodo === 'GET') return responder(p);
      if (accion === '/tracking') {
        return responder({ orderId: p.id, status: p.status, ...(p.shipment ? { trackingCode: p.shipment.trackingCode } : {}),
          tracking: true, lastPollFailed: false, events: [] });
      }
      if (accion === '/start-preparation') { mover(p, 'IN_PREPARATION', 'SELLER'); return responder(cambio(p)); }
      if (accion === '/issues') {
        const datos = peticion.postDataJSON();
        const novedad = { id: siguienteNovedad++, type: datos.type, description: datos.description, status: 'OPEN',
          createdAt: fecha() };
        p.openIssues.push(novedad);
        return responder(novedad, 201);
      }
      const resolver = accion.match(/^\/issues\/(\d+)\/resolve$/);
      if (resolver) {
        const novedad = p.openIssues.find((n: any) => n.id === Number(resolver[1]));
        p.openIssues = p.openIssues.filter((n: any) => n !== novedad);
        return responder({ ...novedad, status: 'RESOLVED' });
      }
      if (accion === '/ready-for-dispatch') {
        if (p.openIssues.length > 0) {
          return responder({ status: 409, code: 'OPEN_ISSUES', message: 'El pedido tiene novedades abiertas.' }, 409);
        }
        mover(p, 'READY_FOR_DISPATCH', 'SELLER');
        // El pedido queda listo aunque el proveedor falle (A5): el envío se reintenta a mano.
        return responder({ ...cambio(p), shipment: { status: 'FAILED', message: 'el proveedor logístico no respondió' } });
      }
      if (accion === '/shipment') {
        p.shipment = { shipmentId: `SIM-order-${p.id}`, trackingCode: `TRK-${p.id}`, status: 'CREATED', createdAt: fecha() };
        return responder({ status: 'CREATED', shipmentId: p.shipment.shipmentId, trackingCode: p.shipment.trackingCode }, 201);
      }
      if (accion === '/cancel') {
        const datos = peticion.postDataJSON();
        mover(p, 'CANCELLED', 'SELLER', datos.details);
        p.paymentStatus = 'REFUNDED';
        for (const linea of p.items) linea.currentStock += linea.quantity;
        return responder({ ...cambio(p), refund: { status: 'COMPLETED', message: 'Reembolso completado' } });
      }
      return false;
    }
  };
}

test('CU-23: preparar, registrar una novedad, despachar con reintento del envío y cancelar por falta de stock', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, (ruta, metodo, peticion, responder) => servidor.atender(ruta, metodo, peticion, responder));

  const etapa = narrador(page, 'CU-23');
  const panel = page.locator('app-pedidos-recibidos');
  const aviso = (texto: string) => panel.getByRole('status').filter({ hasText: texto });
  const boton = (nombre: string) => panel.getByRole('button', { name: nombre, exact: true });

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('La tienda consulta los pedidos por atender', async () => {
    await etapa('La tienda inicia sesión y abre «Pedidos recibidos»');
    await iniciarSesion(page, VENDEDOR);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Pedidos recibidos');
    await expect(panel.getByText('Pedido #2301')).toBeVisible();
    await expect(panel.getByText('Pedido #2302')).toBeVisible();
    await etapa('Hay dos pedidos pagados y confirmados, del más antiguo al más reciente');
  });

  await test.step('Revisa el pedido y empieza a prepararlo', async () => {
    await panel.getByText('Pedido #2301').click();
    await expect(panel.getByRole('region', { name: 'Datos de entrega' })).toContainText('Laura Pérez');
    await expect(panel).toContainText('2 × Camiseta estampada talla M');
    await etapa('El detalle trae la entrega, los productos y el stock actual de cada uno');
    await boton('Iniciar preparación').click();
    await expect(aviso('Pedido en preparación.')).toBeVisible();
    expect(servidor.estado(2301)).toBe('IN_PREPARATION');
  });

  await test.step('Registra una novedad: mientras esté abierta no puede despachar', async () => {
    await etapa('Al empacar encuentra un problema y lo deja registrado');
    await boton('Registrar novedad').click();
    const formulario = panel.getByRole('form', { name: 'Registrar novedad' });
    await elegirEnLista(page, formulario.locator('ion-select'), 'Producto dañado');
    await escribir(formulario.locator('textarea'), NOVEDAD);
    await formulario.getByRole('button', { name: 'Guardar novedad', exact: true }).click();
    await expect(aviso('Novedad registrada.')).toBeVisible();
    await expect(panel).toContainText(NOVEDAD);
    await expect(panel.locator('ion-button', { hasText: 'Listo para despacho' })).toHaveAttribute('aria-disabled', 'true');
    await expect(panel.getByText('Resuelve las novedades abiertas antes de dejar el pedido listo.')).toBeVisible();
    await etapa('Con la novedad abierta, «Listo para despacho» queda bloqueado');
    await boton('Resolver').click();
    await expect(aviso('Novedad resuelta.')).toBeVisible();
  });

  await test.step('Deja el pedido listo; el proveedor falla y reintenta el envío', async () => {
    await etapa('Resuelta la novedad, deja el pedido listo para despacho');
    await boton('Listo para despacho').click();
    await expect(aviso('el envío no se pudo crear con el proveedor')).toBeVisible();
    expect(servidor.estado(2301)).toBe('READY_FOR_DISPATCH');
    await expect(panel.getByRole('region', { name: 'Envío', exact: true })).toContainText('Envío pendiente');
    await etapa('El proveedor logístico no respondió: el pedido sigue listo y el envío queda pendiente');
    await boton('Reintentar envío').click();
    await expect(aviso('Envío creado. Guía: TRK-2301.')).toBeVisible();
    expect(servidor.guia(2301)).toBe('TRK-2301');
    await etapa('El reintento crea el envío y entrega la guía; desde aquí lo sigue logística (CU-24)');
    const historial = panel.locator('ion-list').filter({ hasText: 'Historial' });
    await historial.scrollIntoViewIfNeeded();
    await expect(historial).toContainText('Confirmado → En preparación');
    await expect(historial).toContainText('En preparación → Listo para despacho');
    await pausa(page, 2500);
    await boton('Volver').click();
  });

  await test.step('No puede cumplir otro pedido: lo cancela y se reembolsa', async () => {
    await etapa('El otro pedido no se puede cumplir: ya no queda stock');
    await panel.getByText('Pedido #2302').click();
    await expect(panel).toContainText('Stock actual: 0');
    await boton('No puedo cumplir el pedido').click();
    const formulario = panel.getByRole('form', { name: 'Cancelar pedido' });
    await expect(formulario).toContainText('se repone el stock y se solicita el reembolso al comprador');
    await escribir(formulario.locator('textarea'), DETALLE_CANCELACION);
    await formulario.getByRole('button', { name: 'Confirmar cancelación', exact: true }).click();
    await expect(aviso('Pedido cancelado. Reembolso: Reembolso completado')).toBeVisible();
    expect(servidor.estado(2302)).toBe('CANCELLED');
    await expect(panel.getByRole('region', { name: 'Resumen del pedido' })).toContainText('Reembolsado');
    await etapa('Se cancela el pedido completo, se repone el stock y el comprador recibe el reembolso');
    await boton('Volver').click();
  });

  await test.step('La lista refleja el resultado de ambos pedidos', async () => {
    await expect(panel.getByText('No hay pedidos para este filtro.')).toBeVisible();
    await etapa('Ya no queda nada por atender');
    await elegirEnLista(page, panel.locator('.filtros ion-select'), 'Todos');
    await expect(panel.locator('ion-item', { hasText: 'Pedido #2301' })).toContainText('Listo para despacho');
    await expect(panel.locator('ion-item', { hasText: 'Pedido #2302' })).toContainText('Cancelado');
    await etapa('Fin: un pedido quedó listo para despacho con su guía y el otro cancelado con reembolso');
    await pausa(page, 4000);
  });
});
