import { test, expect } from '@playwright/test';
import {
  CLAVE, Cuenta, abrirDesdeMenu, cambiarRol, cerrarPanelCuenta, cerrarSesion, escribir, iniciarSesion, narrador, pausa,
  simularBackend
} from './apoyo';

/**
 * CU-12: recorrido visual del registro de vendedores, en un solo navegador.
 *  1. Un visitante sin cuenta se registra: el sistema rechaza un correo ya registrado y un nombre de tienda repetido,
 *     reserva la tienda y pide confirmar el correo con el código enviado; con un código equivocado no avanza.
 *  2. Con el correo confirmado entra, tiene los roles COMPRADOR y VENDEDOR y ve las opciones de su negocio.
 *  3. Un comprador que ya tiene cuenta habilita el rol de vendedor con su misma cuenta, sin crear otra.
 * La API está simulada aquí con estado propio. Las reglas reales (correo único, nombre único, política de contraseña,
 * aceptación de condiciones y confirmación por código) las prueban las pruebas del backend de CU-12.
 */

const CONDICIONES = `1. Eres responsable de la información, el precio y el inventario de los productos que publiques.
2. Debes despachar los pedidos a tiempo y cumplir la política de devoluciones de tu tienda (mínimo 30 días).
3. Debes atender las reclamaciones de tus compradores; si no hay acuerdo, decide el equipo de soporte.
4. No puedes publicar productos ilegales, falsificados ni que infrinjan derechos de terceros.
5. El marketplace puede restringir o suspender tu tienda si incumples estas condiciones.`;

/** El código de 43 caracteres que el backend enviaría al buzón del vendedor. */
const CODIGO = 'demoCU12-Kq7vX2pLm9TzR4wB8nYc3HfJ6sD1gA5eQ0u';

const NUEVO = 'ceramica.paramo@example.com';
const TIENDA_NUEVA = 'Cerámica del Páramo';
const TIENDA_EXISTENTE = 'Tienda Sonido Andino';
const COMPRADORA: Cuenta = { accountId: 2, email: 'lucia.compras@example.com', roles: ['COMPRADOR'] };
const TIENDA_COMPRADORA = 'Tejidos Lucía';

/** Estado de la API simulada: lo que el backend guardaría en MySQL durante la demostración. */
function crearServidor() {
  const cuentas: Cuenta[] = [COMPRADORA];
  const tiendas = [TIENDA_EXISTENTE];
  const aceptaciones: { email: string; version: string }[] = [];
  /** Registros a la espera de confirmar el correo: aún no pueden iniciar sesión. */
  const pendientes = new Map<string, Cuenta>();
  let siguienteCuenta = 10;

  const nombreOcupado = (nombre: string) => tiendas.some(t => t.toLowerCase() === nombre.trim().toLowerCase());
  const crearTienda = (nombre: string) => { tiendas.push(nombre.trim()); return tiendas.length; };

  return {
    cuentas, tiendas, aceptaciones,
    atender: (sesionDe: () => string | null) => (ruta: string, metodo: string, peticion: any,
      responder: (cuerpo: unknown, estado?: number) => Promise<void>, route: any) => {
      if (ruta === '/api/sellers/terms') return responder({ version: '2026-09', text: CONDICIONES });

      if (ruta === '/api/sellers/register' && metodo === 'POST') {
        const { email, password, storeName, acceptTerms } = peticion.postDataJSON();
        if (cuentas.some(c => c.email === email) || pendientes.has(email)) {
          return responder({ code: 'EMAIL_ALREADY_REGISTERED',
            message: 'Ya existe una cuenta con ese correo: inicia sesión y habilita el rol de vendedor' }, 409);
        }
        if (!acceptTerms) return responder({ code: 'TERMS_NOT_ACCEPTED', message: 'Debes aceptar las condiciones' }, 400);
        if (password.length < 12) return responder({ code: 'WEAK_PASSWORD', message: 'La contraseña es muy corta' }, 400);
        if (nombreOcupado(storeName)) {
          return responder({ code: 'STORE_NAME_TAKEN', message: 'Ya existe otra tienda con ese nombre' }, 409);
        }
        const storeId = crearTienda(storeName);
        pendientes.set(email, { accountId: siguienteCuenta++, email, roles: ['COMPRADOR'] });
        aceptaciones.push({ email, version: '2026-09' });
        return responder({ storeId, storeName: storeName.trim(), emailVerificationRequired: true,
          sellerRoleActive: false, verificationDeliveryFailed: false }, 201);
      }

      if (ruta === '/api/auth/email-verification/confirm' && metodo === 'POST') {
        if (peticion.postDataJSON().token !== CODIGO) {
          return responder({ code: 'INVALID_VERIFICATION_TOKEN', message: 'Código de verificación inválido o vencido' }, 400);
        }
        // Confirmar el correo concede el rol VENDEDOR porque ya hay tienda y condiciones aceptadas.
        const [email, cuenta] = [...pendientes.entries()][0];
        pendientes.delete(email);
        cuentas.push({ ...cuenta, roles: ['COMPRADOR', 'VENDEDOR'] });
        return route.fulfill({ status: 204, body: '' });
      }

      if (ruta === '/api/sellers/enable' && metodo === 'POST') {
        const cuenta = cuentas.find(c => c.email === sesionDe());
        if (!cuenta) return responder({ message: 'Inicia sesión' }, 401);
        const { storeName } = peticion.postDataJSON();
        if (nombreOcupado(storeName)) {
          return responder({ code: 'STORE_NAME_TAKEN', message: 'Ya existe otra tienda con ese nombre' }, 409);
        }
        const storeId = crearTienda(storeName);
        cuenta.roles = [...cuenta.roles, 'VENDEDOR'];
        aceptaciones.push({ email: cuenta.email, version: '2026-09' });
        return responder({ storeId, storeName: storeName.trim(), emailVerificationRequired: false,
          sellerRoleActive: true, verificationDeliveryFailed: false }, 201);
      }
      return false;
    }
  };
}

test('CU-12: un visitante y un comprador se registran como vendedores', async ({ page }) => {
  const servidor = crearServidor();
  let correoEnSesion: string | null = null;
  page.on('request', peticion => {
    if (peticion.url().endsWith('/api/auth/login')) correoEnSesion = new URLSearchParams(peticion.postData() ?? '').get('email');
    if (peticion.url().endsWith('/api/auth/logout')) correoEnSesion = null;
  });
  await simularBackend(page, servidor.cuentas, servidor.atender(() => correoEnSesion));
  const nota = narrador(page, 'CU-12');

  await page.goto('/');
  await nota('Un visitante sin cuenta quiere vender en el marketplace.');
  await page.getByRole('button', { name: 'Conocer el espacio vendedor' }).click();
  const registro = page.locator('app-registro-vendedor');
  await expect(registro.getByText('Vende en el marketplace')).toBeVisible();
  await expect(registro.getByRole('button', { name: 'Soy nuevo' })).toBeVisible();

  // ---------- Condiciones ----------
  await nota('Antes de registrarse lee las condiciones para vender. Queda guardada la versión que acepta.');
  await expect(registro.getByText('Condiciones para vender (versión 2026-09)')).toBeVisible();
  await expect(registro.locator('pre.condiciones')).toContainText('mínimo 30 días');
  await registro.locator('pre.condiciones').scrollIntoViewIfNeeded();
  await pausa(page, 2500);

  const crear = registro.getByRole('button', { name: 'Crear cuenta y tienda' });
  const correo = registro.getByLabel('Correo');
  const clave = registro.getByLabel('Contraseña (mínimo 12 caracteres)');
  const repetir = registro.getByLabel('Repite la contraseña');
  const tienda = registro.getByLabel('Nombre de tu tienda (único)');

  // ---------- Correo ya registrado ----------
  await nota('Primero escribe un correo que ya tiene cuenta en el marketplace.');
  await escribir(correo, COMPRADORA.email);
  await escribir(clave, CLAVE);
  await escribir(repetir, CLAVE);
  await escribir(tienda, TIENDA_NUEVA);
  await registro.locator('ion-checkbox').click();
  await crear.click();
  await expect(registro.getByRole('alert')).toContainText('Ya existe una cuenta con ese correo');
  await nota('El sistema no crea otra cuenta: le indica que inicie sesión y habilite el rol con la suya.');

  // ---------- Contraseñas distintas ----------
  await nota('Cambia a un correo nuevo, pero se equivoca al repetir la contraseña.');
  await escribir(correo, NUEVO);
  await escribir(repetir, 'OtraClave1234!');
  await expect(crear).toBeDisabled();
  await nota('Mientras las contraseñas no coincidan, el botón de registro sigue desactivado.');
  await escribir(repetir, CLAVE);
  await expect(crear).toBeEnabled();

  // ---------- Nombre de tienda repetido ----------
  await nota(`Ahora intenta llamar a su tienda «${TIENDA_EXISTENTE}», que ya existe.`);
  await escribir(tienda, TIENDA_EXISTENTE);
  await crear.click();
  await expect(registro.getByRole('alert')).toContainText('Ya existe otra tienda con ese nombre');
  await nota('El nombre de la tienda es único. No queda creada ni la cuenta.');
  expect(servidor.cuentas.map(c => c.email)).not.toContain(NUEVO);

  // ---------- Registro y confirmación del correo ----------
  await escribir(tienda, TIENDA_NUEVA);
  await nota(`Elige «${TIENDA_NUEVA}» y envía el registro.`);
  await crear.click();
  await expect(registro.getByRole('heading', { name: 'Confirma tu registro' })).toBeVisible();
  await expect(registro.getByText(NUEVO)).toBeVisible();
  await nota('La tienda queda reservada. El rol de vendedor llega cuando confirme su correo con el código enviado.');

  const codigo = registro.getByLabel('Código de verificación');
  await escribir(codigo, 'codigo-que-no-es');
  await registro.getByRole('button', { name: 'Confirmar correo' }).click();
  await expect(registro.getByRole('alert')).toContainText('Código de verificación inválido o vencido');
  await nota('Con un código equivocado no avanza.');

  await nota('Copia el código que le llegó al correo.');
  await escribir(codigo, CODIGO);
  await registro.getByRole('button', { name: 'Confirmar correo' }).click();
  await expect(registro.getByRole('heading', { name: '¡Listo!' })).toBeVisible();
  await expect(registro.getByText(TIENDA_NUEVA)).toBeVisible();
  await nota('Registro confirmado: la cuenta ya tiene el rol de vendedor y su tienda está creada.');
  await registro.getByRole('button', { name: 'Entendido' }).click();

  // ---------- El nuevo vendedor entra ----------
  await nota('Entra con su cuenta nueva.');
  let cuenta = await iniciarSesion(page, NUEVO);
  await expect(cuenta.getByText('Roles disponibles: COMPRADOR, VENDEDOR')).toBeVisible();
  await nota('Tiene los dos roles: puede seguir comprando y ahora también vender. Elige el rol VENDEDOR.');
  await cambiarRol(page, cuenta, 'VENDEDOR');
  await cerrarPanelCuenta(page);
  await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Mis productos');
  await expect(page.locator('app-mis-productos')).toBeVisible();
  await nota('Ya ve las opciones de su negocio y puede empezar a publicar productos (CU-14).');
  await pausa(page, 2000);
  await page.locator('app-mis-productos').getByRole('button', { name: 'Cerrar' }).first().click();
  await cerrarSesion(page);

  // ---------- Un comprador habilita el rol con su misma cuenta ----------
  await nota(`Ahora Lucía, que ya compra en el marketplace, también quiere vender.`);
  cuenta = await iniciarSesion(page, COMPRADORA.email);
  await expect(cuenta.getByText('Roles disponibles: COMPRADOR')).toBeVisible();
  await cerrarPanelCuenta(page);
  await page.getByRole('button', { name: 'Conocer el espacio vendedor' }).click();
  await expect(registro.getByText(`Vas a habilitar el rol de vendedor en tu cuenta ${COMPRADORA.email}`)).toBeVisible();
  await nota('No tiene que crear otra cuenta: usa la misma y las mismas credenciales.');
  await escribir(registro.getByLabel('Nombre de tu tienda (único)'), TIENDA_COMPRADORA);
  await registro.locator('ion-checkbox').click();
  await registro.getByRole('button', { name: 'Habilitar mi tienda' }).click();
  await expect(registro.getByRole('heading', { name: '¡Listo!' })).toBeVisible();
  await expect(registro.getByText(TIENDA_COMPRADORA)).toBeVisible();
  await nota('Como su correo ya estaba confirmado, el rol de vendedor queda activo de inmediato.');
  await registro.getByRole('button', { name: 'Entendido' }).click();

  expect(servidor.cuentas.find(c => c.email === COMPRADORA.email)?.roles).toEqual(['COMPRADOR', 'VENDEDOR']);
  expect(servidor.tiendas).toEqual([TIENDA_EXISTENTE, TIENDA_NUEVA, TIENDA_COMPRADORA]);
  expect(servidor.aceptaciones).toEqual([
    { email: NUEVO, version: '2026-09' }, { email: COMPRADORA.email, version: '2026-09' }]);
  await nota('Fin: dos vendedores nuevos, cada uno con su tienda y con las condiciones aceptadas.');
  await pausa(page, 2500);
});
