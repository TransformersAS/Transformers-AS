import { test, expect, Page } from '@playwright/test';
import {
  Cuenta, abrirDesdeMenu, cerrarPanelCuenta, cerrarSesion, elegirEnLista, escribir, iniciarSesion, narrador, pausa,
  simularBackend
} from './apoyo';

/**
 * CU-13: recorrido visual de una reclamación de compra con sus tres actores, en un solo navegador.
 *  1. La compradora abre una reclamación sobre un producto de su compra, con evidencia; no puede abrir otra igual.
 *  2. El vendedor le pide información y ella responde.
 *  3. El vendedor propone un reembolso: uno mayor a lo pagado se rechaza; propone uno parcial.
 *  4. La compradora no está de acuerdo y escala a soporte.
 *  5. Soporte decide un reembolso y la reclamación queda resuelta para la compradora.
 * La API está simulada aquí con estado propio. Las reglas reales (dueño de la compra, una reclamación abierta por
 * producto, estados permitidos, tope del reembolso y reembolso idempotente) las prueban las pruebas del backend de CU-13.
 */

const COMPRADORA: Cuenta = { accountId: 2, email: 'lucia.compras@example.com', roles: ['COMPRADOR'] };
const VENDEDOR: Cuenta = { accountId: 4, email: 'ventas@sonidoandino.co', roles: ['VENDEDOR'] };
const SOPORTE: Cuenta = { accountId: 7, email: 'soporte.demo@example.com', roles: ['SOPORTE'] };

const PEDIDO = 1042;
const PARLANTE = { productId: 31, productName: 'Parlante Bluetooth Andes', quantity: 1, unitPrice: 180000, subtotal: 180000 };
const CABLE = { productId: 32, productName: 'Cable auxiliar 3,5 mm', quantity: 2, unitPrice: 12000, subtotal: 24000 };

const ahora = () => new Date().toISOString();

type Autor = 'BUYER' | 'SELLER' | 'SUPPORT';
type Tipo = 'MESSAGE' | 'INFO_REQUEST' | 'PROPOSAL' | 'ESCALATION' | 'DECISION';

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  const reclamaciones: any[] = [];
  const reembolsos: { claimId: number; amount: number }[] = [];
  const conflicto = (mensaje: string, estado = 409) => ({ cuerpo: { message: mensaje }, estado });

  const mensaje = (r: any, author: Autor, kind: Tipo, message: string) => {
    r.messages.push({ author, kind, message, createdAt: ahora() });
    r.updatedAt = ahora();
  };
  const exigir = (r: any, texto: string, ...estados: string[]) => estados.includes(r.status) ? null : conflicto(texto);
  const tope = (r: any, monto: number | null) => monto !== null && monto > r.itemTotal
    ? conflicto(`El reembolso no puede superar lo pagado por el producto (${r.itemTotal})`, 400) : null;

  return {
    reclamaciones, reembolsos,
    atender: (ruta: string, metodo: string, peticion: any, responder: (cuerpo: unknown, estado?: number) => Promise<void>) => {
      const enviar = (resultado: { cuerpo: unknown; estado: number } | null, r: any) =>
        resultado ? responder(resultado.cuerpo, resultado.estado) : responder(r);
      const cuerpo = () => peticion.postDataJSON() ?? {};

      // ---------- Compras de la compradora ----------
      if (ruta === '/api/orders' && metodo === 'GET') {
        return responder([{ id: PEDIDO, status: 'DELIVERED', total: 214000, shippingMethod: 'STANDARD',
          createdAt: new Date(Date.now() - 6 * 86_400_000).toISOString() }]);
      }
      if (ruta === `/api/orders/${PEDIDO}`) return responder({ id: PEDIDO, status: 'DELIVERED', items: [PARLANTE, CABLE] });

      // ---------- Listas por rol ----------
      if ((ruta === '/api/claims' || ruta === '/api/seller/claims') && metodo === 'GET') {
        return responder([...reclamaciones].reverse());
      }
      if (ruta === '/api/support/claims' && metodo === 'GET') {
        const estado = new URL(peticion.url()).searchParams.get('status');
        return responder(reclamaciones.filter(r => r.status === estado));
      }

      // ---------- Comprador ----------
      if (ruta === '/api/claims' && metodo === 'POST') {
        const { orderId, productId, description, evidenceUrls } = cuerpo();
        const item = [PARLANTE, CABLE].find(i => i.productId === productId);
        if (orderId !== PEDIDO || !item) return responder({ message: 'El producto no pertenece a esa compra' }, 400);
        if (reclamaciones.some(r => r.orderId === orderId && r.productId === productId && r.status !== 'RESOLVED')) {
          return responder({ message: 'Ya hay una reclamación abierta para este producto de la compra' }, 409);
        }
        const r = { id: 501 + reclamaciones.length, orderId, productId, productName: item.productName,
          itemTotal: item.subtotal, storeId: 1, description, status: 'OPEN', evidenceUrls, proposalText: null,
          proposedRefund: null, resolution: null, resolutionNote: null, refundAmount: null, refundStatus: null,
          createdAt: ahora(), updatedAt: ahora(), messages: [] };
        reclamaciones.push(r);
        return responder(r, 201);
      }
      const accion = ruta.match(/^\/api\/(claims|seller\/claims|support\/claims)\/(\d+)\/([a-z-]+)$/);
      if (!accion || metodo !== 'POST') return false;
      const r = reclamaciones.find(x => x.id === Number(accion[2]));
      if (!r) return responder({ message: 'Reclamación no encontrada' }, 404);
      const datos = cuerpo();

      switch (`${accion[1]}/${accion[3]}`) {
        case 'claims/messages':
          if (r.status === 'RESOLVED') return responder({ message: 'La reclamación ya terminó' }, 409);
          mensaje(r, 'BUYER', 'MESSAGE', datos.message.trim());
          if (r.status === 'INFO_REQUESTED') r.status = 'OPEN';
          return responder(r);
        case 'claims/accept': {
          const error = exigir(r, 'No hay una solución propuesta para aceptar', 'SOLUTION_PROPOSED');
          if (!error) { r.status = 'RESOLVED'; r.resolution = 'SOLUTION_ACCEPTED'; }
          return enviar(error, r);
        }
        case 'claims/escalate': {
          const error = exigir(r, 'Solo se puede escalar una reclamación que sigue abierta',
            'OPEN', 'INFO_REQUESTED', 'SOLUTION_PROPOSED');
          if (!error) {
            r.status = 'ESCALATED';
            mensaje(r, 'BUYER', 'ESCALATION', datos.reason?.trim() || 'No llegamos a un acuerdo con el vendedor');
          }
          return enviar(error, r);
        }
        case 'seller/claims/request-info': {
          const error = exigir(r, 'Solo se puede pedir información en una reclamación abierta', 'OPEN');
          if (!error) { r.status = 'INFO_REQUESTED'; mensaje(r, 'SELLER', 'INFO_REQUEST', datos.message.trim()); }
          return enviar(error, r);
        }
        case 'seller/claims/propose': {
          const error = exigir(r, 'Ya no se puede proponer una solución en esta reclamación',
            'OPEN', 'INFO_REQUESTED', 'SOLUTION_PROPOSED') ?? tope(r, datos.refundAmount);
          if (!error) {
            r.status = 'SOLUTION_PROPOSED';
            r.proposalText = datos.message.trim();
            r.proposedRefund = datos.refundAmount || null;
            mensaje(r, 'SELLER', 'PROPOSAL', r.proposalText);
          }
          return enviar(error, r);
        }
        case 'support/claims/resolve': {
          const error = exigir(r, 'Solo soporte puede decidir una reclamación escalada', 'ESCALATED')
            ?? (datos.decision === 'REFUND_GRANTED' && !(datos.amount > 0) ? conflicto('Indica el monto a reembolsar', 400) : null)
            ?? tope(r, datos.amount);
          if (!error) {
            r.status = 'RESOLVED';
            r.resolution = datos.decision;
            r.resolutionNote = datos.note.trim();
            mensaje(r, 'SUPPORT', 'DECISION', r.resolutionNote);
            if (datos.decision === 'REFUND_GRANTED') {
              r.refundAmount = datos.amount;
              r.refundStatus = 'COMPLETED';
              reembolsos.push({ claimId: r.id, amount: datos.amount });
            }
          }
          return enviar(error, r);
        }
      }
      return false;
    }
  };
}

/** Entra con la cuenta y abre la pantalla de reclamaciones desde el menú de su rol. */
async function abrirReclamaciones(page: Page, cuenta: Cuenta, menu: string, opcion: string) {
  await iniciarSesion(page, cuenta.email);
  await cerrarPanelCuenta(page);
  await abrirDesdeMenu(page, menu, opcion);
  const panel = page.locator('app-reclamaciones');
  await expect(panel).toBeVisible();
  return panel;
}

test('CU-13: una reclamación pasa por la compradora, el vendedor y soporte hasta resolverse', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, [COMPRADORA, VENDEDOR, SOPORTE], servidor.atender);
  const nota = narrador(page, 'CU-13');
  await page.goto('/');

  // ---------- 1. La compradora abre la reclamación ----------
  await nota('Lucía recibió su pedido, pero el parlante no enciende. Abre una reclamación.');
  let panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await expect(panel.getByText('No hay reclamaciones que mostrar.')).toBeVisible();
  await panel.getByRole('button', { name: 'Nueva reclamación' }).click();

  await panel.getByRole('button', { name: 'Enviar reclamación' }).click();
  await expect(panel.getByRole('alert')).toContainText('Elige la compra y el producto afectado');
  await nota('Sin elegir la compra y el producto, no se puede enviar.');

  await elegirEnLista(page, panel.locator('ion-select[label="Compra"]'), new RegExp(`Pedido #${PEDIDO}`));
  await elegirEnLista(page, panel.locator('ion-select[label="Producto afectado"]'), PARLANTE.productName);
  await escribir(panel.getByLabel('Cuéntanos qué pasó'), 'El parlante llegó y no enciende, aunque lo cargué toda la noche.');
  await escribir(panel.getByLabel('Evidencias (una dirección por línea, máximo 5)'), 'https://fotos.example.com/parlante-apagado.jpg');
  await nota('Elige la compra, el producto afectado, describe el problema y adjunta una foto como evidencia.');
  await panel.getByRole('button', { name: 'Enviar reclamación' }).click();
  await expect(panel.getByRole('status')).toContainText('Reclamación enviada');
  await expect(panel.locator('ion-item', { hasText: PARLANTE.productName })).toContainText('Abierta');
  await nota('La reclamación queda abierta y le llega al vendedor.');

  await nota('Si intenta abrir otra por el mismo producto, el sistema no la deja duplicar.');
  await panel.getByRole('button', { name: 'Nueva reclamación' }).click();
  await elegirEnLista(page, panel.locator('ion-select[label="Compra"]'), new RegExp(`Pedido #${PEDIDO}`));
  await elegirEnLista(page, panel.locator('ion-select[label="Producto afectado"]'), PARLANTE.productName);
  await escribir(panel.getByLabel('Cuéntanos qué pasó'), 'Sigue sin encender.');
  await panel.getByRole('button', { name: 'Enviar reclamación' }).click();
  await expect(panel.getByRole('alert')).toContainText('Ya hay una reclamación abierta para este producto');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- 2. El vendedor pide información ----------
  await nota('El vendedor de Sonido Andino revisa las reclamaciones de su tienda.');
  panel = await abrirReclamaciones(page, VENDEDOR, 'Opciones de mi negocio', 'Reclamaciones');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await expect(panel.getByText('Problema: El parlante llegó y no enciende')).toBeVisible();
  await expect(panel.getByText('https://fotos.example.com/parlante-apagado.jpg')).toBeVisible();
  await escribir(panel.getByLabel('Mensaje para el comprador'), '¿La luz de carga se enciende al conectarlo?');
  await panel.getByRole('button', { name: 'Pedir información' }).click();
  await expect(panel.getByRole('status')).toContainText('Información solicitada');
  await expect(panel.locator('h2 + p ion-badge')).toContainText('Información solicitada');
  await nota('Antes de proponer algo, le pide más información a la compradora.');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- La compradora responde ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await escribir(panel.getByLabel('Escribe un mensaje o el motivo para escalar'), 'No, la luz no prende con ningún cargador.');
  await panel.getByRole('button', { name: 'Enviar mensaje' }).click();
  await expect(panel.getByRole('status')).toContainText('Mensaje enviado');
  await expect(panel.locator('h2 + p ion-badge')).toContainText('Abierta');
  await nota('Lucía responde y la reclamación vuelve a estar abierta para el vendedor.');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- 3. El vendedor propone una solución ----------
  panel = await abrirReclamaciones(page, VENDEDOR, 'Opciones de mi negocio', 'Reclamaciones');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await nota('El vendedor intenta ofrecer un reembolso mayor a lo que se pagó por el parlante.');
  await escribir(panel.getByLabel('Mensaje para el comprador'), 'Te devolvemos el dinero.');
  await escribir(panel.getByLabel('Reembolso que ofreces (COP, opcional; máximo lo pagado)'), '250000');
  await panel.getByRole('button', { name: 'Proponer solución' }).click();
  await expect(panel.getByRole('alert')).toContainText('El reembolso no puede superar lo pagado por el producto (180000)');
  await nota('El reembolso nunca puede superar lo pagado por ese producto.');

  await escribir(panel.getByLabel('Mensaje para el comprador'), 'Puede ser un problema de batería: te devolvemos 50.000 y conservas el parlante.');
  await escribir(panel.getByLabel('Reembolso que ofreces (COP, opcional; máximo lo pagado)'), '50000');
  await panel.getByRole('button', { name: 'Proponer solución' }).click();
  await expect(panel.getByRole('status')).toContainText('Solución propuesta');
  await expect(panel.locator('h2 + p ion-badge')).toContainText('Solución propuesta');
  await nota('Propone un reembolso parcial de 50.000.');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- 4. La compradora escala ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await expect(panel.getByText('Solución propuesta: Puede ser un problema de batería')).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Aceptar la solución' })).toBeVisible();
  await nota('Lucía puede aceptar la propuesta, pero el parlante no sirve: prefiere que lo revise soporte.');
  await escribir(panel.getByLabel('Escribe un mensaje o el motivo para escalar'), 'El parlante no funciona; quiero que me devuelvan lo que pagué.');
  await panel.getByRole('button', { name: 'Escalar a soporte' }).click();
  await expect(panel.getByRole('status')).toContainText('Reclamación escalada a soporte');
  await expect(panel.locator('h2 + p ion-badge')).toContainText('Escalada a soporte');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- 5. Soporte decide ----------
  await nota('Un agente de soporte revisa las reclamaciones escaladas.');
  panel = await abrirReclamaciones(page, SOPORTE, 'Opciones de soporte', 'Reclamaciones escaladas');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await expect(panel.getByText('Historial', { exact: true })).toBeVisible();
  await expect(panel.locator('ion-list ion-item')).toHaveCount(4);
  await nota('Ve todo el historial: la información pedida, la respuesta, la propuesta y el motivo de la escalada.');
  await pausa(page, 2500);
  await escribir(panel.getByLabel('Monto a reembolsar (COP; máximo lo pagado)'), '180000');
  await escribir(panel.getByLabel('Explica tu decisión'), 'El producto llegó defectuoso según la evidencia. Se reembolsa el valor completo.');
  await panel.getByRole('button', { name: 'Registrar decisión' }).click();
  await expect(panel.getByRole('status')).toContainText('Decisión registrada');
  await expect(panel.getByText('No hay reclamaciones que mostrar.')).toBeVisible();
  await nota('Soporte concede el reembolso completo: 180.000. La reclamación sale de las pendientes por decidir.');
  await elegirEnLista(page, panel.locator('ion-select[label="Mostrar"]'), 'Resueltas');
  // La reclamación que tenía abierta está entre las resueltas, así que la pantalla vuelve a mostrarla.
  await expect(panel.getByRole('heading', { name: `Reclamación #501 · ${PARLANTE.productName}` })).toBeVisible();
  await expect(panel.getByText('Resultado: Soporte concedió un reembolso.')).toBeVisible();
  await nota('En las resueltas queda la decisión con su explicación.');
  await panel.getByRole('button', { name: 'Cerrar' }).click();
  await cerrarSesion(page);

  // ---------- La compradora ve el resultado ----------
  panel = await abrirReclamaciones(page, COMPRADORA, 'Opciones de mis compras', 'Mis reclamaciones');
  await expect(panel.locator('ion-item', { hasText: PARLANTE.productName })).toContainText('Resuelta');
  await panel.locator('ion-item', { hasText: PARLANTE.productName }).click();
  await expect(panel.getByText('Resultado: Soporte concedió un reembolso.')).toContainText('completado');
  await expect(panel.getByRole('button', { name: 'Escalar a soporte' })).toHaveCount(0);
  await nota('Lucía ve su reclamación resuelta y el reembolso completado. Ya no se puede seguir tramitando.');

  expect(servidor.reembolsos).toEqual([{ claimId: 501, amount: 180000 }]);
  expect(servidor.reclamaciones).toHaveLength(1);
  await pausa(page, 2500);
});
