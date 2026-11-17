import { test, expect, Page } from '@playwright/test';
import {
  ADMIN, TIENDA, VENDEDOR, abrirDesdeMenu, cerrarSesion, escribir, fecha, fechaVisible, iniciarSesion, narrador,
  pausa, simularBackend
} from './apoyo';

/**
 * CU-27: recorrido visual de la evaluación y suspensión temporal de una tienda, en un solo navegador que alterna la
 * cuenta de administración y la del vendedor. La API está simulada aquí con estado propio: lo que decide una parte lo
 * ve la otra después. Las reglas reales (antecedentes confirmados, efectos de la suspensión sobre el catálogo y el
 * pago, barrido de vencimientos) las prueban las pruebas del módulo suspensions.
 */

const PREGUNTA = '¿Qué pasó con los pedidos de octubre que terminaron en reembolso por productos defectuosos?';
const RESPUESTA = 'Un lote de audífonos llegó con falla de fábrica. Ya lo retiramos y cambiamos de proveedor.';
const MOTIVO = 'Reincidencia en productos defectuosos: tres antecedentes confirmados en los últimos dos meses.';
const JUSTIFICACION = 'El vendedor ya retiró el lote defectuoso; se reduce la suspensión a tres días.';
const MOTIVO_FIN = 'El vendedor demostró el cambio de proveedor y la corrección de las publicaciones.';

// Efectos que informa el backend (SuspensionEffects).
const BLOQUEADO = ['Publicar productos nuevos', 'Reactivar publicaciones pausadas',
  'Modificar la información comercial de sus publicaciones', 'Crear promociones o cupones',
  'Recibir nuevas compras: sus productos dejan de mostrarse en el catálogo'];
const PERMITIDO = ['Atender los pedidos existentes hasta su entrega', 'Atender reclamaciones y devoluciones',
  'Responder mensajes de operaciones anteriores', 'Consultar el historial y la analítica de su tienda',
  'Pausar o retirar publicaciones', 'Usar los demás roles de su cuenta, por ejemplo comprar'];

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  const EN_CURSO = { ordersInProgress: 2, openClaims: 1, openReturns: 0 };
  const antecedentes = [
    { type: 'CLAIM', referenceId: 14, decidedAt: fecha(-24 * 40), subject: 'Audífonos inalámbricos con estuche (pedido #988)',
      decision: 'REFUND_GRANTED', detail: 'El producto llegó defectuoso', amount: 189000 },
    { type: 'CLAIM', referenceId: 19, decidedAt: fecha(-24 * 21), subject: 'Audífonos inalámbricos con estuche (pedido #1017)',
      decision: 'REFUND_GRANTED', detail: 'No encendía al recibirlo', amount: 189000 },
    { type: 'MODERATION', referenceId: 7, decidedAt: fecha(-24 * 9), subject: 'Publicación: Audífonos inalámbricos con estuche',
      decision: 'RETIRAR', detail: 'La descripción prometía una garantía que la tienda no cumple', amount: null }
  ];
  let estadoTienda = 'ACTIVE';
  let aclaracion: any = null;
  let suspension: any = null;

  const vigente = () => suspension?.status === 'ACTIVE' ? suspension : null;
  const cambio = (tipo: string, antes: string | null, despues: string | null, justificacion: string) => ({
    type: tipo, previousEndsAt: antes, newEndsAt: despues, justification: justificacion, actorId: ADMIN.accountId,
    reactivationBlocked: false, createdAt: fecha() });
  const horasDe = (dias: number) => 24 * dias;
  const finalDesde = (inicio: string, dias: number) => {
    const d = new Date(inicio);
    d.setDate(d.getDate() + dias);
    const n = (v: number) => String(v).padStart(2, '0');
    return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:${n(d.getSeconds())}`;
  };
  const rechazo = (campo: string, mensaje: string) =>
    ({ status: 400, code: 'VALIDATION_ERROR', message: mensaje, details: { field: campo } });

  return {
    estadoTienda: () => estadoTienda,
    suspension: () => suspension,

    atender(ruta: string, metodo: string, peticion: any, responder: any) {
      const datos = metodo === 'GET' ? null : peticion.postDataJSON();

      // Administración: evaluar, pedir aclaración, suspender, cambiar la duración y finalizar.
      if (ruta === '/api/admin/store-evaluations') {
        return responder([{ storeId: 1, storeName: TIENDA, storeStatus: estadoTienda, claims: 2, moderations: 1,
          suspensions: suspension ? 1 : 0, recentRecords: 3, lastRecordAt: antecedentes[2].decidedAt, recidivist: true,
          activeSuspension: !!vigente(), openClarification: aclaracion?.status === 'OPEN' }]);
      }
      if (ruta === '/api/admin/store-evaluations/1') {
        return responder({ storeId: 1, storeName: TIENDA, storeStatus: estadoTienda,
          statusReason: vigente() ? 'Suspensión temporal' : null, pending: EN_CURSO, records: antecedentes, recentRecords: 3,
          recidivist: true, activeSuspension: vigente(), suspensions: suspension ? [suspension] : [], reviews: [],
          clarifications: aclaracion ? [aclaracion] : [] });
      }
      if (ruta === '/api/admin/store-evaluations/1/clarifications' && metodo === 'POST') {
        aclaracion = { id: 1, message: datos.message, requestedAt: fecha(), dueAt: fecha(72), status: 'OPEN', response: null,
          respondedAt: null };
        return responder(aclaracion, 201);
      }
      if (ruta === '/api/admin/store-evaluations/1/suspensions/preview' && metodo === 'POST') {
        if ((datos.reason ?? '').trim().length < 10) {
          return responder(rechazo('reason', 'El motivo debe tener entre 10 y 1000 caracteres'), 400);
        }
        return responder({ storeId: 1, storeName: TIENDA, reason: datos.reason, durationDays: datos.durationDays,
          startsAt: fecha(), endsAt: fecha(horasDe(datos.durationDays)), blocked: BLOQUEADO, allowed: PERMITIDO,
          pending: EN_CURSO, warning: null });
      }
      if (ruta === '/api/admin/store-evaluations/1/suspensions' && metodo === 'POST') {
        const inicio = fecha();
        const fin = finalDesde(inicio, datos.durationDays);
        suspension = { id: 5, storeId: 1, reason: datos.reason, startsAt: inicio, endsAt: fin, status: 'ACTIVE',
          appliedBy: ADMIN.accountId, closedAt: null, closedBy: null, closeReason: null,
          changes: [cambio('APPLIED', null, fin, datos.reason)] };
        estadoTienda = 'SUSPENDED';
        return responder(suspension, 201);
      }
      if (ruta === '/api/admin/store-suspensions/5/duration' && metodo === 'PUT') {
        const fin = finalDesde(suspension.startsAt, datos.durationDays);
        suspension.changes.push(cambio('DURATION_CHANGED', suspension.endsAt, fin, datos.justification));
        suspension.endsAt = fin;
        return responder(suspension);
      }
      if (ruta === '/api/admin/store-suspensions/5/lift' && metodo === 'POST') {
        suspension.changes.push(cambio('LIFTED', suspension.endsAt, null, datos.reason));
        Object.assign(suspension, { status: 'LIFTED', closedAt: fecha(), closedBy: ADMIN.accountId, closeReason: datos.reason });
        estadoTienda = 'ACTIVE';
        return responder({ suspensionId: 5, status: 'LIFTED', reactivated: true });
      }

      // Vendedor: el estado de su tienda y la respuesta a la aclaración. No ve qué cuenta tomó cada decisión.
      if (ruta === '/api/seller/store/standing') {
        const paraVendedor = suspension
          ? { ...suspension, appliedBy: null, closedBy: null, changes: suspension.changes.map((c: any) => ({ ...c, actorId: null })) }
          : null;
        return responder({ status: estadoTienda, statusReason: vigente() ? 'Suspensión temporal' : null,
          activeSuspension: vigente() ? paraVendedor : null, suspensions: paraVendedor ? [paraVendedor] : [],
          clarifications: aclaracion ? [aclaracion] : [], blocked: vigente() ? BLOQUEADO : [],
          allowed: vigente() ? PERMITIDO : [] });
      }
      if (ruta === '/api/seller/store/standing/clarifications/1/response' && metodo === 'POST') {
        Object.assign(aclaracion, { status: 'ANSWERED', response: datos.response, respondedAt: fecha() });
        return responder(aclaracion);
      }
      return false;
    }
  };
}

test('CU-27: evaluar una tienda, pedir aclaración, suspenderla, cambiar la duración y finalizarla', async ({ page }) => {
  const servidor = crearServidor();
  await simularBackend(page, (ruta, metodo, peticion, responder) => servidor.atender(ruta, metodo, peticion, responder));

  const etapa = narrador(page, 'CU-27');
  const evaluacion = page.locator('app-evaluacion-tiendas');
  const miTienda = page.locator('app-estado-tienda');
  const estado = miTienda.getByRole('region', { name: 'Estado de la tienda' });
  const avisoAdmin = (texto: string) => evaluacion.getByRole('status').filter({ hasText: texto });

  const entrarComoAdmin = async () => {
    await iniciarSesion(page, ADMIN);
    await abrirDesdeMenu(page, 'Opciones de administración', 'Evaluar tiendas');
    await evaluacion.getByRole('list', { name: 'Tiendas con antecedentes' }).getByRole('button').first().click();
    await expect(evaluacion.getByRole('heading', { name: TIENDA })).toBeVisible();
  };
  const entrarComoVendedor = async () => {
    await iniciarSesion(page, VENDEDOR);
    await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Estado de mi tienda');
    await expect(estado).toBeVisible();
  };
  const salir = (panel: typeof evaluacion) => salirDe(page, panel);

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('La administración revisa los antecedentes de la tienda', async () => {
    await etapa('La administración inicia sesión y abre «Evaluar tiendas»');
    await iniciarSesion(page, ADMIN);
    await abrirDesdeMenu(page, 'Opciones de administración', 'Evaluar tiendas');
    const lista = evaluacion.getByRole('list', { name: 'Tiendas con antecedentes' });
    await expect(lista).toContainText(TIENDA);
    await expect(lista).toContainText('Reincidente');
    await etapa('Solo aparecen tiendas con incumplimientos confirmados; esta es reincidente');
    await lista.getByRole('button').first().click();
    await expect(evaluacion.getByText('Reclamación resuelta con reembolso al comprador').first()).toBeVisible();
    await expect(evaluacion.getByText('Contenido retirado por moderación')).toBeVisible();
    await expect(evaluacion).toContainText('3 antecedente(s) en los últimos 180 días');
    await etapa('Dos reclamaciones con reembolso y una publicación retirada por moderación');
  });

  await test.step('Antes de decidir, le pide una aclaración al vendedor', async () => {
    await evaluacion.getByRole('button', { name: 'Pedir aclaración al vendedor', exact: true }).click();
    await escribir(evaluacion.getByLabel('Pregunta para el vendedor'), PREGUNTA);
    await evaluacion.getByRole('button', { name: 'Enviar solicitud', exact: true }).click();
    await expect(avisoAdmin('Se pidió la aclaración al vendedor.')).toBeVisible();
    await expect(evaluacion).toContainText('Esperando respuesta');
    await etapa('El vendedor tiene 72 horas para responder y recibe una notificación');
    await salir(evaluacion);
  });

  await test.step('El vendedor responde la aclaración', async () => {
    await etapa('El vendedor inicia sesión y abre «Estado de mi tienda»');
    await entrarComoVendedor();
    await expect(estado).toContainText('Operando');
    await expect(miTienda).toContainText(PREGUNTA);
    await miTienda.getByRole('button', { name: 'Enviar respuesta', exact: true }).click();
    await expect(miTienda.getByText('Escribe tu respuesta antes de enviarla.')).toBeVisible();
    await etapa('La tienda sigue operando; una respuesta vacía no se envía');
    await escribir(miTienda.getByLabel('Tu respuesta'), RESPUESTA);
    await miTienda.getByRole('button', { name: 'Enviar respuesta', exact: true }).click();
    await expect(miTienda.getByRole('status').filter({ hasText: 'Tu respuesta quedó registrada' })).toBeVisible();
    await expect(miTienda).toContainText('Respondida');
    await salir(miTienda);
  });

  await test.step('La administración lee la respuesta y suspende la tienda con resumen previo', async () => {
    await etapa('La administración lee la respuesta y decide suspender temporalmente');
    await entrarComoAdmin();
    await expect(evaluacion).toContainText(RESPUESTA);
    await evaluacion.getByRole('button', { name: 'Suspender temporalmente', exact: true }).click();
    const motivo = evaluacion.getByLabel('Motivo de la suspensión');
    await motivo.fill('corto');
    await evaluacion.getByRole('button', { name: 'Ver resumen', exact: true }).click();
    await expect(evaluacion.getByText('El motivo debe tener entre 10 y 1000 caracteres')).toBeVisible();
    await expect(motivo).toHaveAttribute('aria-invalid', 'true');
    await etapa('Un motivo demasiado corto se rechaza junto al campo');
    await motivo.fill('');
    await escribir(motivo, MOTIVO);
    await evaluacion.getByRole('button', { name: 'Ver resumen', exact: true }).click();
    const resumen = evaluacion.getByRole('region', { name: 'Resumen de la suspensión' });
    await resumen.scrollIntoViewIfNeeded();
    await expect(resumen).toContainText('quedará suspendida 7 día(s)');
    await expect(resumen).toContainText('Publicar productos nuevos');
    await expect(resumen).toContainText('Atender los pedidos existentes hasta su entrega');
    await expect(resumen).toContainText('2 pedido(s)');
    await etapa('Antes de confirmar ve fechas, qué se bloquea, qué sigue permitido y lo que queda en curso');
    await evaluacion.getByRole('button', { name: 'Confirmar suspensión', exact: true }).click();
    await expect(avisoAdmin('La tienda quedó suspendida.')).toBeVisible();
    expect(servidor.estadoTienda()).toBe('SUSPENDED');
    await expect(evaluacion.getByRole('region', { name: 'Suspensión vigente' }))
      .toContainText(fechaVisible(servidor.suspension().endsAt));
    await salir(evaluacion);
  });

  await test.step('El vendedor ve la suspensión, su motivo y lo que puede hacer', async () => {
    await etapa('El vendedor vuelve a entrar: su tienda está suspendida');
    await entrarComoVendedor();
    await expect(estado).toContainText('Suspendida temporalmente');
    await expect(estado).toContainText(fechaVisible(servidor.suspension().endsAt));
    await expect(estado).toContainText(MOTIVO);
    await expect(estado).toContainText('Recibir nuevas compras: sus productos dejan de mostrarse en el catálogo');
    await expect(estado).toContainText('Atender los pedidos existentes hasta su entrega');
    await etapa('Sabe hasta cuándo, por qué, qué no puede hacer y que debe seguir atendiendo sus pedidos');
    await pausa(page, 2000);
    await salir(miTienda);
  });

  await test.step('La administración reduce la duración y luego la finaliza antes de tiempo', async () => {
    await etapa('La administración revisa el caso y reduce la suspensión');
    await entrarComoAdmin();
    const finAnterior = fechaVisible(servidor.suspension().endsAt);
    await evaluacion.getByRole('button', { name: 'Modificar duración', exact: true }).click();
    await evaluacion.getByLabel('Nueva duración total (días, desde el inicio)').fill('3');
    await escribir(evaluacion.getByLabel('Justificación del cambio'), JUSTIFICACION);
    await evaluacion.getByRole('button', { name: 'Guardar', exact: true }).click();
    await expect(avisoAdmin('Se cambió la duración.')).toBeVisible();
    const vigente = evaluacion.getByRole('region', { name: 'Suspensión vigente' });
    await expect(vigente).toContainText(fechaVisible(servidor.suspension().endsAt));
    await expect(vigente).not.toContainText(finAnterior);
    await etapa('Cada cambio queda con su justificación y quién lo hizo');
    await evaluacion.getByRole('button', { name: 'Finalizar anticipadamente', exact: true }).click();
    await escribir(evaluacion.getByLabel('Motivo para levantarla antes'), MOTIVO_FIN);
    await evaluacion.getByRole('button', { name: 'Guardar', exact: true }).click();
    await expect(avisoAdmin('La suspensión terminó y la tienda volvió a operar.')).toBeVisible();
    expect(servidor.estadoTienda()).toBe('ACTIVE');
    const suspensiones = evaluacion.locator('ul.lista').filter({ hasText: 'Levantada antes de su fin' });
    await suspensiones.scrollIntoViewIfNeeded();
    await expect(suspensiones).toContainText('Cambio de duración');
    await expect(suspensiones).toContainText(`por la cuenta #${ADMIN.accountId}`);
    await etapa('La suspensión termina antes de su fecha y la tienda vuelve a operar');
    await salir(evaluacion);
  });

  await test.step('El vendedor ve su tienda operando y el historial completo', async () => {
    await etapa('El vendedor entra por última vez');
    await entrarComoVendedor();
    await expect(estado).toContainText('Operando');
    await expect(estado).toContainText('Tu tienda opera con normalidad.');
    const historial = miTienda.locator('ul.historial');
    await historial.scrollIntoViewIfNeeded();
    await expect(historial).toContainText('Levantada antes de su fin');
    await expect(historial).toContainText(JUSTIFICACION);
    await etapa('Fin: la tienda opera de nuevo y conserva el historial de la suspensión');
    await pausa(page, 4000);
  });
});

/** Cierra el panel abierto y la sesión, para que entre la otra cuenta. */
async function salirDe(page: Page, panel: ReturnType<Page['locator']>): Promise<void> {
  await panel.getByRole('button', { name: 'Cerrar', exact: true }).click();
  await cerrarSesion(page);
}
