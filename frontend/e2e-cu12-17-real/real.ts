import { expect, Page } from '@playwright/test';
import { Rol, cambiarRol, cerrarPanelCuenta } from '../e2e-cu12-17-demo/apoyo';

/**
 * Ayudas de las demos contra la aplicación REAL. La cuenta pública de demostración del perfil local es vendedora de la
 * tienda 1 y compradora a la vez; su contraseña es la de `CLAVE` en apoyo.ts.
 */
export const CUENTA_DEMO = 'demo@marketplace.local';

/** Llamada a la API real con la sesión del navegador y el token CSRF. */
export async function api(page: Page, metodo: 'get' | 'post' | 'put' | 'delete', ruta: string, data?: unknown) {
  const pedido = page.context().request;
  const csrf = await (await pedido.get('/api/auth/csrf')).json();
  const respuesta = await pedido[metodo](ruta, { headers: { [csrf.headerName]: csrf.token }, data });
  expect(respuesta.ok(), `${metodo.toUpperCase()} ${ruta}: ${respuesta.status()} ${await respuesta.text()}`).toBeTruthy();
  return respuesta.status() === 204 ? null : respuesta.json().catch(() => null);
}

/** Cambia el rol activo desde el panel de la cuenta, solo si hace falta. */
export async function usarRol(page: Page, rol: Rol) {
  const yo = await api(page, 'get', '/api/auth/me');
  if (yo.activeRole === rol) return;
  await page.getByRole('button', { name: 'Ver cuenta', exact: true }).click();
  await cambiarRol(page, page.getByRole('dialog', { name: 'Seguridad de tu cuenta' }), rol);
  await cerrarPanelCuenta(page);
}

/** Línea que el script de la demo lee al final para consultar la base (CLAVE=valor). */
export function informar(clave: string, valor: string | number) {
  console.log(`${clave}=${valor}`);
}
