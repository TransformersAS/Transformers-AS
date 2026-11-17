import { expect, Locator, Page, Route } from '@playwright/test';

/**
 * Apoyo común de las demostraciones de CU-23 a CU-27: cuentas, sesión simulada, narración en pantalla y los pasos que
 * se repiten (entrar, salir, abrir un panel del menú, elegir en una lista de Ionic).
 */

export type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN';
export interface Cuenta { accountId: number; email: string; roles: Rol[] }

export const COMPRADOR: Cuenta = { accountId: 2, email: 'comprador.demo@example.com', roles: ['COMPRADOR'] };
export const VENDEDOR: Cuenta = { accountId: 4, email: 'vendedor.demo@example.com', roles: ['VENDEDOR'] };
export const ADMIN: Cuenta = { accountId: 1, email: 'admin.demo@example.com', roles: ['ADMIN'] };
const CUENTAS = [COMPRADOR, VENDEDOR, ADMIN];

export const TIENDA = 'Tienda Sonido Andino';

/** Fecha local sin zona, como la envía el backend (LocalDateTime), desplazada en horas desde ahora. */
export function fecha(horasDesdeAhora = 0): string {
  const d = new Date(Date.now() + horasDesdeAhora * 3_600_000);
  const n = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${n(d.getMonth() + 1)}-${n(d.getDate())}T${n(d.getHours())}:${n(d.getMinutes())}:${n(d.getSeconds())}`;
}

/** La misma fecha como la muestran las vistas de CU-27 (dd/MM/yyyy HH:mm). */
export function fechaVisible(iso: string): string {
  const [dia, hora] = iso.split('T');
  const [anio, mes, d] = dia.split('-');
  return `${d}/${mes}/${anio} ${hora.slice(0, 5)}`;
}

export type Responder = (cuerpo: unknown, estado?: number) => Promise<void>;
/** Atiende una petición del caso de uso; devuelve false si la ruta no es suya. */
export type Atender = (ruta: string, metodo: string, peticion: ReturnType<Route['request']>, responder: Responder,
  route: Route) => Promise<void> | false;

/**
 * Simula el backend dentro del navegador: la sesión y lo que la página principal pide de paso se resuelven aquí; lo
 * propio del caso de uso lo atiende la función recibida, que guarda su estado durante la demostración.
 */
export async function simularBackend(page: Page, atender: Atender): Promise<void> {
  let sesion: (Cuenta & { activeRole: Rol }) | null = null;
  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const peticion = route.request();
    const ruta = new URL(peticion.url()).pathname;
    const responder: Responder = (cuerpo, estado = 200) => route.fulfill({ status: estado, json: cuerpo });

    if (ruta === '/api/auth/csrf') return responder({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (ruta === '/api/auth/me') return sesion ? responder(sesion) : responder({ message: 'Sin sesión' }, 401);
    if (ruta === '/api/auth/login') {
      const cuenta = CUENTAS.find(c => c.email === new URLSearchParams(peticion.postData() ?? '').get('email'));
      if (!cuenta) return responder({ message: 'Credenciales inválidas' }, 401);
      sesion = { ...cuenta, activeRole: cuenta.roles[0] };
      return responder({});
    }
    if (ruta === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }

    const atendida = atender(ruta, peticion.method(), peticion, responder, route);
    if (atendida !== false) return atendida;

    // Lo que la página principal pide de paso y no es parte del caso de uso.
    if (ruta === '/api/recommendations') return responder({ products: [], strategy: 'DEMO_CONTROLADA' });
    if (ruta === '/api/cart') return responder({ items: [], total: 0 });
    if (ruta.endsWith('/notifications')) return responder({ items: [], hasMore: false });
    return responder([]);
  });
}

/** Devuelve la función que narra cada etapa en un aviso fijo; solo es para la presentación. */
export function narrador(page: Page, casoDeUso: string): (texto: string) => Promise<void> {
  return async texto => {
    console.log(texto);
    await page.evaluate(([cu, mensaje]) => {
      document.querySelector('#demo-nota')?.remove();
      const nota = document.createElement('aside');
      nota.id = 'demo-nota';
      nota.textContent = `${cu} · Demostración con datos controlados · ${mensaje}`;
      Object.assign(nota.style, { position: 'fixed', bottom: '16px', right: '16px', zIndex: '99999', maxWidth: '340px',
        padding: '14px', background: '#fff4df', color: '#183037', border: '2px solid #f56b4b', borderRadius: '12px',
        pointerEvents: 'none' });
      document.body.appendChild(nota);
    }, [casoDeUso, texto]);
    await page.waitForTimeout(PAUSA_NARRACION);
  };
}

/** Pausas de presentación; DEMO_RAPIDA=1 las quita para validar el recorrido sin esperar. */
const RAPIDA = process.env['DEMO_RAPIDA'] === '1';
const PAUSA_NARRACION = RAPIDA ? 0 : 1800;
const TECLEO = RAPIDA ? 0 : 15;

/** Pausa exclusiva de presentación para leer una pantalla. */
export async function pausa(page: Page, milisegundos: number): Promise<void> {
  await page.waitForTimeout(RAPIDA ? 0 : milisegundos);
}

/** Escribe como una persona, para que se alcance a leer. */
export async function escribir(campo: Locator, texto: string): Promise<void> {
  await campo.pressSequentially(texto, { delay: TECLEO });
}

export async function iniciarSesion(page: Page, cuenta: Cuenta): Promise<void> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  const panel = page.getByRole('dialog', { name: 'Acceso a tu cuenta' });
  await panel.getByLabel('Correo').pressSequentially(cuenta.email, { delay: RAPIDA ? 0 : 40 });
  await panel.getByLabel('Contraseña').fill('MarketplaceDemo123!');
  await panel.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Seguridad de tu cuenta' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}

export async function cerrarSesion(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Acceso a tu cuenta' })).toBeVisible();
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}

export async function abrirDesdeMenu(page: Page, menu: string, opcion: string): Promise<void> {
  await page.getByRole('button', { name: menu, exact: true }).click();
  await page.getByRole('button', { name: opcion, exact: true }).click();
}

/** Elige una opción en una lista desplegable de Ionic (se abre como un diálogo con botones de opción). */
export async function elegirEnLista(page: Page, lista: Locator, opcion: string): Promise<void> {
  await lista.click();
  const dialogo = page.locator('ion-alert');
  await dialogo.getByRole('radio', { name: opcion, exact: true }).click();
  await dialogo.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(dialogo).toBeHidden();
}
