import { test, expect, Page, Route } from '@playwright/test';

/**
 * CU-21: recorrido visual de la moderación de un caso de reportes desde soporte, en un solo navegador.
 * La API está simulada aquí con estado propio. La respuesta del reportante es CU-20 y tiene su propia demostración;
 * aquí se simula entre etapas. Las reglas reales las prueban las pruebas del módulo de moderación del backend.
 */

const AGENTE = { accountId: 6, email: 'soporte.demo@example.com', roles: ['SOPORTE'], activeRole: 'SOPORTE' };
const AGENTE_ID = 'soporte.demo@example.com';
const CASO = 87;
const PUBLICACION = { id: '58', titulo: 'Reloj inteligente Serie 8 original',
  texto: 'Sellado, garantía oficial de 2 años. Precio de lanzamiento: $ 89.000.' };

const MEDIDA = 'Oculto por precaución mientras se verifica el precio y la autenticidad.';
const PREGUNTA = '¿Tienes un enlace o captura del precio oficial para adjuntar al caso?';
const RESPUESTA = 'Sí: en la tienda oficial del fabricante aparece a $ 1.549.000. Adjunté la captura al radicar.';
const DECISION = 'Precio y garantía falsos según la evidencia de dos reportantes; se retira la publicación.';

/** Fecha local sin zona, como la envía el backend (LocalDateTime), desplazada en horas desde ahora. */
function fecha(horas: number): string {
  const d = new Date(Date.now() + horas * 3_600_000);
  const n = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:00`;
}

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let sesion: typeof AGENTE | null = null;
  const decisiones: any[] = [];
  const solicitudes: any[] = [];
  const caso: any = {
    id: CASO, contentType: 'PUBLICACION', contentId: PUBLICACION.id, status: 'PENDIENTE', reportCount: 2,
    assignedAgentId: null, contentState: 'VISIBLE', openedAt: fecha(-5), resolvedAt: null, version: 1,
    content: { title: PUBLICACION.titulo, text: PUBLICACION.texto, ownerId: 'tienda-9', attributes: {} },
    reports: [
      { id: 214, reporterId: 'cuenta-2', reason: 'INFORMACION_ENGANOSA', createdAt: fecha(-5),
        description: 'Dice «original con garantía oficial» a $ 89.000, pero el modelo real cuesta más de $ 1.500.000.',
        evidences: [{ id: 1, fileUrl: '/assets/catalogo-demo/photo-1523275335684-37898b6baf30.jpg', fileType: 'image/jpeg' }] },
      { id: 219, reporterId: 'cuenta-15', reason: 'POSIBLE_FRAUDE', createdAt: fecha(-3),
        description: 'Pagué y la tienda me pide transferir aparte para «liberar» el envío.', evidences: [] }
    ],
    informationRequests: solicitudes, decisions: decisiones,
    history: [{ caseId: 41, openedAt: fecha(-24 * 40), resolvedAt: fecha(-24 * 39), reportCount: 1,
      decisions: [{ id: 3, agentId: 'otro-agente', decision: 'MANTENER', measureResult: 'APLICADA', createdAt: fecha(-24 * 39),
        justification: 'Sin evidencia suficiente en ese momento.' }] }]
  };
  const resumen = () => ({ id: caso.id, contentType: caso.contentType, contentId: caso.contentId, status: caso.status,
    reportCount: caso.reportCount, assignedAgentId: caso.assignedAgentId, contentState: caso.contentState,
    openedAt: caso.openedAt, resolvedAt: caso.resolvedAt, version: caso.version });

  return {
    caso: () => caso,
    /** CU-20: el reportante responde desde «Mis reportes». */
    reportanteResponde() {
      Object.assign(solicitudes[0], { status: 'RESPONDIDA', respondedAt: fecha(0), responseText: RESPUESTA });
      caso.status = 'EN_REVISION';
      caso.version++;
    },

    async atender(route: Route) {
      const req = route.request();
      const ruta = new URL(req.url()).pathname;
      const method = req.method();
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });
      const base = `/api/support/moderation/cases/${CASO}`;

      if (ruta === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
      if (ruta === '/api/auth/me') return sesion ? json(sesion) : json({ message: 'Sin sesión' }, 401);
      if (ruta === '/api/auth/login') { sesion = AGENTE; return json({}); }
      if (ruta === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }

      if (ruta === '/api/support/moderation/cases') {
        const pedido = new URL(req.url()).searchParams.get('status');
        const items = (pedido === 'RESUELTO') === (caso.status === 'RESUELTO') ? [resumen()] : [];
        return json({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1 });
      }
      if (ruta === base) return json(caso);
      if (ruta === `${base}/claim` && method === 'POST') {
        Object.assign(caso, { assignedAgentId: AGENTE_ID, status: 'EN_REVISION', version: caso.version + 1 });
        return json(resumen());
      }
      if (ruta === `${base}/information-requests` && method === 'POST') {
        const datos = req.postDataJSON();
        const solicitud = { id: solicitudes.length + 1, target: datos.target, targetUserId: datos.targetUserId,
          message: datos.message, requestedBy: AGENTE_ID, requestedAt: fecha(0), dueAt: fecha(72), status: 'ABIERTA',
          respondedAt: null, responseText: null };
        solicitudes.push(solicitud);
        Object.assign(caso, { status: 'INFO_SOLICITADA', version: caso.version + 1 });
        return json(solicitud, 201);
      }
      if (ruta === `${base}/decisions` && method === 'POST') {
        const datos = req.postDataJSON();
        if (datos.expectedVersion !== caso.version) return json({ message: 'El caso cambió; recárgalo.' }, 409);
        const vigente = solicitudes.some(s => s.status === 'ABIERTA');
        if (vigente && datos.decision !== 'OCULTAR_TEMPORALMENTE') {
          return json({ message: 'Hay una solicitud de información vigente.' }, 409);
        }
        decisiones.push({ id: decisiones.length + 10, agentId: AGENTE_ID, decision: datos.decision,
          justification: datos.justification, measureResult: 'APLICADA', createdAt: fecha(0) });
        if (datos.decision === 'OCULTAR_TEMPORALMENTE') {
          Object.assign(caso, { contentState: 'OCULTO_TEMPORAL', version: caso.version + 1 });
        } else {
          Object.assign(caso, { status: 'RESUELTO', resolvedAt: fecha(0), version: caso.version + 1,
            contentState: datos.decision === 'RETIRAR' ? 'RETIRADO' : 'VISIBLE' });
        }
        return json({ decisionId: decisiones.at(-1).id, decision: datos.decision, measureResult: 'APLICADA',
          caseStatus: caso.status, contentState: caso.contentState });
      }

      // Lo que la página principal pide de paso y no es parte de CU-21.
      if (ruta === '/api/recommendations') return json({ products: [], strategy: 'DEMO_CONTROLADA' });
      if (ruta === '/api/cart') return json({ items: [], total: 0 });
      if (ruta === '/api/notifications') return json({ items: [], hasMore: false });
      return json([]);
    }
  };
}

test('CU-21: tomar un caso, ocultar por precaución, pedir información y retirar la publicación', async ({ page }) => {
  const servidor = crearServidor();
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', route => servidor.atender(route));

  const etapa = (texto: string) => narrar(page, texto);
  const cola = page.locator('app-cola-soporte');
  const texto = cola.getByRole('textbox', { name: 'Mensaje o justificación' });
  const aviso = (mensaje: string) => expect(cola.getByText(mensaje, { exact: true })).toBeVisible();

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('Soporte abre la cola y revisa el caso', async () => {
    await etapa('El agente de soporte inicia sesión y abre «Moderación de reportes»');
    await iniciarSesion(page, AGENTE.email);
    await page.getByRole('button', { name: 'Opciones de soporte', exact: true }).click();
    await page.getByRole('button', { name: 'Moderación de reportes', exact: true }).click();
    await expect(cola.getByText('PUBLICACION · 58')).toBeVisible();
    await expect(cola).toContainText('2 reportes');
    await etapa('Dos personas reportaron la misma publicación: es un solo caso');
    await cola.getByText('PUBLICACION · 58').click();
    await expect(cola.getByText(PUBLICACION.titulo)).toBeVisible();
    await expect(cola).toContainText('INFORMACION_ENGANOSA · cuenta-2');
    await expect(cola).toContainText('POSIBLE_FRAUDE · cuenta-15');
    await expect(cola.getByRole('link', { name: 'Evidencia (image/jpeg)' })).toBeVisible();
    await expect(cola).toContainText('Caso #41 · 1 reporte(s)');
    await etapa('Ve los reportes, la evidencia y un caso anterior que se mantuvo');
  });

  await test.step('Toma el caso y oculta la publicación por precaución', async () => {
    await cola.getByRole('button', { name: 'Tomar caso', exact: true }).click();
    await aviso('Caso tomado.');
    await expect(cola).toContainText(`Agente: ${AGENTE_ID}`);
    await etapa('Lo toma: queda asignado a este agente');
    await expect(cola).toContainText('Escribe al menos 10 caracteres');
    await etapa('Toda acción exige una justificación de al menos 10 caracteres');
    await texto.pressSequentially(MEDIDA, { delay: 12 });
    await cola.getByRole('button', { name: 'Ocultar temporalmente', exact: true }).click();
    await aviso('Decisión registrada y aplicada.');
    await expect(cola).toContainText('contenido: OCULTO_TEMPORAL');
    await etapa('Oculta la publicación mientras investiga: deja de verse en el catálogo');
  });

  await test.step('Pide información y no puede decidir mientras espera', async () => {
    await etapa('Pide al primer reportante una prueba del precio oficial (72 horas)');
    await texto.pressSequentially(PREGUNTA, { delay: 12 });
    await cola.getByRole('button', { name: 'Pedir información (plazo 72 h)', exact: true }).click();
    await aviso('Solicitud enviada; el destinatario tiene 72 horas para responder.');
    await expect(cola).toContainText('REPORTADOR (cuenta-2) · ABIERTA');
    await texto.fill('Intento retirar antes de la respuesta.');
    await expect(cola).toContainText('Hay una solicitud de información vigente');
    await etapa('Con la solicitud abierta, no se puede mantener ni retirar todavía');
    await texto.fill('');
  });

  await test.step('Con la respuesta, retira la publicación', async () => {
    servidor.reportanteResponde();
    await etapa('El reportante responde desde «Mis reportes» (CU-20)');
    await page.getByRole('button', { name: 'Volver', exact: true }).click();
    await cola.getByText('PUBLICACION · 58').click();
    await expect(cola).toContainText(`Respuesta: ${RESPUESTA}`);
    await etapa('Con la respuesta y la evidencia, decide retirar la publicación');
    await texto.pressSequentially(DECISION, { delay: 12 });
    await cola.getByRole('button', { name: 'Retirar', exact: true }).click();
    // Al resolverse, la tarjeta de acciones (y su aviso de éxito) desaparece: se comprueba el estado del caso.
    await expect(cola).toContainText('RESUELTO · contenido: RETIRADO');
    await expect(cola.getByRole('button', { name: 'Tomar caso' })).toHaveCount(0);
    await expect(cola).toContainText(`RETIRAR · ${AGENTE_ID}`);
    expect(servidor.caso()).toMatchObject({ status: 'RESUELTO', contentState: 'RETIRADO' });
  });

  await test.step('El caso pasa a los resueltos', async () => {
    await page.getByRole('button', { name: 'Volver', exact: true }).click();
    await expect(cola.getByText('No hay casos abiertos.')).toBeVisible();
    await cola.locator('ion-select').filter({ hasText: 'Mostrar' }).click();
    await page.getByRole('radio', { name: 'Casos resueltos', exact: true }).click();
    await page.getByRole('button', { name: 'OK', exact: true }).click();
    await expect(cola.getByText('PUBLICACION · 58')).toBeVisible();
    await expect(cola).toContainText('RESUELTO');
    await etapa('Fin: el caso quedó resuelto, con la decisión y su justificación registradas');
    // Pausa exclusiva de presentación para leer el resultado final.
    await page.waitForTimeout(4000);
  });
});

/** Aviso fijo en la esquina que narra la etapa; solo es para la presentación. */
async function narrar(page: Page, texto: string): Promise<void> {
  console.log(texto);
  await page.evaluate(texto => {
    document.querySelector('#cu21-demo-nota')?.remove();
    const nota = document.createElement('aside');
    nota.id = 'cu21-demo-nota';
    nota.textContent = `CU-21 · Demostración con datos controlados · ${texto}`;
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
