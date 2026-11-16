import { test, expect, Page, Route } from '@playwright/test';

/**
 * CU-22: recorrido visual de una restricción de acceso, de la evaluación al retiro, en un solo navegador.
 * La API está simulada aquí con estado propio: restringir, responder y retirar cambian lo que se ve después.
 * Las reglas reales del backend las prueban las pruebas de integración (AccountRestrictionIntegrationTests).
 */

type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN';
interface Cuenta { accountId: number; email: string; roles: Rol[] }

const ADMIN: Cuenta = { accountId: 7, email: 'admin.demo@example.com', roles: ['ADMIN'] };
const EVALUADA: Cuenta = { accountId: 3, email: 'demo@marketplace.local', roles: ['COMPRADOR', 'VENDEDOR'] };
const CUENTAS = [ADMIN, EVALUADA];

const PREGUNTA = '¿Puedes explicar por qué publicaste un producto falsificado y cómo vas a evitarlo?';
const MOTIVO = 'Publicación de productos falsificados y reclamación con reembolso en el mismo mes.';
const RESPUESTA = 'Fue un proveedor nuevo; ya retiré sus productos y solo vendo con factura del fabricante.';
const MOTIVO_RETIRO = 'La vendedora explicó lo ocurrido y retiró los productos del proveedor.';

/** Fecha local sin zona, como la envía el backend (LocalDateTime). */
function fecha(diasDesdeHoy: number, hora = '10:30:00'): string {
  const dia = new Date(Date.now() + diasDesdeHoy * 86_400_000);
  const dosDigitos = (n: number) => String(n).padStart(2, '0');
  return `${dia.getFullYear()}-${dosDigitos(dia.getMonth() + 1)}-${dosDigitos(dia.getDate())}T${hora}`;
}

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let sesion: (Cuenta & { activeRole: Rol | null }) | null = null;
  const solicitudes: any[] = [];
  const restricciones: any[] = [];
  const antecedentes = [
    { type: 'CLAIM', referenceId: 41, decidedAt: fecha(-10), subject: 'Licuadora de vaso de vidrio',
      decision: 'REFUND_GRANTED', detail: 'El producto llegó roto y la tienda no respondió.', amount: 189000 },
    { type: 'MODERATION', referenceId: 29, decidedAt: fecha(-28), subject: 'Publicación: Juego de ollas antiadherentes',
      decision: 'RETIRAR', detail: 'La publicación ofrecía un producto falsificado.', amount: null }
  ];
  const vigentes = () => restricciones.filter(r => r.status === 'ACTIVE');
  const restringido = (rol: Rol) => vigentes().some(r => r.scope === 'ACCOUNT' || r.role === rol);

  return {
    vigentes,
    async atender(route: Route) {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const method = req.method();
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

      if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
      if (path === '/api/auth/me') return sesion ? json(sesion) : json({ message: 'Sin sesión' }, 401);
      if (path === '/api/auth/login') {
        const email = new URLSearchParams(req.postData() ?? '').get('email');
        const cuenta = CUENTAS.find(c => c.email === email);
        if (!cuenta) return json({ message: 'Credenciales inválidas' }, 401);
        // Con un solo rol queda activo de una vez; con varios, la persona elige.
        sesion = { ...cuenta, activeRole: cuenta.roles.length === 1 ? cuenta.roles[0] : null };
        return json({});
      }
      if (path === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }
      if (path === '/api/auth/active-role' && sesion) {
        const rol = req.postDataJSON().role as Rol;
        if (sesion.accountId === EVALUADA.accountId && restringido(rol)) {
          return json({ code: 'ROLE_RESTRICTED',
            message: 'Este rol tiene una restricción vigente. Consulta los detalles en «Mi cuenta».' }, 403);
        }
        sesion = { ...sesion, activeRole: rol };
        return json(sesion);
      }

      // Administración (rol ADMIN).
      if (path === '/api/admin/accounts' && method === 'GET') {
        const cuenta = CUENTAS.find(c => c.email === new URL(req.url()).searchParams.get('email'));
        return cuenta
          ? json({ id: cuenta.accountId, email: cuenta.email, active: true, roles: cuenta.roles })
          : json({ message: 'No existe una cuenta con ese correo.' }, 404);
      }
      if (path === `/api/admin/accounts/${EVALUADA.accountId}/evaluation`) {
        return json({
          account: { id: EVALUADA.accountId, email: EVALUADA.email, active: true, roles: EVALUADA.roles },
          records: antecedentes, recentRecords: antecedentes.length, recidivist: antecedentes.length >= 2,
          activeRestrictions: vigentes(), restrictions: restricciones, informationRequests: solicitudes
        });
      }
      if (path === `/api/admin/accounts/${EVALUADA.accountId}/information-requests` && method === 'POST') {
        const solicitud = { id: solicitudes.length + 1, message: req.postDataJSON().message, requestedAt: fecha(0),
          dueAt: fecha(3), status: 'OPEN', response: null, respondedAt: null };
        solicitudes.push(solicitud);
        return json(solicitud, 201);
      }
      if (path === `/api/admin/accounts/${EVALUADA.accountId}/restrictions` && method === 'POST') {
        const datos = req.postDataJSON();
        const restriccion = { id: restricciones.length + 1, accountId: EVALUADA.accountId, scope: datos.scope,
          role: datos.role, reason: datos.reason, permanent: datos.permanent, startsAt: fecha(0, '10:35:00'),
          endsAt: datos.permanent ? null : fecha(datos.durationDays, '10:35:00'), status: 'ACTIVE',
          appliedBy: ADMIN.accountId, closedAt: null, closedBy: null, closeReason: null,
          changes: [{ type: 'APPLIED', previousEndsAt: null, newEndsAt: null, justification: datos.reason,
            actorId: ADMIN.accountId, createdAt: fecha(0, '10:35:00') }] };
        restricciones.push(restriccion);
        return json(restriccion, 201);
      }
      const retiro = path.match(/^\/api\/admin\/restrictions\/(\d+)\/withdraw$/);
      if (retiro && method === 'POST') {
        const restriccion = restricciones.find(r => r.id === Number(retiro[1]));
        const motivo = req.postDataJSON().reason;
        Object.assign(restriccion, { status: 'WITHDRAWN', closedAt: fecha(0, '11:10:00'), closedBy: ADMIN.accountId,
          closeReason: motivo });
        restriccion.changes.push({ type: 'WITHDRAWN', previousEndsAt: restriccion.endsAt, newEndsAt: null,
          justification: motivo, actorId: ADMIN.accountId, createdAt: fecha(0, '11:10:00') });
        return json({ restrictionId: restriccion.id, status: 'WITHDRAWN' });
      }

      // La propia cuenta: «Mi cuenta», con o sin rol activo. No muestra quién aplicó la restricción.
      if (path === '/api/account/standing') {
        return json({ activeRestrictions: vigentes().map(r => ({ ...r, appliedBy: null })),
          informationRequests: solicitudes });
      }
      const respuesta = path.match(/^\/api\/account\/information-requests\/(\d+)\/answer$/);
      if (respuesta && method === 'POST') {
        const solicitud = solicitudes.find(s => s.id === Number(respuesta[1]));
        Object.assign(solicitud, { status: 'ANSWERED', response: req.postDataJSON().response, respondedAt: fecha(0, '10:50:00') });
        return json(solicitud);
      }

      // Lo que la página principal pide de paso y no es parte de CU-22.
      if (path === '/api/recommendations') return json({ products: [], strategy: 'DEMO_CONTROLADA' });
      if (path === '/api/cart') return json({ items: [], total: 0 });
      if (path === '/api/notifications') return json({ items: [], hasMore: false });
      return json([]);
    }
  };
}

test('CU-22: evaluar, pedir aclaración, restringir un rol, responder y retirar la restricción', async ({ page }) => {
  const servidor = crearServidor();
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', route => servidor.atender(route));

  const etapa = (texto: string) => narrar(page, texto);
  const panelAdmin = page.locator('app-admin-restricciones');
  const miCuenta = page.locator('app-estado-cuenta');

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('La administración busca la cuenta y revisa sus antecedentes', async () => {
    await etapa('La administración inicia sesión');
    await iniciarSesion(page, ADMIN.email);
    await etapa('Abre «Restricciones de cuentas» y busca la cuenta');
    await abrirDesdeMenu(page, 'Opciones de administración', 'Restricciones de cuentas');
    await panelAdmin.getByLabel('Correo de la cuenta').pressSequentially(EVALUADA.email, { delay: 60 });
    await panelAdmin.getByRole('button', { name: 'Buscar', exact: true }).click();
    await expect(panelAdmin.getByRole('heading', { name: EVALUADA.email })).toBeVisible();
    await expect(panelAdmin).toContainText('2 antecedente(s) en los últimos 180 días');
    await expect(panelAdmin.getByText('Reincidente', { exact: true })).toBeVisible();
    await expect(panelAdmin).toContainText('Reembolso aprobado por soporte');
    await expect(panelAdmin).toContainText('Juego de ollas antiadherentes — Publicación retirada');
    await etapa('Dos antecedentes confirmados en 180 días: la cuenta es reincidente');
  });

  await test.step('Pide una aclaración y restringe el rol Vendedor por 30 días', async () => {
    await etapa('Antes de decidir, pide una aclaración (72 horas para responder)');
    await panelAdmin.getByRole('button', { name: 'Pedir aclaración', exact: true }).click();
    await panelAdmin.getByLabel('Pregunta para la cuenta').pressSequentially(PREGUNTA, { delay: 20 });
    await panelAdmin.getByRole('button', { name: 'Enviar solicitud', exact: true }).click();
    await expect(panelAdmin.getByRole('status')).toHaveText('Se pidió la aclaración a la cuenta.');
    await expect(panelAdmin).toContainText('Esperando respuesta');

    await etapa('Aplica una restricción solo al rol Vendedor, por 30 días');
    await panelAdmin.getByRole('button', { name: 'Aplicar restricción', exact: true }).click();
    await panelAdmin.getByLabel('Alcance').selectOption('ROLE');
    await panelAdmin.getByLabel('Rol afectado').selectOption('VENDEDOR');
    await panelAdmin.getByLabel('Motivo').pressSequentially(MOTIVO, { delay: 20 });
    await panelAdmin.getByLabel('Duración (días)').fill('30');
    await panelAdmin.getByRole('button', { name: 'Guardar', exact: true }).click();
    await expect(panelAdmin.getByRole('status')).toHaveText('La restricción quedó aplicada.');
    const vigente = panelAdmin.getByRole('region', { name: 'Restricción vigente' });
    await expect(vigente).toContainText('Rol Vendedor');
    await expect(vigente).toContainText(MOTIVO);
    expect(servidor.vigentes()).toHaveLength(1);
    await panelAdmin.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La persona restringida no puede usar el rol Vendedor', async () => {
    await etapa('Entra la dueña de la tienda: tiene dos roles y debe elegir uno');
    await iniciarSesion(page, EVALUADA.email);
    await etapa('Intenta entrar como Vendedor: el rol está restringido');
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
    await elegirRol(page, 'VENDEDOR');
    await expect(page.getByRole('alert').filter({ hasText: 'Este rol tiene una restricción vigente' })).toBeVisible();
    await expect(page.getByText(/^Rol activo:/)).toHaveText(/Sin seleccionar/);

    await etapa('Como Comprador sí puede entrar: la restricción es solo del rol Vendedor');
    await elegirRol(page, 'COMPRADOR');
    await expect(page.getByRole('status').filter({ hasText: 'Rol activo actualizado.' })).toBeVisible();
    await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
  });

  await test.step('Consulta la restricción en «Mi cuenta» y responde la aclaración', async () => {
    await etapa('En «Mi cuenta» ve la restricción, su motivo y hasta cuándo dura');
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mi cuenta');
    const restriccion = miCuenta.getByRole('region', { name: 'Restricción vigente' });
    await expect(restriccion).toContainText('Rol Vendedor');
    await expect(restriccion).toContainText(MOTIVO);
    await expect(miCuenta).not.toContainText('admin.demo@example.com');

    await etapa('Responde la aclaración que pidió la administración');
    await expect(miCuenta).toContainText(PREGUNTA);
    await miCuenta.getByLabel('Tu respuesta').pressSequentially(RESPUESTA, { delay: 20 });
    await miCuenta.getByRole('button', { name: 'Enviar respuesta', exact: true }).click();
    await expect(miCuenta.getByRole('status')).toHaveText('Tu respuesta quedó registrada para la administración.');
    await expect(miCuenta).toContainText('Respondida');
    await miCuenta.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('La administración lee la respuesta y retira la restricción', async () => {
    await etapa('La administración vuelve y encuentra la respuesta en la evaluación');
    await iniciarSesion(page, ADMIN.email);
    await abrirDesdeMenu(page, 'Opciones de administración', 'Restricciones de cuentas');
    await panelAdmin.getByLabel('Correo de la cuenta').fill(EVALUADA.email);
    await panelAdmin.getByRole('button', { name: 'Buscar', exact: true }).click();
    await expect(panelAdmin).toContainText(RESPUESTA);

    await etapa('Con la explicación, retira la restricción antes de su fin');
    await panelAdmin.getByRole('button', { name: 'Retirar anticipadamente', exact: true }).click();
    await panelAdmin.getByLabel('Motivo para retirarla antes').pressSequentially(MOTIVO_RETIRO, { delay: 20 });
    await panelAdmin.getByRole('button', { name: 'Guardar', exact: true }).click();
    await expect(panelAdmin.getByRole('status')).toHaveText('La restricción fue retirada.');
    await expect(panelAdmin.getByRole('region', { name: 'Restricción vigente' })).toHaveCount(0);
    await expect(panelAdmin).toContainText('Retirada antes de su fin');
    expect(servidor.vigentes()).toHaveLength(0);
    await panelAdmin.getByRole('button', { name: 'Cerrar', exact: true }).click();
    await cerrarSesion(page);
  });

  await test.step('Sin restricción, la persona vuelve a usar el rol Vendedor', async () => {
    await etapa('La dueña de la tienda vuelve a entrar como Vendedor');
    await iniciarSesion(page, EVALUADA.email);
    await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
    await elegirRol(page, 'VENDEDOR');
    await expect(page.getByRole('status').filter({ hasText: 'Rol activo actualizado.' })).toBeVisible();
    await expect(page.getByText(/^Rol activo:/)).toHaveText(/VENDEDOR/);
    await etapa('Fin: el rol Vendedor está disponible de nuevo');
    // Pausa exclusiva de presentación para leer el resultado final.
    await page.waitForTimeout(4000);
  });
});

/** Aviso fijo en la esquina que narra la etapa; solo es para la presentación. */
async function narrar(page: Page, texto: string): Promise<void> {
  console.log(texto);
  await page.evaluate(texto => {
    document.querySelector('#cu22-demo-nota')?.remove();
    const nota = document.createElement('aside');
    nota.id = 'cu22-demo-nota';
    nota.textContent = `CU-22 · Demostración con datos controlados · ${texto}`;
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

async function elegirRol(page: Page, rol: Rol): Promise<void> {
  await page.locator('ion-select').filter({ hasText: 'Cambiar rol activo' }).click();
  await page.getByRole('radio', { name: rol, exact: true }).click();
  await page.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(page.getByRole('button', { name: 'OK', exact: true })).toBeHidden();
}
