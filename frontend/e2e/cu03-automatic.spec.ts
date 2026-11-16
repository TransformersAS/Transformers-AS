import { test, expect } from '@playwright/test';
import { automatic, requireAutomaticSettings, loginMarketplace, paySandbox } from '../e2e-cu03-real/automatic-login';

// Pruebas aisladas del conductor: todas las páginas del proveedor se controlan.
// No generan pagos ni certifican que su interfaz externa conserve estos selectores.
test.beforeEach(() => {
  Object.assign(process.env, { CU03_AUTOMATIC: 'true', CU03_BUYER_EMAIL: 'buyer@demo.test',
    CU03_BUYER_PASSWORD: 'dummy-marketplace-password', CU03_MP_TEST_USER: 'TESTUSER123456',
    CU03_MP_TEST_PASSWORD: 'dummy-sandbox-password', CU03_MP_TEST_CARD_LAST4: '6260', CU03_MP_TEST_CARD_CVV: '123' });
});

test('requiere configuración de pruebas y rechaza usuario personal del proveedor', async () => {
  expect(automatic()).toBe(true);
  requireAutomaticSettings();
  process.env.CU03_MP_TEST_USER = 'personal@example.com';
  expect(() => requireAutomaticSettings()).toThrow(/TESTUSER/);
});

test('ingresa datos del Marketplace solamente en su origen', async ({ page, baseURL }) => {
  await page.route(`${baseURL}/automatic-fixture`, route => route.fulfill({ contentType: 'text/html; charset=utf-8',
    body: '<ion-input name="correo"><input></ion-input><ion-input name="clave"><input type="password"></ion-input><button>Iniciar sesión</button>' }));
  await page.goto('/automatic-fixture');
  await loginMarketplace(page, baseURL!);
  await expect(page.locator('ion-input[name="correo"] input')).toHaveValue('buyer@demo.test');
  await expect(loginMarketplace(page, 'https://otro.example')).rejects.toThrow(/fuera del origen/);
});

for (const rememberedUser of [false, true]) test(`flujo sandbox completa contraseña y paga una vez; usuario recordado=${rememberedUser}`, async ({ page, baseURL }) => {
  let paid = 0;
  await page.route('https://sandbox.mercadopago.com.co/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let html = '';
    if (path === '/options') html = '<div style="position:relative;width:400px;height:100px"><span id="mp_login_row-content">Ingresar con mi cuenta</span><button aria-labelledby="mp_login_row-content" style="position:absolute;inset:0;background:transparent;border:0" onclick="location.href=\'/login\'"></button></div>';
    else if (path === '/login') html = '<label>E-mail o teléfono<input></label><button onclick="location.href=\'/methods\'">Continuar</button>';
    else if (path === '/methods') html = '<h1>Elige un método de verificación para continuar</h1><div style="position:relative;width:400px;height:100px"><span id="password-method">Contraseña Ingresarás tu contraseña.</span><button aria-labelledby="password-method" style="position:absolute;inset:0;background:transparent;border:0" onclick="location.href=\'/password\'"></button></div><button onclick="throw new Error(\'No elegir correo\')">E-mail</button>';
    else if (path === '/password') html = '<input type="password" hidden><label>Contraseña<input autocomplete="current-password" type="text" onkeydown="this.dataset.typed=1"></label><button hidden>Continuar</button><button onclick="if(document.querySelector(\'input[autocomplete]\').dataset.typed===\'1\' && document.querySelector(\'input[autocomplete]\').value===\'dummy-sandbox-password\')location.href=\'/checkout\'">  ' + (rememberedUser ? 'Confirmar' : 'Continuar') + '  </button>';
    else if (path === '/checkout') html = '<p>Sandbox</p><button>Visa **** 6260</button><label>Código de seguridad<input></label><button hidden>Continuar</button><button onclick="if(document.querySelector(\'input\').value===\'123\')location.href=\'/paid\'">Pagar</button>';
    else if (path === '/paid') { paid++; html = `<script>location.href=${JSON.stringify(`${baseURL}/?checkout=sample`)}</script>`; }
    else throw new Error('Página inesperada en el fixture');
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
  });
  await page.goto(`https://sandbox.mercadopago.com.co/${rememberedUser ? 'password' : 'options'}`);
  await paySandbox(page, baseURL!, 'sample');
  expect(paid).toBe(1);
});

test('escribe y confirma la contraseña en el iframe de Mercado Pago', async ({ page, baseURL }) => {
  let submitted = 0;
  await page.route('https://sandbox.mercadopago.com.co/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body: string;
    if (path === '/password') body = '<iframe title="Validación" src="/password-frame"></iframe>';
    else if (path === '/password-frame') body = `<label>Contraseña<input type="password" autocomplete="current-password"></label>
      <button onclick="if(document.querySelector('input').value==='dummy-sandbox-password')top.location.href='/submitted'">Confirmar</button>`;
    else if (path === '/submitted') {
      submitted++;
      body = `<script>location.href=${JSON.stringify(`${baseURL}/?checkout=sample`)}</script>`;
    } else throw new Error('Documento inesperado');
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body });
  });
  await page.goto('https://sandbox.mercadopago.com.co/password');
  await paySandbox(page, baseURL!, 'sample');
  expect(submitted).toBe(1);
});

test('completa el CVV en un iframe del dominio global del proveedor', async ({ page, baseURL }) => {
  let payments = 0;
  await page.route('https://secure-fields.mercadopago.com/**', route => route.fulfill({ contentType: 'text/html; charset=utf-8', body: `
    <label>Código de seguridad<input autocomplete="cc-csc" oninput="parent.postMessage(this.value,'https://sandbox.mercadopago.com.co')"></label>` }));
  await page.route('https://sandbox.mercadopago.com.co/**', async route => {
    if (new URL(route.request().url()).pathname === '/paid') {
      payments++;
      await route.fulfill({ contentType: 'text/html', body: `<script>location.href=${JSON.stringify(`${baseURL}/?checkout=sample`)}</script>` });
      return;
    }
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<p>Sandbox</p><button>Visa **** 6260</button>
      <p>Código de seguridad</p><iframe src="https://secure-fields.mercadopago.com/security"></iframe>
      <button id="pay" disabled onclick="location.href='/paid'">Pagar</button>
      <script>addEventListener('message',e=>{if(e.origin==='https://secure-fields.mercadopago.com')document.querySelector('#pay').disabled=e.data!=='123'})</script>` });
  });
  await page.goto('https://sandbox.mercadopago.com.co/checkout');
  await paySandbox(page, baseURL!, 'sample');
  expect(payments).toBe(1);
});

test('espera el CVV que aparece después de seleccionar la tarjeta', async ({ page, baseURL }) => {
  let payments = 0;
  await page.route('https://sandbox.mercadopago.com.co/**', async route => {
    if (new URL(route.request().url()).pathname === '/paid') {
      payments++;
      await route.fulfill({ contentType: 'text/html', body: `<script>location.href=${JSON.stringify(`${baseURL}/?checkout=sample`)}</script>` });
      return;
    }
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<p>Sandbox</p>
      <button onclick="setTimeout(()=>{document.querySelector('input').hidden=false},500)">Visa **** 6260</button>
      <p>Código de seguridad</p><input hidden autocomplete="cc-csc" oninput="document.querySelector('#pay').disabled=this.value!=='123'">
      <button id="pay" disabled onclick="location.href='/paid'">Pagar</button>` });
  });
  await page.goto('https://sandbox.mercadopago.com.co/checkout');
  await paySandbox(page, baseURL!, 'sample');
  expect(payments).toBe(1);
});

test('se detiene ante verificación de identidad sin pagar', async ({ page, baseURL }) => {
  await page.route('https://sandbox.mercadopago.com.co/**', route => route.fulfill({ contentType: 'text/html', body: '<h1>Necesitamos validar tu identidad</h1>' }));
  await page.goto('https://sandbox.mercadopago.com.co/verification');
  await expect(paySandbox(page, baseURL!, 'sample')).rejects.toThrow(/verificación humana/);
});

test('no confirma un checkout sin indicador sandbox', async ({ page, baseURL }) => {
  await page.route('https://www.mercadopago.com.co/**', route => route.fulfill({ contentType: 'text/html', body: '<button>Visa **** 6260</button><button>Pagar</button>' }));
  await page.goto('https://www.mercadopago.com.co/checkout');
  await expect(paySandbox(page, baseURL!, 'sample')).rejects.toThrow(/indicador sandbox/);
});
