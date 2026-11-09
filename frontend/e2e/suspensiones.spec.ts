import { test, expect, Page } from '@playwright/test';

const suspension = {
  id: 5, storeId: 1, reason: 'Reincidencia en productos defectuosos', startsAt: '2026-11-01T10:00:00',
  endsAt: '2026-11-08T10:00:00', status: 'ACTIVE', appliedBy: null, closedAt: null, closedBy: null, closeReason: null,
  changes: [{ type: 'APPLIED', previousEndsAt: null, newEndsAt: '2026-11-08T10:00:00', justification: 'Motivo',
    actorId: null, reactivationBlocked: false, createdAt: '2026-11-01T10:00:00' }]
};

async function simular(page: Page, rol: 'VENDEDOR' | 'ADMIN',
                       manejar: (ruta: string, metodo: string, cuerpo: any) => { status?: number; body: unknown } | null) {
  const enviados: { ruta: string; cuerpo: any; csrf: string | undefined }[] = [];
  await page.route(/^https:\/\/(?:fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, r => r.abort());
  await page.route('**/api/**', async route => {
    const request = route.request();
    const ruta = new URL(request.url()).pathname;
    const reply = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (ruta === '/api/auth/me') return reply({ accountId: 9, email: 'x@example.test', roles: [rol], activeRole: rol });
    if (ruta === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    const cuerpo = request.method() === 'GET' ? null : request.postDataJSON();
    if (cuerpo) enviados.push({ ruta, cuerpo, csrf: request.headers()['x-csrf-token'] });
    const respuesta = manejar(ruta, request.method(), cuerpo);
    if (respuesta) return reply(respuesta.body, respuesta.status ?? 200);
    if (ruta.endsWith('/notifications')) return reply({ items: [], hasMore: false });
    if (ruta === '/api/recommendations') return reply({ userId: 9, strategy: 'test', products: [] });
    return reply([]);
  });
  await page.goto('/');
  return enviados;
}

test('el vendedor ve su suspensión y responde la aclaración dentro del plazo', async ({ page }) => {
  let respondida = false;
  const estado = () => ({
    status: 'SUSPENDED', statusReason: 'Suspensión temporal', activeSuspension: suspension, suspensions: [suspension],
    blocked: ['Publicar productos nuevos', 'Recibir nuevas compras: sus productos dejan de mostrarse en el catálogo'],
    allowed: ['Atender los pedidos existentes hasta su entrega'],
    clarifications: [{ id: 3, message: 'Explica las devoluciones de octubre', requestedAt: '2026-11-01T09:00:00',
      dueAt: '2026-11-04T09:00:00', status: respondida ? 'ANSWERED' : 'OPEN',
      response: respondida ? 'El lote venía defectuoso' : null, respondedAt: respondida ? '2026-11-02T09:00:00' : null }]
  });
  const enviados = await simular(page, 'VENDEDOR', (ruta, metodo) => {
    if (ruta === '/api/seller/store/standing') return { body: estado() };
    if (ruta === '/api/seller/store/standing/clarifications/3/response' && metodo === 'POST') {
      respondida = true;
      return { body: estado().clarifications[0] };
    }
    return null;
  });

  await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
  await page.getByRole('button', { name: 'Estado de mi tienda', exact: true }).click();
  const tienda = page.getByRole('region', { name: 'Estado de la tienda' });
  await expect(tienda).toContainText('Suspendida temporalmente');
  await expect(tienda).toContainText('08/11/2026 10:00');
  await expect(tienda).toContainText('Reincidencia en productos defectuosos');
  await expect(tienda).toContainText('Publicar productos nuevos');
  await expect(tienda).toContainText('Atender los pedidos existentes');

  await page.getByRole('button', { name: 'Enviar respuesta' }).click();
  await expect(page.getByText('Escribe tu respuesta antes de enviarla.')).toBeVisible();
  await page.getByLabel('Tu respuesta').fill('El lote venía defectuoso');
  await page.getByRole('button', { name: 'Enviar respuesta' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Tu respuesta quedó registrada' })).toBeVisible();
  await expect(page.getByText('Respondida')).toBeVisible();
  expect(enviados).toEqual([{ ruta: '/api/seller/store/standing/clarifications/3/response',
    cuerpo: { response: 'El lote venía defectuoso' }, csrf: 'csrf' }]);
});

test('el administrador revisa antecedentes, ve el resumen y confirma la suspensión', async ({ page }) => {
  let suspendida = false;
  const evaluacion = () => ({
    storeId: 1, storeName: 'Tienda principal', storeStatus: suspendida ? 'SUSPENDED' : 'ACTIVE', statusReason: null,
    pending: { ordersInProgress: 2, openClaims: 1, openReturns: 0 }, recentRecords: 2, recidivist: true,
    records: [{ type: 'CLAIM', referenceId: 4, decidedAt: '2026-10-20T10:00:00', subject: 'Lampara (pedido #12)',
      decision: 'REFUND_GRANTED', detail: 'Llegó rota', amount: 55000 },
    { type: 'MODERATION', referenceId: 9, decidedAt: '2026-10-25T10:00:00', subject: 'Publicación: Lampara',
      decision: 'RETIRAR', detail: 'Imagen engañosa', amount: null }],
    activeSuspension: suspendida ? suspension : null, suspensions: suspendida ? [suspension] : [], reviews: [],
    clarifications: []
  });
  const enviados = await simular(page, 'ADMIN', (ruta, metodo, cuerpo) => {
    if (ruta === '/api/admin/store-evaluations') {
      return { body: [{ storeId: 1, storeName: 'Tienda principal', storeStatus: 'ACTIVE', claims: 1, moderations: 1,
        suspensions: 0, recentRecords: 2, lastRecordAt: '2026-10-25T10:00:00', recidivist: true,
        activeSuspension: false, openClarification: false }] };
    }
    if (ruta === '/api/admin/store-evaluations/1') return { body: evaluacion() };
    if (ruta === '/api/admin/store-evaluations/1/suspensions/preview') {
      if (cuerpo.reason.length < 10) {
        return { status: 400, body: { code: 'SUSPENSION_REASON_REQUIRED', message: 'El motivo debe tener entre 10 y 1000 caracteres',
          details: { field: 'reason' } } };
      }
      return { body: { storeId: 1, storeName: 'Tienda principal', reason: cuerpo.reason, durationDays: cuerpo.durationDays,
        startsAt: '2026-11-01T10:00:00', endsAt: '2026-11-08T10:00:00', blocked: ['Publicar productos nuevos'],
        allowed: ['Atender reclamaciones y devoluciones'], pending: { ordersInProgress: 2, openClaims: 1, openReturns: 0 },
        warning: null } };
    }
    if (ruta === '/api/admin/store-evaluations/1/suspensions' && metodo === 'POST') {
      suspendida = true;
      return { status: 201, body: suspension };
    }
    return null;
  });

  await page.getByRole('button', { name: 'Opciones de administración', exact: true }).click();
  await page.getByRole('button', { name: 'Evaluar tiendas', exact: true }).click();
  const lista = page.getByRole('list', { name: 'Tiendas con antecedentes' });
  await expect(lista).toContainText('Reincidente');
  await lista.getByRole('button').first().click();
  await expect(page.getByText('Reclamación resuelta con reembolso al comprador')).toBeVisible();
  await expect(page.getByText('Contenido retirado por moderación')).toBeVisible();

  await page.getByRole('button', { name: 'Suspender temporalmente' }).click();
  await page.getByLabel('Motivo de la suspensión').fill('corto');
  await page.getByRole('button', { name: 'Ver resumen' }).click();
  await expect(page.getByText('El motivo debe tener entre 10 y 1000 caracteres')).toBeVisible();
  await expect(page.getByLabel('Motivo de la suspensión')).toHaveAttribute('aria-invalid', 'true');

  await page.getByLabel('Motivo de la suspensión').fill('Reincidencia en productos defectuosos');
  await page.getByRole('button', { name: 'Ver resumen' }).click();
  const resumen = page.getByRole('region', { name: 'Resumen de la suspensión' });
  await expect(resumen).toContainText('7 día(s)');
  await expect(resumen).toContainText('Publicar productos nuevos');
  await expect(resumen).toContainText('2 pedido(s)');
  await page.getByRole('button', { name: 'Confirmar suspensión' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'La tienda quedó suspendida.' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Suspensión vigente' })).toContainText('08/11/2026 10:00');

  const suspensiones = enviados.filter(envio => envio.ruta === '/api/admin/store-evaluations/1/suspensions');
  expect(suspensiones).toEqual([{ ruta: '/api/admin/store-evaluations/1/suspensions',
    cuerpo: { reason: 'Reincidencia en productos defectuosos', durationDays: 7 }, csrf: 'csrf' }]);
});

test('al finalizar avisa si otra restricción impidió reactivar la tienda', async ({ page }) => {
  await simular(page, 'ADMIN', (ruta) => {
    if (ruta === '/api/admin/store-evaluations') {
      return { body: [{ storeId: 1, storeName: 'Tienda principal', storeStatus: 'SUSPENDED', claims: 1, moderations: 0,
        suspensions: 1, recentRecords: 1, lastRecordAt: null, recidivist: true, activeSuspension: true, openClarification: false }] };
    }
    if (ruta === '/api/admin/store-evaluations/1') {
      return { body: { storeId: 1, storeName: 'Tienda principal', storeStatus: 'SUSPENDED', statusReason: null,
        pending: { ordersInProgress: 0, openClaims: 0, openReturns: 0 }, records: [], recentRecords: 1, recidivist: true,
        activeSuspension: suspension, suspensions: [suspension], reviews: [], clarifications: [] } };
    }
    if (ruta === '/api/admin/store-suspensions/5/lift') return { body: { suspensionId: 5, status: 'LIFTED', reactivated: false } };
    return null;
  });
  await page.getByRole('button', { name: 'Opciones de administración', exact: true }).click();
  await page.getByRole('button', { name: 'Evaluar tiendas', exact: true }).click();
  await page.getByRole('list', { name: 'Tiendas con antecedentes' }).getByRole('button').first().click();
  await page.getByRole('button', { name: 'Finalizar anticipadamente' }).click();
  await page.getByLabel('Motivo para levantarla antes').fill('El vendedor corrigió los incumplimientos');
  await page.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'no se reactivó porque tiene otra restricción vigente' }))
    .toBeVisible();
});
