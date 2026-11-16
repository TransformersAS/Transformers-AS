import { test, expect, Page, Route } from '@playwright/test';

/**
 * CU-19: recorrido visual de una devolución, de la solicitud al reembolso, en un solo navegador.
 * La API está simulada aquí con estado propio: cada acción cambia lo que la otra parte ve después. La llegada del
 * paquete y el fin de la inspección, que en el sistema real dependen de logística y del barrido programado, se simulan
 * entre etapas. Las reglas reales del backend las prueban las pruebas de devoluciones del módulo returns.
 */

type Rol = 'COMPRADOR' | 'VENDEDOR';
interface Cuenta { accountId: number; email: string; roles: Rol[] }

const COMPRADOR: Cuenta = { accountId: 2, email: 'comprador.demo@example.com', roles: ['COMPRADOR'] };
const VENDEDOR: Cuenta = { accountId: 4, email: 'vendedor.demo@example.com', roles: ['VENDEDOR'] };
const CUENTAS = [COMPRADOR, VENDEDOR];

const PEDIDO = 1042;
const PRODUCTO = { id: 31, nombre: 'Audífonos inalámbricos con estuche', precio: 189000 };
const DESCRIPCION = 'El audífono izquierdo no suena desde el primer día, aunque está cargado.';
const PREGUNTA = '¿Probaste a restablecerlos de fábrica? Si puedes, cuéntanos qué luz muestra el estuche.';
const RESPUESTA = 'Sí, los restablecí dos veces. El estuche muestra luz roja fija en el lado izquierdo.';
const NOTA_APROBACION = 'Es una falla de fábrica conocida; envíalos y te reembolsamos.';

/** Fecha local sin zona, como la envía el backend (LocalDateTime), desplazada en horas desde ahora. */
function fecha(horasDesdeAhora: number): string {
  const d = new Date(Date.now() + horasDesdeAhora * 3_600_000);
  const n = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:00`;
}

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let sesion: (Cuenta & { activeRole: Rol }) | null = null;
  let devolucion: any = null;
  let seguimiento: any = null;

  const pedido = () => ({
    id: PEDIDO, status: 'DELIVERED', total: PRODUCTO.precio + 12000, shippingMethod: 'STANDARD',
    createdAt: fecha(-24 * 9), paymentStatus: 'APPROVED', sellerNameSnapshot: 'Tienda Sonido Andino',
    discountAmount: 0, shippingCost: 12000, history: [], refunds: [],
    items: [{ productId: PRODUCTO.id, productName: PRODUCTO.nombre, quantity: 1, unitPrice: PRODUCTO.precio,
      subtotal: PRODUCTO.precio }]
  });
  const evento = (type: string, actor: string, to: string | null, details: string | null = null) =>
    devolucion.timeline.push({ type, from: devolucion.status, to, actor, details, at: fecha(0) });
  const cambiar = (type: string, actor: string, to: string, details: string | null = null) => {
    evento(type, actor, to, details);
    devolucion.status = to;
    devolucion.updatedAt = fecha(0);
  };
  const resumen = () => ({
    id: devolucion.id, orderId: PEDIDO, orderItemId: devolucion.orderItemId, productName: PRODUCTO.nombre, quantity: 1,
    refundAmount: PRODUCTO.precio, status: devolucion.status, reasonLabel: devolucion.reasonLabel, origin: 'BUYER',
    createdAt: devolucion.createdAt, updatedAt: devolucion.updatedAt, sellerDecisionOverdue: false,
    methodSelectionOverdue: false, pickupBlocked: false,
    awaitingBuyerResponse: devolucion.informationRequests.some((s: any) => s.status === 'OPEN')
  });
  const detalle = () => ({ ...devolucion, ...resumen() });

  return {
    /** Logística informa que el paquete llegó a la tienda: empiezan las 24 h de inspección. */
    llegaALaTienda() {
      seguimiento.status = 'DELIVERED';
      seguimiento.tracking = false;
      seguimiento.deliveredAt = fecha(0);
      seguimiento.events.push({ id: 2, type: 'DELIVERED', occurredAt: fecha(0), receivedAt: fecha(0), source: 'WEBHOOK',
        outcome: 'APPLIED', description: 'Entregado en la tienda', location: 'Bogotá', evidence: null });
      devolucion.inspectionDueAt = fecha(24);
      cambiar('INSPECTION_STARTED', 'LOGISTICS', 'IN_INSPECTION');
    },
    /** Pasan las 24 h sin reportes: el barrido pide el reembolso y la pasarela lo confirma. */
    terminaLaInspeccion() {
      cambiar('REFUND_REQUESTED', 'SYSTEM', 'REFUND_PENDING');
      cambiar('FINISHED', 'SYSTEM', 'FINISHED', 'Reembolso de $ 189.000 a la tarjeta del comprador.');
    },
    estado: () => devolucion?.status ?? null,

    async atender(route: Route) {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const method = req.method();
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

      if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
      if (path === '/api/auth/me') return sesion ? json(sesion) : json({ message: 'Sin sesión' }, 401);
      if (path === '/api/auth/login') {
        const cuenta = CUENTAS.find(c => c.email === new URLSearchParams(req.postData() ?? '').get('email'));
        if (!cuenta) return json({ message: 'Credenciales inválidas' }, 401);
        sesion = { ...cuenta, activeRole: cuenta.roles[0] };
        return json({});
      }
      if (path === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }

      // Pedido entregado del comprador.
      if (path === '/api/orders') return json([pedido()]);
      if (path === `/api/orders/${PEDIDO}`) return json(pedido());
      if (path === `/api/orders/${PEDIDO}/tracking`) {
        return json({ orderId: PEDIDO, status: 'DELIVERED', trackingCode: 'ENV-88231', tracking: false,
          lastPollFailed: false, deliveredAt: fecha(-24 * 6), events: [] });
      }

      // Comprador: solicitar, consultar, responder y elegir el método de retorno.
      if (path === '/api/return-requests/eligible-orders') {
        return json([{ orderId: PEDIDO, deliveredAt: fecha(-24 * 6), lines: [{ orderItemId: 77, productId: PRODUCTO.id,
          productName: PRODUCTO.nombre, quantity: 1, unitPrice: PRODUCTO.precio, refundAmount: PRODUCTO.precio,
          eligible: devolucion === null, ineligibleCode: null, ineligibleMessage: null, returnWindowEndsAt: fecha(24 * 24),
          existingReturnId: devolucion?.id ?? null, existingReturnStatus: devolucion?.status ?? null }] }]);
      }
      if (path === '/api/return-requests' && method === 'POST') {
        const datos = req.postDataJSON();
        devolucion = { id: 51, orderId: PEDIDO, orderItemId: datos.orderItemId, productId: PRODUCTO.id,
          productName: PRODUCTO.nombre, quantity: 1, unitPrice: PRODUCTO.precio, refundAmount: PRODUCTO.precio,
          status: 'REQUESTED', reason: datos.reason, reasonLabel: 'El producto llegó defectuoso',
          description: datos.description, origin: 'BUYER', originClaimId: null, createdAt: fecha(0), updatedAt: fecha(0),
          returnWindowEndsAt: fecha(24 * 24), decision: null, returnMethodCode: null, inspectionDueAt: null,
          problemReported: false, problemDescription: null, claimId: null, informationRequests: [], evidences: [],
          timeline: [] };
        evento('REQUESTED', 'BUYER', 'REQUESTED');
        return json({ id: 51, orderItemId: datos.orderItemId, status: 'REQUESTED', duplicate: false, evidenceCount: 0,
          createdAt: devolucion.createdAt }, 201);
      }
      if (path === '/api/return-requests' && method === 'GET') return json(devolucion ? [resumen()] : []);
      if (path === '/api/return-requests/51') return json(detalle());
      if (path === '/api/return-requests/51/information-response' && method === 'POST') {
        Object.assign(devolucion.informationRequests[0], { status: 'ANSWERED', responseText: req.postDataJSON().text,
          respondedAt: fecha(0) });
        cambiar('INFORMATION_ANSWERED', 'BUYER', 'IN_REVIEW');
        return json(detalle());
      }
      if (path === '/api/return-requests/51/return-methods') {
        return json([{ code: 'PICKUP', label: 'Recogida en tu dirección' },
          { code: 'DROP_OFF', label: 'Entrega en un punto aliado' }]);
      }
      if (path === '/api/return-requests/51/return-method' && method === 'POST') {
        devolucion.returnMethodCode = req.postDataJSON().method;
        evento('METHOD_CHOSEN', 'BUYER', null, 'Recogida en tu dirección');
        seguimiento = { returnId: 51, status: 'PICKUP_SCHEDULED', trackingCode: 'DEV-51-4417', failedPickups: 0,
          maxFailedPickups: 3, pickupStopped: false, canRequestNewPickup: false, tracking: true, lastPollFailed: false,
          events: [{ id: 1, type: 'PICKUP_SCHEDULED', occurredAt: fecha(0), receivedAt: fecha(0), source: 'SYSTEM',
            outcome: 'APPLIED', description: 'Recogida programada para mañana', location: null, evidence: null }] };
        return json(detalle());
      }
      if (/^\/api\/(seller\/)?returns\/51\/tracking(\/refresh)?$/.test(path)) {
        return seguimiento ? json(seguimiento) : json({ message: 'Sin envío de retorno' }, 404);
      }

      // Tienda: revisar, pedir información y aprobar.
      if (path === '/api/seller/return-requests') return json(devolucion ? [resumen()] : []);
      if (path === '/api/seller/return-requests/51') return json(detalle());
      if (path === '/api/seller/return-requests/51/review' && method === 'POST') {
        cambiar('REVIEW_STARTED', 'SELLER', 'IN_REVIEW');
        return json(detalle());
      }
      if (path === '/api/seller/return-requests/51/information-requests' && method === 'POST') {
        devolucion.informationRequests.push({ id: 1, message: req.postDataJSON().message, requestedAt: fecha(0),
          dueAt: fecha(24), status: 'OPEN', expired: false, responseText: null, respondedAt: null });
        cambiar('INFORMATION_REQUESTED', 'SELLER', 'INFO_REQUIRED');
        return json(detalle());
      }
      if (path === '/api/seller/return-requests/51/approve' && method === 'POST') {
        devolucion.decision = { note: req.postDataJSON().note, decidedAt: fecha(0) };
        cambiar('APPROVED', 'SELLER', 'APPROVED');
        return json(detalle());
      }

      // Lo que la página principal pide de paso y no es parte de CU-19.
      if (path === '/api/recommendations') return json({ products: [], strategy: 'DEMO_CONTROLADA' });
      if (path === '/api/cart') return json({ items: [], total: 0 });
      if (path === '/api/notifications') return json({ items: [], hasMore: false });
      return json([]);
    }
  };
}

test('CU-19: solicitar, revisar, aclarar, aprobar, enviar, inspeccionar y reembolsar una devolución', async ({ page }) => {
  const servidor = crearServidor();
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', route => servidor.atender(route));

  const etapa = (texto: string) => narrar(page, texto);
  const misPedidos = page.getByRole('dialog', { name: 'Mis pedidos' });
  const misDevoluciones = page.locator('app-mis-devoluciones');
  const recibidas = page.locator('app-devoluciones-recibidas');
  const abrirDevolucion = (panel: typeof recibidas) => panel.getByText(/^Devolución #51 · /).click();

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('El comprador pide devolver un producto de un pedido entregado', async () => {
    await etapa('El comprador inicia sesión y abre sus pedidos');
    await iniciarSesion(page, COMPRADOR.email);
    await page.getByRole('button', { name: 'Mis pedidos', exact: true }).click();
    await misPedidos.getByRole('button', { name: `Ver detalle del pedido ${PEDIDO}` }).click();
    await etapa('El pedido está entregado y dentro del plazo: aparece «Devolver»');
    await misPedidos.getByRole('button', { name: 'Devolver', exact: true }).click();
    await misPedidos.getByLabel('Motivo').selectOption('DEFECTIVE');
    await misPedidos.getByLabel('Descripción').pressSequentially(DESCRIPCION, { delay: 20 });
    await misPedidos.getByRole('button', { name: 'Solicitar devolución', exact: true }).click();
    await expect(misPedidos.getByRole('status').filter({ hasText: 'Tu devolución quedó solicitada' })).toBeVisible();
    expect(servidor.estado()).toBe('REQUESTED');
    await etapa('La devolución queda «Solicitada» para que la tienda la revise');
    await misPedidos.getByRole('button', { name: 'Cerrar pedidos', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La tienda empieza la revisión y pide más información', async () => {
    await etapa('La tienda inicia sesión y abre «Devoluciones recibidas»');
    await iniciarSesion(page, VENDEDOR.email);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Devoluciones recibidas');
    await abrirDevolucion(recibidas);
    await expect(recibidas).toContainText(DESCRIPCION);
    await recibidas.getByRole('button', { name: 'Iniciar revisión', exact: true }).click();
    await expect(recibidas.getByRole('status').filter({ hasText: 'Empezaste la revisión.' })).toBeVisible();
    await etapa('Antes de decidir, pide información: el comprador tiene 24 horas');
    await recibidas.getByLabel('Mensaje').pressSequentially(PREGUNTA, { delay: 15 });
    await recibidas.getByRole('button', { name: 'Pedir información', exact: true }).click();
    await expect(recibidas.getByRole('status').filter({ hasText: 'Se pidió la información.' })).toBeVisible();
    expect(servidor.estado()).toBe('INFO_REQUIRED');
    await recibidas.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('El comprador responde dentro del plazo', async () => {
    await etapa('El comprador ve que su devolución requiere respuesta');
    await iniciarSesion(page, COMPRADOR.email);
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mis devoluciones');
    await expect(misDevoluciones.getByText('Requiere tu respuesta', { exact: true })).toBeVisible();
    await abrirDevolucion(misDevoluciones);
    await expect(misDevoluciones).toContainText(PREGUNTA);
    await misDevoluciones.getByLabel('Tu respuesta').pressSequentially(RESPUESTA, { delay: 15 });
    await misDevoluciones.getByRole('button', { name: 'Enviar respuesta', exact: true }).click();
    await expect(misDevoluciones.getByRole('status').filter({ hasText: 'Tu respuesta se envió al vendedor.' })).toBeVisible();
    expect(servidor.estado()).toBe('IN_REVIEW');
    await misDevoluciones.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La tienda lee la respuesta y aprueba la devolución', async () => {
    await etapa('La tienda lee la respuesta y aprueba');
    await iniciarSesion(page, VENDEDOR.email);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Devoluciones recibidas');
    await abrirDevolucion(recibidas);
    await expect(recibidas).toContainText(RESPUESTA);
    await recibidas.getByLabel('Justificación (obligatoria al rechazar)').pressSequentially(NOTA_APROBACION, { delay: 15 });
    await recibidas.getByRole('button', { name: 'Aprobar', exact: true }).click();
    await expect(recibidas.getByRole('status').filter({ hasText: 'Aprobaste la devolución.' })).toBeVisible();
    expect(servidor.estado()).toBe('APPROVED');
    await recibidas.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('El comprador elige cómo enviar el producto', async () => {
    await etapa('Aprobada: el comprador elige cómo devolver el producto');
    await iniciarSesion(page, COMPRADOR.email);
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mis devoluciones');
    await abrirDevolucion(misDevoluciones);
    await expect(misDevoluciones).toContainText(NOTA_APROBACION);
    await misDevoluciones.getByLabel('Recogida en tu dirección').check();
    await misDevoluciones.getByRole('button', { name: 'Confirmar método', exact: true }).click();
    await expect(misDevoluciones.getByRole('status').filter({ hasText: 'Elegiste el método de retorno.' })).toBeVisible();
    await expect(misDevoluciones.getByRole('region', { name: 'Seguimiento de la devolución' })).toContainText('DEV-51-4417');
    await etapa('Logística programa la recogida y el comprador puede seguir el envío');
    await misDevoluciones.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('El paquete llega a la tienda y empieza la inspección', async () => {
    servidor.llegaALaTienda();
    await etapa('Días después, logística entrega el paquete en la tienda');
    await iniciarSesion(page, VENDEDOR.email);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Devoluciones recibidas');
    await abrirDevolucion(recibidas);
    await expect(recibidas.getByRole('heading', { name: 'Inspección del producto' })).toBeVisible();
    await expect(recibidas).toContainText('de las 24 de inspección');
    await etapa('La tienda tiene 24 horas para reportar un problema; si no, se reembolsa solo');
    await recibidas.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('Sin problemas en la inspección, el comprador recibe el reembolso', async () => {
    servidor.terminaLaInspeccion();
    await etapa('Pasan las 24 horas sin reportes: el sistema reembolsa al comprador');
    await iniciarSesion(page, COMPRADOR.email);
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mis devoluciones');
    await abrirDevolucion(misDevoluciones);
    await expect(misDevoluciones.getByText('Finalizada', { exact: true }).first()).toBeVisible();
    const historial = misDevoluciones.getByRole('list', { name: 'Historial de la devolución' });
    await historial.scrollIntoViewIfNeeded();
    for (const paso of ['Devolución solicitada', 'El vendedor pidió información', 'El comprador respondió', 'Aprobada',
      'Método de retorno elegido', 'La mercancía llegó al vendedor: inspección de 24 h', 'Reembolso completado']) {
      await expect(historial).toContainText(paso);
    }
    await etapa('Fin: la devolución está finalizada y el historial muestra cada paso');
    // Pausa exclusiva de presentación para leer el historial final.
    await page.waitForTimeout(4000);
  });
});

/** Aviso fijo en la esquina que narra la etapa; solo es para la presentación. */
async function narrar(page: Page, texto: string): Promise<void> {
  console.log(texto);
  await page.evaluate(texto => {
    document.querySelector('#cu19-demo-nota')?.remove();
    const nota = document.createElement('aside');
    nota.id = 'cu19-demo-nota';
    nota.textContent = `CU-19 · Demostración con datos controlados · ${texto}`;
    Object.assign(nota.style, { position: 'fixed', bottom: '16px', right: '16px', zIndex: '99999', maxWidth: '340px',
      padding: '14px', background: '#fff4df', color: '#183037', border: '2px solid #f56b4b', borderRadius: '12px',
      pointerEvents: 'none' });
    document.body.appendChild(nota);
  }, texto);
  await page.waitForTimeout(1800);
}

async function iniciarSesion(page: Page, correo: string): Promise<void> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  const panel = page.getByRole('dialog', { name: 'Acceso a tu cuenta' });
  await panel.getByLabel('Correo').pressSequentially(correo, { delay: 40 });
  await panel.getByLabel('Contraseña').fill('MarketplaceDemo123!');
  await panel.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}

async function cerrarSesion(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Acceso a tu cuenta' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}

async function abrirDesdeMenu(page: Page, menu: string, opcion: string): Promise<void> {
  await page.getByRole('button', { name: menu, exact: true }).click();
  await page.getByRole('button', { name: opcion, exact: true }).click();
}
