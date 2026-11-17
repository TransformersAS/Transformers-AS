import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

/**
 * Configuración común de los recorridos visuales de CU-23 a CU-27: Chrome visible, una acción cada 700 ms y la API
 * simulada dentro de cada prueba. Las reglas reales las comprueban las pruebas del backend.
 */
export function configuracionDemo(casoDeUso: string, prueba: string, puerto: string) {
  return defineConfig({
    ...base,
    testDir: './e2e-cu23-27-demo',
    testMatch: prueba,
    workers: 1,
    timeout: 420000,
    reporter: [['list'], ['html', { outputFolder: `playwright-report-${casoDeUso}-demo`, open: 'never' }]],
    outputDir: `../artifacts/playwright-${casoDeUso}-demo`,
    use: {
      ...base.use,
      baseURL: `http://127.0.0.1:${puerto}`,
      headless: process.env['DEMO_RAPIDA'] === '1',
      launchOptions: { slowMo: process.env['DEMO_RAPIDA'] === '1' ? 0 : 700 },
    },
    webServer: {
      // DEMO_RAPIDA=1 valida el recorrido sin pausas ni ventana, con la compilación que ya exista.
      command: process.env['DEMO_RAPIDA'] === '1' ? 'node e2e-security-server.cjs' : 'npm run build && node e2e-security-server.cjs',
      env: { SECURITY_E2E_PORT: puerto },
      url: `http://127.0.0.1:${puerto}`,
      reuseExistingServer: false,
      timeout: 180000,
    },
    projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  });
}
