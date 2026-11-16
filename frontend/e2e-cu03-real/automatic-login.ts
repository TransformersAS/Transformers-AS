import { expect, type Page } from '@playwright/test';
const { setting } = require('../cu03-real-support.cjs');

export const automatic = () => setting('CU03_AUTOMATIC') === 'true';
export function requireAutomaticSettings() {
  if (!automatic()) return;
  for (const name of ['CU03_BUYER_EMAIL', 'CU03_BUYER_PASSWORD', 'CU03_MP_TEST_USER', 'CU03_MP_TEST_PASSWORD', 'CU03_MP_TEST_CARD_LAST4', 'CU03_MP_TEST_CARD_CVV']) {
    if (!setting(name)) throw new Error(`Falta ${name} en el entorno o .env. No pegues su valor en el chat.`);
  }
  if (!/^TESTUSER\d+$/i.test(setting('CU03_MP_TEST_USER'))) throw new Error('CU03_MP_TEST_USER debe ser el usuario TESTUSER numérico generado por Mercado Pago para pruebas.');
  if (!/^\d{4}$/.test(setting('CU03_MP_TEST_CARD_LAST4')) || !/^\d{3,4}$/.test(setting('CU03_MP_TEST_CARD_CVV'))) throw new Error('Configura los últimos cuatro dígitos y CVV de una tarjeta de PRUEBA guardada.');
}

export async function loginMarketplace(page: Page, origin: string) {
  if (new URL(page.url()).origin !== origin) throw new Error('No se ingresan credenciales del Marketplace fuera del origen configurado.');
  try {
    await page.locator('ion-input[name="correo"] input').fill(setting('CU03_BUYER_EMAIL'));
    await page.locator('ion-input[name="clave"] input').fill(setting('CU03_BUYER_PASSWORD'));
    await page.getByRole('button', { name: 'Iniciar sesión', exact: true }).click();
  } catch {
    throw new Error('No se pudo completar el formulario de acceso del Marketplace. Revisa la ventana y las credenciales locales.');
  }
}

function providerHost(page: Page) {
  const url = new URL(page.url());
  return url.protocol === 'https:' && (url.hostname === 'mercadopago.com.co' || url.hostname.endsWith('.mercadopago.com.co'));
}

export async function paySandbox(page: Page, origin: string, checkout: string) {
  await page.waitForURL(url =>
    (url.protocol === 'https:' && (url.hostname === 'mercadopago.com.co' || url.hostname.endsWith('.mercadopago.com.co'))) ||
    (url.origin === origin && url.searchParams.get('checkout') === checkout), { timeout: 30000 });
  let sentUser = false, sentPassword = false, openedAccount = false, paymentClicked = false;
  let passwordMethodClicks = 0, lastPasswordMethodClick = 0;
  const end = Date.now() + 120000;
  while (Date.now() < end) {
    if (page.isClosed()) throw new Error('Se cerró el navegador antes de verificar el pago.');
    const url = new URL(page.url());
    if (url.origin === origin && url.searchParams.get('checkout') === checkout) return;
    if (!providerHost(page)) throw new Error('La automatización se detuvo fuera de un dominio HTTPS de Mercado Pago Colombia.');
    const providerFrames = page.frames().filter(frame => /^https:\/\/([\w-]+\.)*mercadopago\.com\.co(?:\/|$)/i.test(frame.url()));
    const body = (await Promise.all(providerFrames.map(frame => frame.locator('body').innerText().catch(() => '')))).join('\n');
    if (/valida(?:r)? tu identidad|validar tu identidad|verificaci[oó]n de identidad|ingresa el c[oó]digo|c[oó]digo que te enviamos|no soy un robot/i.test(body)) {
      throw new Error('Mercado Pago exige verificación humana. No se omite: completa la verificación manualmente y continúa el checkout existente.');
    }
    const captcha = page.locator('iframe[src*="recaptcha"][title*="challenge"], iframe[src*="hcaptcha"]');
    if (await captcha.first().isVisible().catch(() => false)) throw new Error('Se detectó un desafío CAPTCHA. Requiere intervención humana; no se reintenta el pago.');
    if (/tarjeta rechaz[oó]|pago rechazado|algo anduvo mal|pago est[aá] pendiente|pago en revisi[oó]n/i.test(body)) throw new Error('El proveedor no confirmó aprobación. Consulta el intento existente; no se repite el pago.');
    const cookies = page.getByRole('button', { name: 'Aceptar cookies', exact: true });
    if (await cookies.isVisible()) { await cookies.click(); continue; }
    const account = page.getByRole('button', { name: /Ingresar con mi cuenta/i });
    if (!openedAccount && await account.isVisible()) { openedAccount = true; await account.click(); continue; }
    if (/elige un m[eé]todo de verificaci[oó]n/i.test(body) && !sentPassword && Date.now() - lastPasswordMethodClick > 5000) {
      if (passwordMethodClicks >= 3) throw new Error('El selector de método de Mercado Pago no respondió. No se envió contraseña ni pago nuevamente.');
      const methodFrame = providerFrames.find(frame => frame !== page.mainFrame()) ?? page.mainFrame();
      const method = methodFrame.getByRole('button', { name: /^Contraseña(?:\s|$)/i })
        .or(methodFrame.getByRole('link', { name: /^Contraseña(?:\s|$)/i })).first();
      if (await method.isVisible()) {
        passwordMethodClicks++;
        lastPasswordMethodClick = Date.now();
        console.log('Mercado Pago: seleccionando el método Contraseña de la cuenta de prueba.');
        await method.click();
        continue;
      }
      throw new Error('Mercado Pago solicita otro método de verificación. Continúa manualmente; no se seleccionan códigos por correo ni se omiten verificaciones.');
    }
    const user = page.getByRole('textbox', { name: /e.mail o tel[eé]fono|usuario/i }).first();
    if (!sentUser && await user.isVisible()) {
      sentUser = true;
      try {
        await user.fill(setting('CU03_MP_TEST_USER'));
        await page.getByRole('button', { name: 'Continuar', exact: true }).click();
      } catch { throw new Error('No se pudo completar el usuario sandbox. Revisa el acceso de prueba en la ventana.'); }
      continue;
    }
    const pay = page.getByRole('button', { name: 'Pagar', exact: true });
    // La validación de contraseña también se presenta dentro de un iframe.
    // Campo y botón deben pertenecer al mismo documento del proveedor.
    let passwordFrame = page.mainFrame();
    for (const frame of page.frames()) {
      if (!/^https:\/\/([\w-]+\.)*mercadopago\.com\.co(?:\/|$)/i.test(frame.url())) continue;
      if (await frame.getByRole('textbox', { name: /^Contraseña$/i }).or(frame.locator('input[autocomplete="current-password"], input[type="password"]')).filter({ visible: true }).first().isVisible()) {
        passwordFrame = frame;
        break;
      }
    }
    const password = passwordFrame.getByRole('textbox', { name: /^Contraseña$/i })
      .or(passwordFrame.locator('input[type="password"], input[autocomplete="current-password"], input[name="password"], input[id="password"]')).filter({ visible: true }).first();
    // El proveedor puede recordar el usuario y abrir directamente la contraseña.
    // Ignorar inputs ocultos y no confundir el CVV de checkout con una contraseña.
    if (!sentPassword && !await pay.isVisible() && await password.isVisible()) {
      sentPassword = true;
      try {
        // El formulario del proveedor puede inicializarse después de hacerse
        // visible. Escribir con teclado permite que procese sus eventos.
        await password.fill('');
        await password.pressSequentially(setting('CU03_MP_TEST_PASSWORD'), { delay: 80 });
        await password.press('Tab');
        // Comparar solo el booleano: una aserción sobre el valor expondría el secreto.
        await expect.poll(async () => (await password.inputValue()) === setting('CU03_MP_TEST_PASSWORD'),
          { timeout: 5000, message: 'El campo de contraseña no conservó el dato; no se envía el formulario vacío.' }).toBe(true);
        const submit = passwordFrame.getByRole('button', { name: /^\s*(Confirmar|Ingresar|Iniciar sesi[oó]n|Continuar)\s*$/i }).filter({ visible: true }).first();
        await submit.click({ timeout: 10000 });
      } catch {
        // Si el proveedor ya avanzó, no volver a enviar la contraseña.
        if (page.isClosed() || (providerHost(page) && await password.isVisible().catch(() => false))) {
          throw new Error('No se pudo enviar la contraseña sandbox. Revisa el botón de acceso en la ventana; no se reintenta el acceso.');
        }
      }
      continue;
    }
    if (!paymentClicked && await pay.isVisible()) {
      // Solo se confirma desde un checkout inequívocamente de pruebas.
      if (url.hostname !== 'sandbox.mercadopago.com.co' && !/\bSandbox\b|ambiente de pruebas/i.test(body)) throw new Error('No se encontró indicador sandbox; se impide confirmar el pago.');
      const card = page.getByText(new RegExp(`(?:Visa|Mastercard).*${setting('CU03_MP_TEST_CARD_LAST4')}`, 'i')).first();
      if (!await card.isVisible()) throw new Error('No aparece la tarjeta de PRUEBA guardada indicada. Configura la tarjeta sandbox o usa el modo asistido.');
      await card.click();
      try {
        // El campo puede aparecer después de seleccionar la tarjeta y vivir en
        // un iframe del proveedor. Nunca escribir el CVV en un input genérico.
        await expect.poll(async () => {
          for (const frame of page.frames()) {
            const frameUrl = frame.url();
            if (frame !== page.mainFrame() && !/^https:\/\/([\w-]+\.)*mercadopago\.com(?:\.co)?(?:\/|$)/i.test(frameUrl)) continue;
            const cvv = frame.getByLabel(/c[oó]digo de seguridad|\bCVV\b|\bCVC\b/i)
              .or(frame.locator('input[autocomplete="cc-csc"], input[name="securityCode"], input[name="security_code"], input[data-checkout="securityCode"]'))
              .filter({ visible: true }).first();
            if (await cvv.isVisible()) {
              await cvv.fill('');
              await cvv.pressSequentially(setting('CU03_MP_TEST_CARD_CVV'), { delay: 100 });
              await cvv.press('Tab');
              if ((await cvv.inputValue()) !== setting('CU03_MP_TEST_CARD_CVV')) return false;
              return true;
            }
          }
          // Algunas tarjetas recordadas no requieren CVV. Solo continuar si
          // no se solicita y el botón está habilitado.
          return !/c[oó]digo de seguridad|\bCVV\b|\bCVC\b/i.test(await page.locator('body').innerText()) && await pay.isEnabled();
        }, { timeout: 10000, message: 'Esperando el campo de seguridad de la tarjeta sandbox' }).toBe(true);
        await expect(pay).toBeEnabled({ timeout: 10000 });
      } catch {
        console.log('Orígenes de los formularios de pago:', page.frames().map(frame => {
          try { return new URL(frame.url()).origin; } catch { return 'sin origen'; }
        }).join(', '));
        throw new Error('No se pudo ingresar el código de la tarjeta de prueba.');
      }
      paymentClicked = true; // Nunca hacer un segundo clic, incluso si hay error de red.
      console.log('Confirmando una sola vez el pago de la tarjeta sandbox configurada.');
      await pay.click();
      continue;
    }
    if (paymentClicked && (/congrats\/approved/.test(url.pathname) || /pago aprobado|pagaste|pago se acredit[oó]/i.test(body))) {
      const back = page.getByRole('link', { name: /volver al sitio|volver a la tienda/i }).first();
      if (await back.isVisible()) { await back.click(); continue; }
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('El sandbox no avanzó por un flujo reconocido. Revisa manualmente el checkout existente; no se reintenta el pago.');
}
