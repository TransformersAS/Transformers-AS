/** Conversación entre comprador y una tienda; la fecha se mantiene ISO para la API. */
export interface Conversacion { id: string; tienda: string; ultimoMensaje: string; actualizadoEn: string; sinLeer: number; }
