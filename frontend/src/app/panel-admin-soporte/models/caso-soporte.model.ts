/** Unidad moderable por administración o soporte, sin filtrar datos de usuario de más. */
export interface CasoSoporte { id: string; tipo: 'contenido' | 'cuenta' | 'pedido'; prioridad: 'alta' | 'media' | 'baja'; }
