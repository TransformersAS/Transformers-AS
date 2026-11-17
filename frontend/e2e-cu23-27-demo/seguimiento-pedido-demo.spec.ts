import { test, expect } from '@playwright/test';
import {
  COMPRADOR, TIENDA, VENDEDOR, abrirDesdeMenu, cerrarSesion, elegirEnLista, fecha, iniciarSesion, narrador, pausa,
  simularBackend
} from './apoyo';

/**
 * CU-24: recorrido visual del seguimiento logístico de un pedido, del despacho a la entrega, en un solo navegador.
 * La API está simulada aquí con estado propio. Lo que en el sistema real informa el proveedor logístico (por webhook o
 * cuando el marketplace lo consulta) se simula entre etapas. Las reglas reales (idempotencia, orden de los eventos y
 * cambio de estado del pedido) las prueban las pruebas de seguimiento del módulo logistics.
 */

const PEDIDO = 2401;
const GUIA = 'TRK-2401';
const PRODUCTO = { id: 31, nombre: 'Audífonos inalámbricos con estuche', precio: 189000 };

/** Orden de los estados de transporte: un evento anterior al estado vigente no lo hace retroceder. */
const ORDEN: Record<string, number> = {
  READY_FOR_DISPATCH: 0, PICKED_UP: 1, IN_TRANSIT: 2, DELIVERY_ATTEMPT_FAILED: 3, DELIVERED: 4
};

interface Novedad { tipo: string; horas: number; descripcion: string; lugar: string | null; evidencia?: string }

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let estado = 'READY_FOR_DISPATCH';
  let entregadoEn: string | null = null;
  let evidencia: { type: string; reference: string } | null = null;
  let consultaFallida = false;
  let proveedorCaido = false;
  let recienConsultado = false;
  const eventos: any[] = [];
  const pendientesEnProveedor: Novedad[] = [];
  const historial: any[] = [
    { fromStatus: null, toStatus: 'CONFIRMED', actorType: 'SYSTEM', actorId: null, reason: 'Pago aprobado',
      correlationId: 'demo', createdAt: fecha(-30) },
    { fromStatus: 'CONFIRMED', toStatus: 'IN_PREPARATION', actorType: 'SELLER', actorId: VENDEDOR.accountId, reason: null,
      correlationId: 'demo', createdAt: fecha(-28) },
    { fromStatus: 'IN_PREPARATION', toStatus: 'READY_FOR_DISPATCH', actorType: 'SELLER', actorId: VENDEDOR.accountId,
      reason: null, correlationId: 'demo', createdAt: fecha(-26) }
  ];

  /** Registra un evento del proveedor y, si corresponde, mueve el estado del pedido. */
  const registrar = (novedad: Novedad, origen: 'WEBHOOK' | 'POLLING') => {
    let resultado = 'RECORDED';
    if (novedad.tipo in ORDEN) {
      if (ORDEN[novedad.tipo] > ORDEN[estado]) {
        resultado = 'APPLIED';
        historial.push({ fromStatus: estado, toStatus: novedad.tipo, actorType: 'LOGISTICS', actorId: null,
          reason: novedad.descripcion, correlationId: 'demo', createdAt: fecha() });
        estado = novedad.tipo;
      } else if (ORDEN[novedad.tipo] < ORDEN[estado]) {
        resultado = 'OUT_OF_ORDER';
      }
    }
    const prueba = novedad.evidencia ? { type: 'SIGNATURE', reference: novedad.evidencia } : null;
    if (novedad.tipo === 'DELIVERED' && resultado === 'APPLIED') {
      entregadoEn = fecha(novedad.horas);
      evidencia = prueba;
    }
    eventos.push({ id: eventos.length + 1, type: novedad.tipo, occurredAt: fecha(novedad.horas), receivedAt: fecha(),
      source: origen, outcome: resultado, description: novedad.descripcion, location: novedad.lugar, evidence: prueba });
  };

  const seguimiento = (consulta?: string) => ({
    orderId: PEDIDO, status: estado, trackingCode: GUIA, tracking: estado !== 'DELIVERED', lastPollFailed: consultaFallida,
    lastPolledAt: fecha(), ...(consulta ? { refresh: consulta } : {}),
    ...(entregadoEn ? { deliveredAt: entregadoEn } : {}), ...(evidencia ? { deliveryEvidence: evidencia } : {}),
    events: [...eventos].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))
  });

  /** «Actualizar seguimiento»: el marketplace consulta al proveedor, sin saturarlo ni perder lo que ya sabía. */
  const consultarProveedor = () => {
    if (estado === 'DELIVERED') return seguimiento('NOT_TRACKED');
    if (recienConsultado) return seguimiento('THROTTLED');
    if (proveedorCaido) { consultaFallida = true; return seguimiento('UNAVAILABLE'); }
    consultaFallida = false;
    recienConsultado = true;
    const nuevas = pendientesEnProveedor.splice(0);
    nuevas.forEach(novedad => registrar(novedad, 'POLLING'));
    return seguimiento(nuevas.length > 0 ? 'UPDATED' : 'NO_CHANGES');
  };

  const pedidoComprador = () => ({
    id: PEDIDO, status: estado, total: PRODUCTO.precio + 12000, shippingMethod: 'STANDARD', createdAt: fecha(-30),
    paymentStatus: 'APPROVED', sellerNameSnapshot: TIENDA, discountAmount: 0, shippingCost: 12000, history: [], refunds: [],
    items: [{ productId: PRODUCTO.id, productName: PRODUCTO.nombre, quantity: 1, unitPrice: PRODUCTO.precio,
      subtotal: PRODUCTO.precio }]
  });
  const pedidoVendedor = () => ({
    id: PEDIDO, status: estado, paymentStatus: 'APPROVED', total: PRODUCTO.precio + 12000, createdAt: fecha(-30),
    shippingMethod: 'STANDARD', openIssues: [], history: historial,
    delivery: { recipientName: 'Laura Pérez', street: 'Calle 45 # 13-20, apto 502', city: 'Bogotá',
      department: 'Cundinamarca', postalCode: '110231', phone: '300 555 0142' },
    items: [{ productId: PRODUCTO.id, name: PRODUCTO.nombre, quantity: 1, unitPrice: PRODUCTO.precio,
      subtotal: PRODUCTO.precio, currentStock: 6, inventoryConsistent: true }],
    shipment: { shipmentId: `SIM-order-${PEDIDO}`, trackingCode: GUIA, status: 'CREATED', createdAt: fecha(-26) }
  });

  return {
    /** El proveedor avisa al marketplace por webhook: queda registrado de inmediato. */
    avisaPorWebhook: (novedad: Novedad) => registrar(novedad, 'WEBHOOK'),
    /** El proveedor tiene una novedad que todavía no ha enviado: aparece cuando el marketplace lo consulta. */
    guardaEnProveedor: (novedad: Novedad) => pendientesEnProveedor.push(novedad),
    seCaeElProveedor: () => { proveedorCaido = true; },
    vuelveElProveedor: () => { proveedorCaido = false; },
    pasaElTiempo: () => { recienConsultado = false; },
    estado: () => estado,

    atender(ruta: string, metodo: string, peticion: any, responder: any) {
      if (ruta === '/api/orders') return responder([pedidoComprador()]);
      if (ruta === `/api/orders/${PEDIDO}`) return responder(pedidoComprador());
      if (ruta === '/api/seller/orders') {
        const estados = new URL(peticion.url()).searchParams.getAll('status');
        const visible = (estados.length > 0 ? estados : ['CONFIRMED', 'IN_PREPARATION']).includes(estado);
        return responder({ page: 0, size: 20, totalElements: visible ? 1 : 0, totalPages: visible ? 1 : 0,
          content: visible ? [{ id: PEDIDO, status: estado, paymentStatus: 'APPROVED', total: PRODUCTO.precio + 12000,
            shippingMethod: 'STANDARD', itemCount: 1, createdAt: fecha(-30) }] : [] });
      }
      if (ruta === `/api/seller/orders/${PEDIDO}`) return responder(pedidoVendedor());
      if (/^\/api\/(seller\/)?orders\/2401\/tracking$/.test(ruta)) return responder(seguimiento());
      if (/^\/api\/(seller\/)?orders\/2401\/tracking\/refresh$/.test(ruta) && metodo === 'POST') {
        return responder(consultarProveedor());
      }
      return false;
    }
  };
}

test('CU-24: seguir un pedido del despacho a la entrega, con novedades, proveedor caído y eventos fuera de orden', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, (ruta, metodo, peticion, responder) => servidor.atender(ruta, metodo, peticion, responder));

  const etapa = narrador(page, 'CU-24');
  const misPedidos = page.getByRole('dialog', { name: 'Mis pedidos' });
  const recibidos = page.locator('app-pedidos-recibidos');
  const envio = page.getByRole('region', { name: 'Seguimiento del envío' });
  const linea = envio.locator('ol.linea');
  const actualizar = envio.getByRole('button', { name: 'Actualizar seguimiento', exact: true });
  const aviso = (texto: string) => envio.getByRole('status').filter({ hasText: texto });

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('El comprador abre el seguimiento de su pedido despachado', async () => {
    await etapa('El comprador inicia sesión y abre el detalle de su pedido');
    await iniciarSesion(page, COMPRADOR);
    await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
    await misPedidos.getByRole('button', { name: `Ver detalle del pedido ${PEDIDO}` }).click();
    await envio.scrollIntoViewIfNeeded();
    await expect(envio).toContainText(`Listo para despacho · Guía ${GUIA}`);
    await expect(envio).toContainText('Todavía no hay actualizaciones de transporte.');
    await etapa('La tienda ya lo despachó: hay guía, pero el transportador aún no informa nada');
  });

  await test.step('El transportador recoge el paquete y la pantalla se actualiza sola', async () => {
    servidor.avisaPorWebhook({ tipo: 'PICKED_UP', horas: -5, descripcion: 'Paquete recogido en la tienda', lugar: 'Bogotá' });
    await etapa('El transportador avisa que recogió el paquete; la página relee cada 10 segundos');
    await expect(linea).toContainText('Recogido', { timeout: 20000 });
    await expect(envio).toContainText(`Recogido · Guía ${GUIA}`);
    expect(servidor.estado()).toBe('PICKED_UP');
    await etapa('Nadie cambió el estado a mano: lo informó el servicio logístico');
  });

  await test.step('«Actualizar seguimiento» consulta al transportador, sin saturarlo', async () => {
    servidor.guardaEnProveedor({ tipo: 'IN_TRANSIT', horas: -4, descripcion: 'En ruta hacia el centro de distribución',
      lugar: 'Bogotá' });
    await etapa('El comprador no quiere esperar y pide actualizar el seguimiento');
    await actualizar.click();
    await expect(aviso('Seguimiento actualizado.')).toBeVisible();
    await expect(envio).toContainText(`En camino · Guía ${GUIA}`);
    await actualizar.click();
    await expect(aviso('Acabas de actualizar. Espera unos segundos antes de volver a consultar.')).toBeVisible();
    await etapa('Si insiste enseguida, el sistema le pide esperar para no saturar al transportador');
    servidor.pasaElTiempo();
  });

  await test.step('Si el transportador no responde, se conserva el último seguimiento conocido', async () => {
    servidor.seCaeElProveedor();
    await etapa('El servicio logístico se cae: ¿qué ve el comprador?');
    await actualizar.click();
    await expect(aviso('El servicio logístico no responde ahora. Se muestra el último seguimiento conocido.')).toBeVisible();
    await expect(envio).toContainText(`En camino · Guía ${GUIA}`);
    await expect(linea.locator('li')).toHaveCount(2);
    await expect(envio).toContainText('El servicio logístico no respondió la última vez');
    await etapa('Sigue viendo «En camino» y los dos eventos que ya tenía: no se pierde nada');
    servidor.vuelveElProveedor();
    await actualizar.click();
    await expect(aviso('El servicio logístico no informó novedades.')).toBeVisible();
    await expect(envio).not.toContainText('El servicio logístico no respondió la última vez');
    await etapa('Cuando el servicio vuelve, la consulta funciona de nuevo: por ahora no hay novedades');
    servidor.pasaElTiempo();
  });

  await test.step('Un intento de entrega fallido y un aviso que llega tarde', async () => {
    servidor.avisaPorWebhook({ tipo: 'DELIVERY_ATTEMPT_FAILED', horas: -2, descripcion: 'No había quien recibiera',
      lugar: 'Bogotá, Chapinero' });
    servidor.avisaPorWebhook({ tipo: 'NEXT_ATTEMPT_SCHEDULED', horas: -1.9, descripcion: 'Nuevo intento mañana en la mañana',
      lugar: null });
    servidor.avisaPorWebhook({ tipo: 'PICKED_UP', horas: -4.9, descripcion: 'Confirmación de recogida (reenviada)',
      lugar: 'Bogotá' });
    await etapa('El transportador no encuentra a nadie, programa otro intento y reenvía un aviso viejo');
    await expect(envio).toContainText(`Intento de entrega fallido · Guía ${GUIA}`, { timeout: 20000 });
    await expect(linea).toContainText('Nuevo intento programado');
    await expect(linea).toContainText('Información adicional');
    await expect(linea).toContainText('Recibida fuera de orden: no cambió el estado');
    expect(servidor.estado()).toBe('DELIVERY_ATTEMPT_FAILED');
    await etapa('El aviso viejo queda en la línea de tiempo, pero el pedido no retrocede a «Recogido»');
  });

  await test.step('El pedido se entrega y el seguimiento termina', async () => {
    servidor.avisaPorWebhook({ tipo: 'DELIVERED', horas: 0, descripcion: 'Entregado al destinatario', lugar: 'Bogotá, Chapinero',
      evidencia: 'Firma de Laura Pérez' });
    await etapa('Al día siguiente el transportador entrega el paquete');
    await expect(envio).toContainText(`Entregado · Guía ${GUIA}`, { timeout: 20000 });
    await expect(envio).toContainText('Confirmación: Firma de Laura Pérez');
    await expect(envio).toContainText('El seguimiento finalizó: ya no se consulta al servicio logístico.');
    await expect(actualizar).toBeHidden();
    await etapa('Queda la fecha y la confirmación de entrega; ya no se consulta más al transportador');
    await pausa(page, 2500);
    await misPedidos.getByRole('button', { name: 'Cerrar pedidos', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La tienda ve el mismo seguimiento y el historial del pedido', async () => {
    await etapa('La tienda inicia sesión y busca el pedido entre los entregados');
    await iniciarSesion(page, VENDEDOR);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Pedidos recibidos');
    await elegirEnLista(page, recibidos.locator('.filtros ion-select'), 'Entregados');
    await recibidos.getByText(`Pedido #${PEDIDO}`).click();
    await envio.scrollIntoViewIfNeeded();
    await expect(envio).toContainText(`Entregado · Guía ${GUIA}`);
    await expect(linea.locator('li')).toHaveCount(6);
    const historial = recibidos.locator('ion-list').filter({ hasText: 'Historial' });
    await historial.scrollIntoViewIfNeeded();
    await expect(historial).toContainText('Listo para despacho → Recogido');
    await expect(historial).toContainText('Intento de entrega fallido → Entregado');
    await expect(historial).toContainText('Logística');
    await etapa('Fin: la tienda ve la misma línea de tiempo y, en el historial, que cada cambio lo hizo Logística');
    await pausa(page, 4000);
  });
});
