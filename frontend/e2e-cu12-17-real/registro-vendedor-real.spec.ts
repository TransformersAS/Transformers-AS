import { test, expect } from '@playwright/test';
import { CLAVE, abrirDesdeMenu, cambiarRol, cerrarPanelCuenta, escribir, iniciarSesion, narrador, pausa } from '../e2e-cu12-17-demo/apoyo';
import { CUENTA_DEMO, informar } from './real';

/**
 * CU-12 contra la aplicación REAL: un visitante se registra como vendedor, el backend le envía el código de
 * verificación por correo (lo atrapa Mailpit, el buzón local de pruebas) y al confirmarlo la cuenta queda con los roles
 * COMPRADOR y VENDEDOR y su tienda. Queda en MySQL: `user_accounts`, `user_account_roles`, `stores` y
 * `seller_terms_acceptances`.
 */

const SELLO = process.env['DEMO_SELLO'] ?? Date.now().toString().slice(-6);
const CORREO = `vendedor.clase${SELLO}@example.com`;
const TIENDA = `Tienda Clase ${SELLO}`;
const MAILPIT = process.env['DEMO_MAILPIT'] ?? 'http://localhost:8025';

/** Busca en Mailpit el último correo enviado a la dirección y devuelve el código de 43 caracteres que trae. */
async function codigoDelCorreo(correo: string): Promise<string> {
  let codigo: string | undefined;
  await expect.poll(async () => {
    const busqueda = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent('to:' + correo)}`)).json();
    const ultimo = busqueda.messages?.[0];
    if (!ultimo) return false;
    const mensaje = await (await fetch(`${MAILPIT}/api/v1/message/${ultimo.ID}`)).json();
    codigo = String(mensaje.Text ?? '').match(/(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/)?.[0];
    return !!codigo;
  }, { message: `no llegó a Mailpit el correo con el código para ${correo}`, timeout: 30000 }).toBe(true);
  return codigo!;
}

test(`CU-12 real: ${CORREO} se registra como vendedor y crea «${TIENDA}»`, async ({ page }) => {
  informar('CORREO', CORREO);
  informar('TIENDA', TIENDA);
  const nota = narrador(page, 'CU-12 · app real');
  await page.goto('/');

  await nota('Un visitante sin cuenta quiere vender en el marketplace.');
  await page.getByRole('button', { name: 'Conocer el espacio vendedor' }).click();
  const registro = page.locator('app-registro-vendedor');
  await expect(registro.getByText(/Condiciones para vender \(versión/)).toBeVisible();
  await nota('Lee las condiciones para vender. Queda guardada la versión que acepta.');

  const crear = registro.getByRole('button', { name: 'Crear cuenta y tienda' });
  const correo = registro.getByLabel('Correo');
  const tienda = registro.getByLabel('Nombre de tu tienda (único)');

  await nota('Primero usa un correo que ya tiene cuenta.');
  await escribir(correo, CUENTA_DEMO);
  await escribir(registro.getByLabel('Contraseña (mínimo 12 caracteres)'), CLAVE);
  await escribir(registro.getByLabel('Repite la contraseña'), CLAVE);
  await escribir(tienda, TIENDA);
  // En algunas versiones el encabezado del panel tapa la casilla si queda al borde: se centra antes del clic.
  await registro.locator('ion-checkbox').evaluate(casilla => casilla.scrollIntoView({ block: 'center' }));
  await registro.locator('ion-checkbox').click();
  await crear.click();
  await expect(registro.getByRole('alert')).toContainText('Ya existe una cuenta con ese correo');

  await nota('Cambia al correo nuevo, pero pide el nombre de una tienda que ya existe.');
  await escribir(correo, CORREO);
  await escribir(tienda, 'Tienda principal');
  await crear.click();
  await expect(registro.getByRole('alert')).toContainText('Ya existe otra tienda con ese nombre');

  await nota(`Elige «${TIENDA}» y envía el registro.`);
  await escribir(tienda, TIENDA);
  await crear.click();
  // El registro envía el correo antes de responder; con el buzón local puede tardar varios segundos.
  await expect(registro.getByRole('heading', { name: 'Confirma tu registro' })).toBeVisible({ timeout: 60000 });
  await nota('La tienda queda reservada y el backend envía el código al correo.');

  const codigo = registro.getByLabel('Código de verificación');
  await escribir(codigo, 'codigo-equivocado');
  await registro.getByRole('button', { name: 'Confirmar correo' }).click();
  await expect(registro.getByRole('alert')).toContainText('Código de verificación inválido o vencido');
  await nota('Con un código equivocado no avanza. Ahora abre el correo que le llegó.');

  const recibido = await codigoDelCorreo(CORREO);
  await escribir(codigo, recibido);
  await registro.getByRole('button', { name: 'Confirmar correo' }).click();
  await expect(registro.getByRole('heading', { name: '¡Listo!' })).toBeVisible();
  await expect(registro.getByText(TIENDA)).toBeVisible();
  await nota('Correo confirmado: la cuenta ya tiene el rol de vendedor y su tienda.');
  await registro.getByRole('button', { name: 'Entendido' }).click();

  await nota('Entra con su cuenta nueva y elige el rol VENDEDOR.');
  const cuenta = await iniciarSesion(page, CORREO);
  await expect(cuenta.getByText('Roles disponibles: COMPRADOR, VENDEDOR')).toBeVisible();
  await cambiarRol(page, cuenta, 'VENDEDOR');
  await cerrarPanelCuenta(page);
  await abrirDesdeMenu(page, 'Opciones de mi negocio', 'Mis productos');
  await expect(page.locator('app-mis-productos')).toBeVisible();
  await nota('Ya puede publicar productos en su tienda. Ahora lo vemos en la base de datos.');
  await pausa(page, 3000);
});
