import { test, expect, Page } from '@playwright/test';

const checkout = '11111111-1111-4111-8111-111111111111';
async function mockApi(page: Page, options: { unconfigured?: boolean; state?: string } = {}) {
  await page.route('**/api/**', async route => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return reply({ accountId: 1, email: 'buyer@example.test', roles: ['COMPRADOR'], activeRole: 'COMPRADOR' });
    if (path === '/api/auth/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    if (path === '/api/cart') return reply({ cartId: 1, items: [{ id: 1, productId: 2, productName: 'Jean', quantity: 1, unitPrice: 95000, subtotal: 95000 }], total: 95000 });
    if (path === '/api/addresses') return reply({ id: 8 });
    if (path === '/api/checkout/preview') return reply({ subtotal: 95000, discount: 0, shippingCost: 10000, total: 105000, couponValid: false });
    if (path === '/api/payments/mercadopago/checkouts') {
      expect(req.headers()['x-csrf-token']).toBe('csrf');
      expect(req.postDataJSON()).toEqual({ addressId: 8, shippingMethod: 'STANDARD', couponCode: '' });
      return options.unconfigured ? reply({ detail: 'Mercado Pago aún no está configurado.' }, 503)
        : reply({ id: checkout, status: 'PENDING', total: 105000, orderId: null,
          checkoutUrl: 'https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=test' });
    }
    if (path.endsWith('/refresh')) {
      expect(req.postDataJSON()).toEqual({ paymentId: '456' });
      expect(req.headers()['x-csrf-token']).toBe('csrf');
      return reply({ id: checkout, status: options.state ?? 'PENDING', orderId: options.state === 'APPROVED' ? 99 : null, total: 105000 });
    }
    if (path === '/api/recommendations') return reply({ userId: 1, strategy: 'test', products: [] });
    return reply([]);
  });
}

for (const unconfigured of [true, false]) {
  test(`hosted checkout replaces simulation choices; unconfigured=${unconfigured}`, async ({ page }) => {
    await mockApi(page, { unconfigured });
    let providerVisits = 0;
    await page.route('https://sandbox.mercadopago.com.co/**', route => {
      providerVisits++;
      return route.fulfill({ contentType: 'text/html', body: '<h1>Hosted checkout test double</h1>' });
    });
    await page.goto('/');
    await expect(page.locator('.welcome-strip')).toContainText('Hola, buyer');
    await page.getByRole('button', { name: 'Abrir carrito', exact: true }).click();
    await page.getByRole('button', { name: 'Continuar compra', exact: true }).click();
    await page.getByPlaceholder('Escribe tu dirección de entrega').fill('Calle 1 # 2-3');
    await page.getByRole('button', { name: 'Calcular total', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true })).toBeVisible();
    await expect(page.locator('.payment-section select')).toHaveCount(0);
    await expect(page.getByText('Simular pago rechazado')).toHaveCount(0);
    await page.getByRole('button', { name: 'Continuar a Mercado Pago', exact: true }).click();
    if (unconfigured) {
      await expect(page.getByText('Mercado Pago aún no está configurado.', { exact: true })).toBeVisible();
      expect(providerVisits).toBe(0);
    } else {
      await expect(page).toHaveURL(/https:\/\/sandbox\.mercadopago\.com\.co\//);
      expect(providerVisits).toBe(1);
    }
  });
}

for (const state of ['PENDING', 'APPROVED', 'REVIEW', 'REFUNDED']) {
  test(`return ignores browser approval and displays backend state ${state}`, async ({ page }) => {
    await mockApi(page, { state });
    await page.goto(`/?checkout=${checkout}&payment_id=456&status=approved`);
    const banner = page.getByRole('region', { name: 'Estado del pago' });
    if (state === 'PENDING') {
      await expect(banner).toContainText('Tu pago está pendiente');
      await expect(banner).not.toContainText('Pago de pruebas aprobado');
    } else if (state === 'APPROVED') {
      await expect(banner).toContainText('Tu pedido es el #99');
    } else if (state === 'REFUNDED') {
      await expect(banner).toContainText('El pago asociado a esta compra fue devuelto.');
    } else {
      await expect(banner).toContainText('No vuelvas a pagar');
    }
  });
}

async function closureApi(page: Page, financial = 'REQUIRES_VERIFICATION', failure = 0) {
  await mockApi(page);
  let closed = false, calls = 0;
  await page.route('**/api/payments/mercadopago/checkouts/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/cancellation')) {
      calls++;
      expect(route.request().headers()['x-csrf-token']).toBe('csrf');
      if (failure) return route.fulfill({status:failure,contentType:'application/json',body:'{}'});
      closed=true;
    }
    return route.fulfill({contentType:'application/json',body:JSON.stringify({id:checkout,status:closed?'CANCELLED':failure===409&&calls?'APPROVED':'PENDING',
      orderId:failure===409&&calls?99:null,total:105000,checkoutUrl:closed?null:'https://sandbox.mercadopago.com.co/test',
      cancelledAt:closed?'2026-10-31T10:00:00':null,financialStatus:closed?financial:null})});
  });
  return () => calls;
}
for (const [financial,message] of [
  ['REQUIRES_VERIFICATION','Todavía debe verificarse si hubo un cobro.'],
  ['PENDING_RESOLUTION','Existe una operación financiera cuya resolución sigue pendiente.'],
  ['REQUIRES_REVIEW','La resolución financiera requiere revisión.'],
  ['REFUNDED','Devolución confirmada según los registros financieros verificados.']
]) test(`cierre y recarga conservan seguimiento ${financial}`, async ({page})=>{
  const calls=await closureApi(page,financial);
  await page.goto(`/?checkout=${checkout}`);
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await expect(page.getByRole('group',{name:'Confirmar cierre de compra'})).toContainText('no necesariamente detiene un cobro externo');
  await page.getByRole('button',{name:'Regresar sin cancelar',exact:true}).click();expect(calls()).toBe(0);
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await page.getByRole('button',{name:'Confirmar cierre',exact:true}).click();
  const banner=page.getByRole('region',{name:'Estado del pago'});
  await expect(banner).toContainText('Proceso de compra cerrado');await expect(banner).toContainText(message);
  await expect(page.getByRole('button',{name:'Cancelar proceso de compra',exact:true})).toHaveCount(0);
  await expect(banner.locator('a[href*="mercadopago"]')).toHaveCount(0);
  await page.reload();await expect(banner).toContainText(message);expect(calls()).toBe(1);
});
for(const status of [401,403,404,409]) test(`cierre informa error ${status}`,async({page})=>{
  await closureApi(page,'REQUIRES_VERIFICATION',status);await page.goto(`/?checkout=${checkout}`);
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await page.getByRole('button',{name:'Confirmar cierre',exact:true}).click();
  if(status===401) {
    await expect(page.getByRole('region',{name:'Estado del pago'})).toHaveCount(0);
    await expect(page.locator('app-checkout-status').getByRole('status')).toContainText('Vuelve a abrir el enlace');
    expect(new URL(page.url()).searchParams.has('checkout')).toBe(false);
  } else await expect(page.getByRole('region',{name:'Estado del pago'}).getByRole('alert')).toBeVisible();
  if(status===409){await expect(page.getByRole('region',{name:'Estado del pago'})).toContainText('Tu pedido es el #99');}
});

test('fallo de red no repite cancelación y permite consultar antes de reintentar',async({page})=>{
  const calls=await closureApi(page);let attempts=0;
  await page.route('**/api/payments/mercadopago/checkouts/*/cancellation',route=>{
    attempts++;return attempts===1?route.abort('failed'):route.fallback();
  });
  await page.goto(`/?checkout=${checkout}`);
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await page.getByRole('button',{name:'Confirmar cierre',exact:true}).click();
  await expect(page.getByRole('region',{name:'Estado del pago'}).getByRole('alert')).toContainText('Consulta el estado');
  expect(attempts).toBe(1);
  await page.getByRole('button',{name:'Consultar estado del pago',exact:true}).click();
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await page.getByRole('button',{name:'Confirmar cierre',exact:true}).click();
  await expect(page.getByText('Proceso de compra cerrado',{exact:true})).toBeVisible();
  expect(attempts).toBe(2);expect(calls()).toBe(1);
});

test('logout limpia URL y una respuesta tardía de cancelación no revela datos',async({page})=>{
  await closureApi(page);
  let release!:()=>void, entered!:()=>void;
  const pending=new Promise<void>(resolve=>release=resolve), started=new Promise<void>(resolve=>entered=resolve);
  await page.route('**/api/payments/mercadopago/checkouts/*/cancellation',async route=>{
    entered();await pending;
    await route.fulfill({contentType:'application/json',body:JSON.stringify({id:checkout,status:'CANCELLED',cancelledAt:'2026-10-31T10:00:00',financialStatus:'REFUNDED'})}).catch(()=>{});
  });
  await page.goto(`/?checkout=${checkout}`);
  await page.getByRole('button',{name:'Cancelar proceso de compra',exact:true}).click();
  await page.getByRole('button',{name:'Confirmar cierre',exact:true}).click();await started;
  await expect(page.getByRole('button',{name:'Confirmar cierre',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'Ver cuenta',exact:true}).click();
  await page.getByRole('button',{name:'Cerrar sesión',exact:true}).click();
  await expect(page.getByRole('region',{name:'Estado del pago'})).toHaveCount(0);
  expect(new URL(page.url()).searchParams.has('checkout')).toBe(false);
  release();await expect(page.getByText('Proceso de compra cerrado',{exact:true})).toHaveCount(0);
});
