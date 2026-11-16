import { test } from '@playwright/test';
import { securityFlow, cancellationFlow } from './account-order-flows';
test('guion CU-08 y CU-11 con cuentas y pedido provisionados por el backend real', async ({ page, request }) => {
  await securityFlow(page, request, 'demo@marketplace.local', 'MarketplaceDemo123!');
  await cancellationFlow(page, 'comprador.demo@example.com', 'MarketplaceDemo123!', 'Camiseta demostración CU-11');
});
