import type { Page } from '@playwright/test';

// Rótulo exclusivo del ejecutor. Solo muestra hechos recibidos, no simula estado.
export async function purchaseSummary(page: Page, title: string, items: { name: string; quantity: number }[], total?: number) {
  console.log(`${title}: ${items.map(item => `${item.quantity} × ${item.name}`).join(', ')}${total === undefined ? '' : `; total ${total} COP`}`);
  await page.evaluate(({ title, items, total }) => {
    document.getElementById('cu03-runner-summary')?.remove();
    const box = document.createElement('aside');
    box.id = 'cu03-runner-summary';
    box.setAttribute('aria-label', 'Resumen del recorrido de prueba');
    Object.assign(box.style, { position: 'fixed', top: '12px', left: '12px', zIndex: '99999', maxWidth: '360px',
      padding: '16px', border: '2px solid #f56b4b', borderRadius: '14px', background: '#fffdf6', color: '#183037', font: '16px sans-serif', pointerEvents: 'none' });
    const heading = document.createElement('strong'); heading.textContent = title; box.appendChild(heading);
    for (const item of items) { const row = document.createElement('p'); row.textContent = `${item.quantity} × ${item.name}`; box.appendChild(row); }
    if (total !== undefined) { const row = document.createElement('p'); row.textContent = `Total: ${new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP' }).format(total)}`; box.appendChild(row); }
    const note = document.createElement('small'); note.textContent = 'Resumen del ejecutor · datos reales de esta compra'; box.appendChild(note);
    document.body.appendChild(box);
  }, { title, items, total });
}

export async function clearPurchaseSummary(page: Page) {
  await page.evaluate(() => document.getElementById('cu03-runner-summary')?.remove());
}
