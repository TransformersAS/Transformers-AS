import { test, expect } from '@playwright/test';
import {
  COMPRADOR, TIENDA, VENDEDOR, abrirDesdeMenu, cerrarSesion, fecha, iniciarSesion, narrador, pausa, simularBackend
} from './apoyo';

/**
 * CU-25: recorrido visual del seguimiento logístico de una devolución, de la recogida a la entrega en la tienda, en un
 * solo navegador. La API está simulada aquí con estado propio; lo que en el sistema real informa el proveedor logístico
 * se simula entre etapas. Las reglas reales (transiciones, tope de tres recogidas fallidas y aviso a devoluciones) las
 * prueban las pruebas de seguimiento de devoluciones del módulo logistics.
 */

const MAXIMO_RECOGIDAS_FALLIDAS = 3;

interface Novedad { tipo: string; horas: number; descripcion: string; lugar: string | null; evidencia?: string }

/** Una devolución en camino a la tienda, como la guarda el backend en return_shipments. */
function crearDevolucion(id: number, recogidasFallidas = 0) {
  let estado = recogidasFallidas > 0 ? 'PICKUP_FAILED' : 'PICKUP_PENDING';
  let detenida = false;
  let entregadaEn: string | null = null;
  const eventos: any[] = [];
  const anotar = (novedad: Novedad, origen: string, resultado: string) => eventos.push({
    id: eventos.length + 1, type: novedad.tipo, occurredAt: fecha(novedad.horas), receivedAt: fecha(), source: origen,
    outcome: resultado, description: novedad.descripcion, location: novedad.lugar,
    evidence: novedad.evidencia ? { type: 'SIGNATURE', reference: novedad.evidencia } : null });
  for (let intento = 1; intento <= recogidasFallidas; intento++) {
    anotar({ tipo: 'PICKUP_FAILED', horas: -24 * (recogidasFallidas - intento + 1), lugar: 'Medellín',
      descripcion: `Intento ${intento}: no había quien entregara el paquete` }, 'WEBHOOK', 'APPLIED');
  }

  return {
    estado: () => estado,
    /** Aplica lo que informa el proveedor con las reglas de transición de una devolución. */
    registrar(novedad: Novedad, origen: 'WEBHOOK' | 'POLLING') {
      if (detenida) return;
      const sinRecoger = estado === 'PICKUP_PENDING' || estado === 'PICKUP_FAILED';
      let resultado = 'APPLIED';
      switch (novedad.tipo) {
        case 'PICKUP_FAILED':
          if (!sinRecoger) { resultado = 'OUT_OF_ORDER'; break; }
          estado = 'PICKUP_FAILED';
          recogidasFallidas++;
          detenida = recogidasFallidas >= MAXIMO_RECOGIDAS_FALLIDAS;
          break;
        case 'PICKED_UP':
          if (sinRecoger) estado = 'PICKED_UP'; else resultado = 'OUT_OF_ORDER';
          break;
        case 'IN_TRANSIT':
          if (estado === 'IN_RETURN') resultado = 'RECORDED'; else estado = 'IN_RETURN';
          break;
        case 'INCIDENT':
          estado = 'LOGISTICS_ISSUE';
          break;
        case 'DELIVERED_TO_SELLER':
          estado = 'DELIVERED_TO_SELLER';
          entregadaEn = fecha(novedad.horas);
          break;
      }
      anotar(novedad, origen, resultado);
    },
    seguimiento: (consulta?: string) => ({
      returnId: id, status: estado, trackingCode: `TRK-R${id}`, failedPickups: recogidasFallidas,
      maxFailedPickups: MAXIMO_RECOGIDAS_FALLIDAS, pickupStopped: detenida,
      canRequestNewPickup: estado === 'PICKUP_FAILED' && !detenida,
      tracking: !detenida && estado !== 'DELIVERED_TO_SELLER', lastPollFailed: false, lastPolledAt: fecha(),
      ...(entregadaEn ? { deliveredAt: entregadaEn } : {}), ...(consulta ? { refresh: consulta } : {}),
      events: [...eventos].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))
    })
  };
}

/** Estado de la API simulada: dos devoluciones del comprador en la tienda del vendedor. */
function crearServidor() {
  const devoluciones: Record<string, ReturnType<typeof crearDevolucion>> = {
    '9001': crearDevolucion(9001),
    '9002': crearDevolucion(9002, 2)
  };
  const pendientesEnProveedor: Record<string, Novedad[]> = { '9001': [], '9002': [] };

  return {
    avisaPorWebhook: (id: number, novedad: Novedad) => devoluciones[id].registrar(novedad, 'WEBHOOK'),
    guardaEnProveedor: (id: number, novedad: Novedad) => pendientesEnProveedor[id].push(novedad),
    estado: (id: number) => devoluciones[id].estado(),

    atender(ruta: string, metodo: string, _peticion: any, responder: any) {
      if (ruta === '/api/orders') {
        return responder([{ id: 1042, status: 'DELIVERED', total: 201000, shippingMethod: 'STANDARD', createdAt: fecha(-24 * 9),
          paymentStatus: 'APPROVED', sellerNameSnapshot: TIENDA, discountAmount: 0, shippingCost: 12000, history: [],
          refunds: [], items: [{ productId: 31, productName: 'Audífonos inalámbricos con estuche', quantity: 1,
            unitPrice: 189000, subtotal: 189000 }] }]);
      }
      if (ruta === '/api/seller/orders') return responder({ page: 0, size: 20, totalElements: 0, totalPages: 0, content: [] });
      const partes = ruta.match(/^\/api\/(?:seller\/)?returns\/(\d+)\/tracking(\/refresh)?$/);
      if (!partes) return false;
      const devolucion = devoluciones[partes[1]];
      // Una devolución ajena o inexistente responde lo mismo: 404, sin revelar si existe.
      if (!devolucion) return responder({ status: 404, code: 'RETURN_NOT_FOUND', message: 'Devolución no encontrada' }, 404);
      if (!partes[2]) return responder(devolucion.seguimiento());
      if (metodo !== 'POST') return false;
      const nuevas = pendientesEnProveedor[partes[1]].splice(0);
      nuevas.forEach(novedad => devolucion.registrar(novedad, 'POLLING'));
      return responder(devolucion.seguimiento(nuevas.length > 0 ? 'UPDATED' : 'NO_CHANGES'));
    }
  };
}

test('CU-25: seguir una devolución de la recogida a la tienda, con recogidas fallidas y una novedad logística', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, (ruta, metodo, peticion, responder) => servidor.atender(ruta, metodo, peticion, responder));

  const etapa = narrador(page, 'CU-25');
  const misPedidos = page.getByRole('dialog', { name: 'Mis pedidos' });
  const recibidos = page.locator('app-pedidos-recibidos');
  const consulta = page.getByRole('region', { name: 'Seguimiento de devolución', exact: true });
  const retorno = page.getByRole('region', { name: 'Seguimiento de la devolución', exact: true });
  const linea = retorno.locator('ol.linea');
  const consultar = async (numero: number) => {
    await consulta.scrollIntoViewIfNeeded();
    await consulta.getByLabel('N.º de devolución').fill(String(numero));
    await consulta.getByRole('button', { name: 'Consultar', exact: true }).click();
    await expect(retorno.getByRole('heading', { name: `Seguimiento de la devolución #${numero}` })).toBeVisible();
  };

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('El comprador consulta el retorno de su devolución aprobada', async () => {
    await etapa('El comprador inicia sesión; su devolución 9001 ya fue aprobada (CU-19)');
    await iniciarSesion(page, COMPRADOR);
    await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
    await consultar(9001);
    await expect(retorno).toContainText('Recogida pendiente · Guía TRK-R9001');
    await expect(retorno).toContainText('Recogidas fallidas: 0 de 3.');
    await etapa('El transportador debe pasar a recoger el producto en su dirección');
  });

  await test.step('Un intento de recogida falla y queda contado', async () => {
    servidor.avisaPorWebhook(9001, { tipo: 'PICKUP_FAILED', horas: -30, descripcion: 'No había nadie en la dirección',
      lugar: 'Medellín' });
    await etapa('El transportador va, pero no encuentra a nadie; la página relee cada 10 segundos');
    await expect(retorno).toContainText('Recogida fallida · Guía TRK-R9001', { timeout: 20000 });
    await expect(retorno).toContainText('Recogidas fallidas: 1 de 3.');
    await expect(retorno).toContainText('puedes usar una nueva opción de recogida');
    await etapa('Va una recogida fallida de tres permitidas');
  });

  await test.step('Recogen el paquete y sale hacia la tienda', async () => {
    servidor.guardaEnProveedor(9001, { tipo: 'PICKED_UP', horas: -6, descripcion: 'Paquete recogido', lugar: 'Medellín' });
    servidor.guardaEnProveedor(9001, { tipo: 'IN_TRANSIT', horas: -5, descripcion: 'En ruta hacia Bogotá', lugar: 'Medellín' });
    await etapa('Al día siguiente sí lo recogen; el comprador pide actualizar el seguimiento');
    await retorno.getByRole('button', { name: 'Actualizar seguimiento', exact: true }).click();
    await expect(retorno.getByRole('status').filter({ hasText: 'Seguimiento actualizado.' })).toBeVisible();
    await expect(retorno).toContainText('En retorno · Guía TRK-R9001');
    await expect(linea.locator('li')).toHaveCount(3);
    expect(servidor.estado(9001)).toBe('IN_RETURN');
    await etapa('Llegaron dos eventos juntos y quedaron en orden: recogido y en retorno');
  });

  await test.step('Una novedad en el camino no detiene el retorno', async () => {
    servidor.avisaPorWebhook(9001, { tipo: 'INCIDENT', horas: -3, descripcion: 'Vía cerrada por derrumbe: se usa una ruta alterna',
      lugar: 'La Dorada' });
    await etapa('En carretera hay un cierre: el transportador reporta una novedad');
    await expect(retorno).toContainText('Novedad logística · Guía TRK-R9001', { timeout: 20000 });
    await expect(linea).toContainText('Vía cerrada por derrumbe');
    servidor.avisaPorWebhook(9001, { tipo: 'IN_TRANSIT', horas: -2, descripcion: 'De nuevo en ruta', lugar: 'Honda' });
    await expect(retorno).toContainText('En retorno · Guía TRK-R9001', { timeout: 20000 });
    await etapa('Superada la novedad, el paquete sigue en retorno');
  });

  await test.step('El paquete llega a la tienda y el seguimiento termina', async () => {
    servidor.avisaPorWebhook(9001, { tipo: 'DELIVERED_TO_SELLER', horas: 0, descripcion: 'Entregado en la bodega de la tienda',
      lugar: 'Bogotá', evidencia: 'Recibido por bodega Sonido Andino' });
    await etapa('El transportador entrega el producto en la tienda');
    await expect(retorno).toContainText('Entregado al vendedor · Guía TRK-R9001', { timeout: 20000 });
    await expect(retorno).toContainText('Confirmación: Recibido por bodega Sonido Andino');
    await expect(retorno).toContainText('El seguimiento finalizó: ya no se consulta al servicio logístico.');
    await expect(linea.locator('li')).toHaveCount(6);
    await etapa('En ese instante logística avisa a devoluciones, que empieza la inspección (CU-19)');
    await pausa(page, 2500);
  });

  await test.step('Tres recogidas fallidas detienen el retorno', async () => {
    await etapa('Otra devolución del comprador, la 9002, ya lleva dos recogidas fallidas');
    await consultar(9002);
    await expect(retorno).toContainText('Recogidas fallidas: 2 de 3.');
    servidor.avisaPorWebhook(9002, { tipo: 'PICKUP_FAILED', horas: 0, descripcion: 'Intento 3: dirección cerrada',
      lugar: 'Medellín' });
    await etapa('El transportador falla por tercera vez');
    await expect(retorno.getByRole('alert')).toContainText('Se alcanzaron tres intentos de recogida fallidos', { timeout: 20000 });
    await expect(retorno).toContainText('Recogidas fallidas: 3 de 3.');
    await expect(retorno.getByRole('button', { name: 'Actualizar seguimiento', exact: true })).toBeHidden();
    await etapa('No se piden más recogidas automáticas: el caso queda para gestión y se avisa a ambas partes');
  });

  await test.step('Una devolución ajena no se puede consultar', async () => {
    await etapa('¿Y si escribe el número de una devolución que no es suya?');
    await consulta.getByLabel('N.º de devolución').fill('9999');
    await consulta.getByRole('button', { name: 'Consultar', exact: true }).click();
    await expect(retorno.getByRole('alert')).toContainText('La devolución no existe o no es tuya.');
    await etapa('El sistema responde igual que si no existiera: no revela datos de otros');
    await misPedidos.getByRole('button', { name: 'Cerrar pedidos', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La tienda ve el mismo seguimiento de la devolución entregada', async () => {
    await etapa('La tienda inicia sesión y consulta la devolución 9001');
    await iniciarSesion(page, VENDEDOR);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Pedidos recibidos');
    await consultar(9001);
    await expect(retorno).toContainText('Entregado al vendedor · Guía TRK-R9001');
    await expect(linea.locator('li')).toHaveCount(6);
    await linea.scrollIntoViewIfNeeded();
    await etapa('Fin: la tienda ve la misma línea de tiempo, de la recogida fallida a la entrega');
    await pausa(page, 4000);
  });
});
