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

for (const state of ['PENDING', 'APPROVED', 'REVIEW']) {
  test(`return ignores browser approval and displays backend state ${state}`, async ({ page }) => {
    await mockApi(page, { state });
    await page.goto(`/?checkout=${checkout}&payment_id=456&status=approved`);
    const banner = page.getByRole('region', { name: 'Estado del pago' });
    if (state === 'PENDING') {
      await expect(banner).toContainText('Tu pago está pendiente');
      await expect(banner).not.toContainText('Pago de pruebas aprobado');
    } else if (state === 'APPROVED') {
      await expect(banner).toContainText('Tu pedido es el #99');
    } else {
      await expect(banner).toContainText('No vuelvas a pagar');
    }
  });
}
