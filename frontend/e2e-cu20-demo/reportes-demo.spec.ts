import { test, expect, Page, Route } from '@playwright/test';
import path from 'node:path';

/**
 * CU-20: recorrido visual de un reporte de contenido desde quien lo radica, en un solo navegador.
 * La API está simulada aquí con estado propio. Lo que hace soporte (pedir información y decidir) es CU-21 y tiene su
 * propia demostración; aquí se simula entre etapas. Las reglas reales las prueban las pruebas del módulo reports.
 */

const COMPRADOR = { accountId: 2, email: 'comprador.demo@example.com', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' };
const EVIDENCIA = path.join(__dirname, '..', 'src', 'assets', 'catalogo-demo', 'photo-1523275335684-37898b6baf30.jpg');
const PUBLICACION = {
  id: 58, name: 'Reloj inteligente Serie 8 original', description: 'Sellado, garantía oficial de 2 años. Precio de lanzamiento.',
  category: 'Tecnología', price: 89000, stock: 40, active: true,
  imageUrls: ['/assets/catalogo-demo/photo-1523275335684-37898b6baf30.jpg'],
  store: { id: 9, name: 'Ofertas Relámpago', description: 'Tecnología a precios bajos' }
};
const OTRO = {
  id: 12, name: 'Lámpara de mesa', description: 'Luz cálida regulable', category: 'Hogar', price: 120000, stock: 8,
  active: true, imageUrls: ['/assets/catalogo-demo/photo-1507473885765-e6ed057f782c.jpg'],
  store: { id: 1, name: 'Casa Andina', description: 'Hogar' }
};

const MOTIVOS = [
  ['CONTENIDO_INAPROPIADO', 'Contenido inapropiado u ofensivo', false],
  ['PRODUCTO_PROHIBIDO', 'Producto prohibido o restringido', false],
  ['INFORMACION_ENGANOSA', 'Información engañosa o falsa', false],
  ['POSIBLE_FRAUDE', 'Posible fraude o estafa', false],
  ['PROPIEDAD_INTELECTUAL', 'Infracción de propiedad intelectual', false],
  ['SPAM', 'Spam o publicación repetida', false],
  ['OTRO', 'Otro motivo', false],
  ['PRODUCTO_NO_RECIBIDO', 'No recibí mi pedido', true],
  ['PRODUCTO_DEFECTUOSO', 'El producto llegó defectuoso o distinto', true],
  ['REEMBOLSO_O_DEVOLUCION', 'Quiero un reembolso o devolución', true]
].map(([code, label, purchaseProblem]) => ({ code, label, purchaseProblem }));

const DESCRIPCION = 'Dice «original con garantía oficial» a $ 89.000, pero el modelo real cuesta más de $ 1.500.000. ' +
  'Las fotos son del sitio del fabricante.';
const PREGUNTA = '¿Tienes un enlace o captura del precio oficial para adjuntar al caso?';
const RESPUESTA = 'Sí: en la tienda oficial del fabricante aparece a $ 1.549.000. Adjunté la captura al radicar.';

/** Fecha local sin zona, como la envía el backend (LocalDateTime), desplazada en horas desde ahora. */
function fecha(horas: number): string {
  const d = new Date(Date.now() + horas * 3_600_000);
  const n = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:00`;
}

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let sesion: typeof COMPRADOR | null = null;
  let reporte: any = null;
  const radicaciones: any[] = [];
  const resumen = () => ({
    id: reporte.id, caseId: reporte.caseId, contentType: reporte.contentType, contentId: reporte.contentId,
    reason: reporte.reason, reasonLabel: reporte.reasonLabel, status: reporte.status, createdAt: reporte.createdAt,
    evidenceCount: reporte.evidences.length, medidaProvisional: reporte.medidaProvisional,
    awaitingYourResponse: reporte.informationRequests.some((s: any) => s.status === 'ABIERTA')
  });

  return {
    radicaciones,
    estado: () => reporte?.status ?? null,
    /** CU-21: el agente toma el caso, oculta la publicación por precaución y pide información. */
    soportePideInformacion() {
      Object.assign(reporte, { status: 'ESPERANDO_INFORMACION', medidaProvisional: 'OCULTO' });
      reporte.informationRequests.push({ id: 1, message: PREGUNTA, requestedAt: fecha(0), dueAt: fecha(72),
        status: 'ABIERTA', respondedAt: null, responseText: null });
    },
    /** CU-21: el agente decide retirar la publicación. El reportante solo ve el resultado. */
    soporteRetira() {
      Object.assign(reporte, { status: 'RESUELTO', result: 'RETIRADO', medidaProvisional: null, resolvedAt: fecha(0) });
    },

    async atender(route: Route) {
      const req = route.request();
      const ruta = new URL(req.url()).pathname;
      const method = req.method();
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

      if (ruta === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
      if (ruta === '/api/auth/me') return sesion ? json(sesion) : json({ message: 'Sin sesión' }, 401);
      if (ruta === '/api/auth/login') { sesion = COMPRADOR; return json({}); }
      if (ruta === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }

      // Mientras el caso está abierto y la publicación oculta por precaución, el catálogo no la muestra.
      const visibles = () => (reporte?.medidaProvisional || reporte?.result === 'RETIRADO') ? [OTRO] : [PUBLICACION, OTRO];
      if (ruta === '/api/products') return json(visibles());
      if (/^\/api\/products\/\d+$/.test(ruta)) return json(visibles().find(p => p.id === Number(ruta.split('/').pop())));

      if (ruta === '/api/reports/reasons') return json(MOTIVOS);
      if (ruta === '/api/reports' && method === 'POST') {
        const multipart = (req.headers()['content-type'] ?? '').startsWith('multipart/form-data');
        const cuerpo = req.postDataBuffer()?.toString('latin1') ?? '';
        const campo = (nombre: string) => multipart
          ? cuerpo.match(new RegExp(`name="${nombre}"\\r\\n\\r\\n([^\\r]*)`))?.[1] ?? ''
          : req.postDataJSON()[nombre];
        const motivo = campo('reason');
        radicaciones.push({ motivo, multipart });
        if (reporte && reporte.reason === motivo && reporte.status !== 'RESUELTO') {
          return json({ ...resumen(), reasonLabel: reporte.reasonLabel, duplicate: true }, 200);
        }
        reporte = { id: 214, caseId: 87, contentType: 'PUBLICACION', contentId: String(PUBLICACION.id), reason: motivo,
          reasonLabel: MOTIVOS.find(m => m.code === motivo)!.label, description: DESCRIPCION, status: 'PENDIENTE',
          result: null, medidaProvisional: null, createdAt: fecha(0), resolvedAt: null,
          evidences: multipart ? [{ ordinal: 1, fileName: 'captura-precio.jpg', contentType: 'image/jpeg', sizeBytes: 98213 }] : [],
          informationRequests: [] };
        return json({ ...resumen(), duplicate: false }, 201);
      }
      if (ruta === '/api/reports/mine') return json(reporte ? [resumen()] : []);
      if (ruta === '/api/reports/214') return json(reporte);
      if (ruta === '/api/reports/214/evidences/1') return route.fulfill({ path: EVIDENCIA, contentType: 'image/jpeg' });
      if (ruta === '/api/reports/214/information-requests/1/response' && method === 'POST') {
        Object.assign(reporte.informationRequests[0], { status: 'RESPONDIDA', respondedAt: fecha(0),
          responseText: req.postDataJSON().text });
        reporte.status = 'EN_REVISION';
        return json(reporte.informationRequests[0]);
      }

      // Lo que la página principal pide de paso y no es parte de CU-20.
      if (ruta === '/api/recommendations') return json({ products: [], strategy: 'DEMO_CONTROLADA' });
      if (ruta === '/api/cart') return json({ items: [], total: 0 });
      if (ruta === '/api/notifications') return json({ items: [], hasMore: false });
      return json([]);
    }
  };
}

test('CU-20: reportar una publicación, responder a soporte y consultar el resultado', async ({ page }) => {
  const servidor = crearServidor();
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', route => servidor.atender(route));

  const etapa = (texto: string) => narrar(page, texto);
  const tarjeta = page.locator('#catalogo .product-card')
    .filter({ has: page.getByRole('heading', { name: PUBLICACION.name, exact: true }) });
  const formulario = page.getByRole('dialog', { name: 'Reportar publicación' });
  const misReportes = page.locator('app-mis-reportes');
  const abrirReporte = () => misReportes.getByText(/^Reporte #214 · /).click();

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('El comprador encuentra una publicación sospechosa', async () => {
    await etapa('El comprador inicia sesión y ve un reloj «original» a un precio imposible');
    await iniciarSesion(page, COMPRADOR.email);
    await tarjeta.scrollIntoViewIfNeeded();
    await expect(tarjeta).toBeVisible();
    await page.waitForTimeout(1200);
  });

  await test.step('Un problema de compra no se reporta como contenido', async () => {
    await etapa('Abre «Reportar» en la publicación');
    await tarjeta.getByRole('button', { name: 'Reportar publicación', exact: true }).click();
    await formulario.getByLabel('Motivo').selectOption('PRODUCTO_DEFECTUOSO');
    await expect(formulario.getByRole('status').filter({ hasText: 'parece un problema con una compra' })).toBeVisible();
    await expect(formulario.getByRole('button', { name: 'Enviar reporte', exact: true })).toBeDisabled();
    await etapa('Si el motivo es un problema de compra, lo orienta a reclamaciones y devoluciones');
  });

  await test.step('Radica el reporte con descripción y una imagen de evidencia', async () => {
    await etapa('Elige «Información engañosa o falsa» e intenta enviar sin describir');
    await formulario.getByLabel('Motivo').selectOption('INFORMACION_ENGANOSA');
    await formulario.getByRole('button', { name: 'Enviar reporte', exact: true }).click();
    await expect(formulario.locator('#reportar-descripcion-error')).toHaveText('Describe qué está mal en el contenido.');
    expect(servidor.radicaciones).toHaveLength(0);

    await etapa('Describe el problema y adjunta una captura como evidencia');
    await formulario.getByLabel('¿Qué está mal?').pressSequentially(DESCRIPCION, { delay: 12 });
    await formulario.getByLabel('Imágenes de evidencia (opcional)').setInputFiles(EVIDENCIA);
    await expect(formulario.getByRole('img', { name: 'Evidencia 1' })).toBeVisible();
    await formulario.getByRole('button', { name: 'Enviar reporte', exact: true }).click();
    await expect(formulario.getByRole('status')).toContainText('Reporte radicado.');
    await expect(formulario.getByRole('status')).toContainText('#214');
    expect(servidor.radicaciones).toEqual([{ motivo: 'INFORMACION_ENGANOSA', multipart: true }]);
    await etapa('Recibe el número de reporte #214, en estado Pendiente');
    await formulario.getByRole('button', { name: 'Cerrar', exact: true }).click();
  });

  await test.step('Reportar lo mismo otra vez no crea un reporte duplicado', async () => {
    await etapa('Si vuelve a reportar con el mismo motivo, no se crea otro');
    await tarjeta.getByRole('button', { name: 'Reportar publicación', exact: true }).click();
    await formulario.getByLabel('Motivo').selectOption('INFORMACION_ENGANOSA');
    await formulario.getByLabel('¿Qué está mal?').fill('Sigue publicado.');
    await formulario.getByRole('button', { name: 'Enviar reporte', exact: true }).click();
    await expect(formulario.getByRole('status')).toContainText('Ya reportaste esto.');
    await expect(formulario.getByRole('status')).toContainText('#214');
    await formulario.getByRole('button', { name: 'Ver mi reporte', exact: true }).click();
    await expect(misReportes.getByRole('heading', { name: 'Reporte #214' })).toBeVisible();
    await expect(misReportes).toContainText('Pendiente');
    await expect(misReportes.getByRole('img', { name: 'captura-precio.jpg' })).toBeVisible();
    await etapa('Lo lleva a su reporte #214: Pendiente, con su descripción y la evidencia');
    await misReportes.getByRole('button', { name: 'Cerrar', exact: true }).click();
  });

  await test.step('Responde la solicitud de información de soporte', async () => {
    servidor.soportePideInformacion();
    await etapa('Soporte (CU-21) toma el caso, oculta la publicación por precaución y pide información');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(tarjeta).toHaveCount(0);
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mis reportes');
    await expect(misReportes.getByText('Requiere tu respuesta', { exact: true })).toBeVisible();
    await abrirReporte();
    await expect(misReportes).toContainText('El contenido está oculto por precaución mientras se revisa el caso.');
    await expect(misReportes).toContainText(PREGUNTA);
    await etapa('Tiene 72 horas para responder');
    await misReportes.getByLabel('Tu respuesta').pressSequentially(RESPUESTA, { delay: 12 });
    await misReportes.getByRole('button', { name: 'Enviar respuesta', exact: true }).click();
    await expect(misReportes.getByRole('status').filter({ hasText: 'Tu respuesta se envió al equipo de soporte.' })).toBeVisible();
    expect(servidor.estado()).toBe('EN_REVISION');
    await misReportes.getByRole('button', { name: 'Cerrar', exact: true }).click();
  });

  await test.step('Consulta el resultado sin ver quién decidió ni por qué', async () => {
    servidor.soporteRetira();
    await etapa('Soporte decide retirar la publicación');
    await abrirDesdeMenu(page, 'Opciones de mis compras', 'Mis reportes');
    await abrirReporte();
    await expect(misReportes.getByRole('status').filter({ hasText: 'Resultado:' }))
      .toHaveText(/El contenido fue retirado\./);
    await expect(misReportes).toContainText('Resuelto');
    await etapa('Fin: el reportante ve el resultado, pero no quién decidió ni su justificación');
    // Pausa exclusiva de presentación para leer el resultado final.
    await page.waitForTimeout(4000);
  });
});

/** Aviso fijo en la esquina que narra la etapa; solo es para la presentación. */
async function narrar(page: Page, texto: string): Promise<void> {
  console.log(texto);
  await page.evaluate(texto => {
    document.querySelector('#cu20-demo-nota')?.remove();
    const nota = document.createElement('aside');
    nota.id = 'cu20-demo-nota';
    nota.textContent = `CU-20 · Demostración con datos controlados · ${texto}`;
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

async function abrirDesdeMenu(page: Page, menu: string, opcion: string): Promise<void> {
  await page.getByRole('button', { name: menu, exact: true }).click();
  await page.getByRole('button', { name: opcion, exact: true }).click();
}
