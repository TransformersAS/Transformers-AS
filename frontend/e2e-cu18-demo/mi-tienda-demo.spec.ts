import { test, expect, Page, Route } from '@playwright/test';
import path from 'node:path';

/**
 * CU-18: recorrido visual de la configuración de una tienda, de la consulta al guardado, en un solo navegador.
 * La API está simulada aquí con estado propio: lo que se guarda es lo que se ve después. Las reglas reales (plazo
 * mínimo, nombre único, versión, imágenes) las prueban las pruebas del backend de la tienda del vendedor.
 */

const VENDEDOR = { accountId: 4, email: 'vendedor.demo@example.com', roles: ['VENDEDOR'], activeRole: 'VENDEDOR' };
const PORTADA = path.join(__dirname, '..', 'src', 'assets', 'catalogo-demo', 'photo-1505740420928-5e560c06d30e.jpg');
const URL_PORTADA = '/assets/catalogo-demo/photo-1505740420928-5e560c06d30e.jpg';

const DESCRIPCION = 'Audífonos, parlantes y accesorios de sonido con garantía de fábrica. Envíos a todo el país.';
const HORARIOS = 'Lunes a sábado de 8:00 a 19:00';
const POLITICA = 'Aceptamos devoluciones con el empaque original. Si llegó defectuoso, nosotros pagamos el envío.';

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  let sesion: typeof VENDEDOR | null = null;
  const guardados: any[] = [];
  let tienda: any = {
    id: 2, name: 'Tienda Sonido Andino', description: 'Audífonos y parlantes.', contactEmail: 'ventas@sonidoandino.co',
    contactPhone: null, businessHours: null, returnWindowDays: 30, minReturnWindowDays: 30, policyText: null,
    status: 'ACTIVE', statusReason: null, canModify: true, version: 3,
    shippingMethods: { enabled: ['STANDARD'], available: ['STANDARD', 'EXPRESS'] }, images: []
  };
  /** Lo que el backend devolvería al validar o guardar esta solicitud, sin tocar las imágenes. */
  const aplicar = (solicitud: any) => ({
    ...tienda, name: solicitud.name, description: solicitud.description, contactEmail: solicitud.contactEmail,
    contactPhone: solicitud.contactPhone, businessHours: solicitud.businessHours,
    returnWindowDays: solicitud.returnWindowDays, policyText: solicitud.policyText,
    shippingMethods: { ...tienda.shippingMethods, enabled: solicitud.shippingMethods }
  });

  return {
    guardados,
    tienda: () => tienda,
    async atender(route: Route) {
      const req = route.request();
      const ruta = new URL(req.url()).pathname;
      const method = req.method();
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });

      if (ruta === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
      if (ruta === '/api/auth/me') return sesion ? json(sesion) : json({ message: 'Sin sesión' }, 401);
      if (ruta === '/api/auth/login') { sesion = VENDEDOR; return json({}); }
      if (ruta === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }

      if (ruta === '/api/seller/store' && method === 'GET') return json(tienda);
      if (ruta === '/api/seller/store/preview' && method === 'POST') {
        const solicitud = req.postDataJSON();
        // A5: la misma regla que aplica el backend, por si la pantalla dejara pasar un plazo menor.
        if (solicitud.returnWindowDays < tienda.minReturnWindowDays) {
          return json({ code: 'RETURN_WINDOW_TOO_SHORT', message: 'Plazo menor al mínimo',
            details: { minReturnWindowDays: tienda.minReturnWindowDays } }, 400);
        }
        return json(aplicar(solicitud));
      }
      if (ruta === '/api/seller/store' && method === 'PUT') {
        const solicitud = req.postDataJSON();
        if (solicitud.version !== tienda.version) return json({ code: 'STORE_VERSION_CONFLICT', message: 'Conflicto' }, 409);
        tienda = { ...aplicar(solicitud), version: tienda.version + 1 };
        guardados.push(solicitud);
        return json(tienda);
      }
      if (ruta === '/api/seller/store/images/portada' && method === 'PUT') {
        const imagen = { kind: 'PORTADA', contentType: 'image/jpeg', sizeBytes: 48213, sha256: 'demo', url: URL_PORTADA };
        tienda = { ...tienda, images: [...tienda.images.filter((i: any) => i.kind !== 'PORTADA'), imagen] };
        return json(imagen);
      }

      // Lo que la página principal pide de paso y no es parte de CU-18.
      if (ruta === '/api/recommendations') return json({ products: [], strategy: 'DEMO_CONTROLADA' });
      if (ruta === '/api/cart') return json({ items: [], total: 0 });
      if (ruta === '/api/notifications') return json({ items: [], hasMore: false });
      return json([]);
    }
  };
}

test('CU-18: consultar, editar con vista previa y guardar la configuración de la tienda', async ({ page }) => {
  const servidor = crearServidor();
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', route => servidor.atender(route));

  const etapa = (texto: string) => narrar(page, texto);
  const miTienda = page.locator('app-mi-tienda');
  // Por rol y nombre accesible: ion-textarea no asocia su etiqueta como getByLabel espera.
  const campo = (nombre: string) => miTienda.getByRole(nombre.startsWith('Plazo') ? 'spinbutton' : 'textbox',
    { name: nombre, exact: true });

  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await test.step('La vendedora consulta cómo está configurada su tienda', async () => {
    await etapa('La vendedora inicia sesión y abre «Mi tienda»');
    await iniciarSesion(page, VENDEDOR.email);
    await page.getByRole('button', { name: 'Opciones de mi negocio', exact: true }).click();
    await page.getByRole('button', { name: 'Mi tienda', exact: true }).click();
    await expect(miTienda.getByRole('heading', { name: /Configuración actual/ })).toBeVisible();
    await expect(miTienda).toContainText('Tienda Sonido Andino');
    await expect(miTienda).toContainText('30 días');
    await etapa('Ve su configuración y cómo ven la tienda los compradores');
    await miTienda.getByRole('heading', { name: 'Así ven tu tienda los compradores' }).scrollIntoViewIfNeeded();
    await page.waitForTimeout(1500);
  });

  await test.step('El plazo de devolución no puede ser menor al mínimo del marketplace', async () => {
    await miTienda.getByRole('button', { name: 'Editar configuración', exact: true }).click();
    await etapa('Intenta bajar el plazo de devolución a 15 días');
    await campo('Plazo de devolución (días)').fill('15');
    await miTienda.getByRole('button', { name: 'Ver vista previa', exact: true }).click();
    await expect(miTienda.getByRole('alert').filter({ hasText: 'Revisa los campos marcados' })).toBeVisible();
    const errorPlazo = miTienda.locator('#error-returnWindowDays');
    await expect(errorPlazo).toHaveText(/El plazo de devolución no puede ser menor a 30 días: es una regla obligatoria/);
    await errorPlazo.scrollIntoViewIfNeeded();
    expect(servidor.guardados).toHaveLength(0);
    await etapa('El sistema lo impide: es una regla obligatoria del marketplace (mínimo 30 días)');
  });

  await test.step('Corrige el plazo y actualiza los datos, los envíos y la portada', async () => {
    await etapa('Lo sube a 45 días y completa los datos de la tienda');
    await campo('Plazo de devolución (días)').fill('45');
    await campo('Descripción').fill('');
    await campo('Descripción').pressSequentially(DESCRIPCION, { delay: 15 });
    await campo('Teléfono de contacto').pressSequentially('+57 300 123 4567', { delay: 40 });
    await campo('Horarios de atención').pressSequentially(HORARIOS, { delay: 20 });
    await campo('Texto de la política').pressSequentially(POLITICA, { delay: 15 });
    await etapa('Agrega el envío exprés y una imagen de portada');
    await miTienda.getByRole('checkbox', { name: 'Envío exprés' }).click();
    await miTienda.getByLabel('Elegir archivo para portada').setInputFiles(PORTADA);
    await miTienda.getByRole('button', { name: 'Ver vista previa', exact: true }).click();
  });

  await test.step('Revisa la vista previa antes de guardar', async () => {
    await expect(miTienda.getByRole('heading', { name: 'Vista previa de los cambios' })).toBeVisible();
    const cambios = miTienda.locator('ul.cambios');
    await expect(cambios).toContainText('30');
    await expect(cambios).toContainText('45');
    await expect(cambios).toContainText(HORARIOS);
    expect(servidor.guardados).toHaveLength(0);
    await etapa('La vista previa muestra antes → después; todavía no se ha guardado nada');
    await miTienda.getByRole('heading', { name: 'Así la verían tus compradores' }).scrollIntoViewIfNeeded();
    await page.waitForTimeout(2000);
  });

  await test.step('Confirma y los compradores ven la tienda actualizada', async () => {
    await etapa('Confirma: se guardan la portada y la configuración');
    await miTienda.getByRole('button', { name: 'Confirmar y guardar', exact: true }).click();
    await expect(miTienda.getByRole('status').filter({ hasText: 'Cambios guardados.' })).toBeVisible();
    expect(servidor.guardados).toHaveLength(1);
    expect(servidor.tienda()).toMatchObject({ returnWindowDays: 45, version: 4,
      shippingMethods: { enabled: ['STANDARD', 'EXPRESS'] } });
    await expect(miTienda).toContainText('45 días');
    await expect(miTienda).toContainText('Envío exprés');
    await etapa('Fin: la tienda quedó guardada y así la ven los compradores');
    await miTienda.getByRole('heading', { name: 'Así ven tu tienda los compradores' }).scrollIntoViewIfNeeded();
    // Pausa exclusiva de presentación para leer el resultado final.
    await page.waitForTimeout(4000);
  });
});

/** Aviso fijo en la esquina que narra la etapa; solo es para la presentación. */
async function narrar(page: Page, texto: string): Promise<void> {
  console.log(texto);
  await page.evaluate(texto => {
    document.querySelector('#cu18-demo-nota')?.remove();
    const nota = document.createElement('aside');
    nota.id = 'cu18-demo-nota';
    nota.textContent = `CU-18 · Demostración con datos controlados · ${texto}`;
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
