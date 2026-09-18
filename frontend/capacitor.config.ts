import type { CapacitorConfig } from '@capacitor/cli';
/** Configuración mínima para envolver exactamente el mismo build web en iOS y Android. */
const config: CapacitorConfig = { appId: 'co.mercadointegral.app', appName: 'Marketplace Integral', webDir: 'dist/browser' };
export default config;
