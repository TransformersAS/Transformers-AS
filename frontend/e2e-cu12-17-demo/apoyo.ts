import { expect, Locator, Page, Route } from '@playwright/test';

/**
 * Apoyo común de las demostraciones de CU-12 a CU-17: cuentas, sesión simulada, narración en pantalla y los pasos que
 * se repiten (entrar, salir, cambiar de rol, abrir un panel del menú, elegir en una lista de Ionic).
 */

export type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN' | 'SOPORTE';
export interface Cuenta { accountId: number; email: string; roles: Rol[] }

export const CLAVE = 'MarketplaceDemo123!';

export type Responder = (cuerpo: unknown, estado?: number) => Promise<void>;
/** Atiende una petición del caso de uso; devuelve false si la ruta no es suya. */
export type Atender = (ruta: string, metodo: string, peticion: ReturnType<Route['request']>, responder: Responder,
  route: Route) => Promise<void> | false;

/**
 * Simula el backend dentro del navegador. La sesión y lo que la página principal pide de paso se resuelven aquí; lo
 * propio del caso de uso lo atiende la función recibida. `cuentas` es la lista viva de cuentas que pueden entrar: el
 * caso de uso puede agregar o cambiar cuentas (por ejemplo, al registrar un vendedor).
 */
export async function simularBackend(page: Page, cuentas: Cuenta[], atender: Atender): Promise<void> {
  let sesion: { email: string; activeRole: Rol | null } | null = null;
  const actual = () => {
    const cuenta = sesion && cuentas.find(c => c.email === sesion!.email);
    return cuenta ? { ...cuenta, activeRole: sesion!.activeRole } : null;
  };

  await page.route(/^https:\/\/(fonts\.googleapis\.com|fonts\.gstatic\.com|images\.unsplash\.com)\//, route => route.abort());
  await page.route('**/api/**', async route => {
    const peticion = route.request();
    const ruta = new URL(peticion.url()).pathname;
    const metodo = peticion.method();
    const responder: Responder = (cuerpo, estado = 200) => route.fulfill({ status: estado, json: cuerpo });

    if (ruta === '/api/auth/csrf') return responder({ headerName: 'X-CSRF-TOKEN', token: 'demo-csrf' });
    if (ruta === '/api/auth/me') return sesion ? responder(actual()) : responder({ message: 'Sin sesión' }, 401);
    if (ruta === '/api/auth/login') {
      const datos = new URLSearchParams(peticion.postData() ?? '');
      const cuenta = cuentas.find(c => c.email === datos.get('email'));
      if (!cuenta || datos.get('password') !== CLAVE) return responder({ message: 'Correo o contraseña incorrectos' }, 401);
      sesion = { email: cuenta.email, activeRole: cuenta.roles[0] };
      return responder({});
    }
    if (ruta === '/api/auth/logout') { sesion = null; return route.fulfill({ status: 200, body: '' }); }
    if (ruta === '/api/auth/active-role' && metodo === 'PUT' && sesion) {
      sesion.activeRole = peticion.postDataJSON().role;
      return responder(actual());
    }

    const atendida = atender(ruta, metodo, peticion, responder, route);
    if (atendida !== false) return atendida;

    // Lo que la página principal pide de paso y no es parte del caso de uso.
    if (ruta === '/api/recommendations') return responder({ products: [], strategy: 'DEMO_CONTROLADA' });
    if (ruta === '/api/cart') return responder({ items: [], total: 0 });
    if (ruta.endsWith('/notifications')) return responder({ items: [], hasMore: false });
    return responder([]);
  });
}

/** Pausas de presentación; DEMO_RAPIDA=1 las quita para validar el recorrido sin esperar. */
const RAPIDA = process.env['DEMO_RAPIDA'] === '1';
const PAUSA_NARRACION = RAPIDA ? 0 : 1800;
const TECLEO = RAPIDA ? 0 : 15;

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

/** Pausa exclusiva de presentación para leer una pantalla. */
export async function pausa(page: Page, milisegundos: number): Promise<void> {
  await page.waitForTimeout(RAPIDA ? 0 : milisegundos);
}

/** Escribe como una persona, para que se alcance a leer. Borra antes lo que hubiera en el campo. */
export async function escribir(campo: Locator, texto: string): Promise<void> {
  await campo.fill('');
  await campo.pressSequentially(texto, { delay: TECLEO });
}

/** Entra con la cuenta y deja abierto el panel «Seguridad de tu cuenta». */
export async function iniciarSesion(page: Page, email: string): Promise<Locator> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  const acceso = page.getByRole('dialog', { name: 'Acceso a tu cuenta' });
  await escribir(acceso.getByLabel('Correo'), email);
  await acceso.getByLabel('Contraseña').fill(CLAVE);
  await acceso.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  const cuenta = page.getByRole('dialog', { name: 'Seguridad de tu cuenta' });
  await expect(cuenta).toBeVisible();
  return cuenta;
}

export async function cerrarPanelCuenta(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Cerrar panel', exact: true }).click();
}

export async function cerrarSesion(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Acceso a tu cuenta' })).toBeVisible();
  await cerrarPanelCuenta(page);
}

/** Cambia el rol activo desde el panel de la cuenta, que debe estar abierto. */
export async function cambiarRol(page: Page, panelCuenta: Locator, rol: Rol): Promise<void> {
  await elegirEnLista(page, panelCuenta.locator('ion-select', { hasText: 'Cambiar rol activo' }), rol);
  await expect(panelCuenta.getByRole('status')).toContainText('Rol activo actualizado');
}

export async function abrirDesdeMenu(page: Page, menu: string, opcion: string): Promise<void> {
  await page.getByRole('button', { name: menu, exact: true }).click();
  await page.getByRole('button', { name: opcion, exact: true }).click();
}

/** Elige una opción en una lista desplegable de Ionic (se abre como un diálogo con botones de opción). */
export async function elegirEnLista(page: Page, lista: Locator, opcion: string | RegExp): Promise<void> {
  await lista.click();
  const dialogo = page.locator('ion-alert');
  await dialogo.getByRole('radio', { name: opcion, exact: typeof opcion === 'string' }).click();
  await dialogo.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(dialogo).toBeHidden();
}
