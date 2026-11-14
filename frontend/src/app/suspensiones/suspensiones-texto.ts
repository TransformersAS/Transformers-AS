/** Textos y formato compartidos por las vistas de CU-27 del administrador y del vendedor. */

import { EstadoAclaracion, EstadoSuspension, EstadoTienda } from './models/suspensiones.model';

export const ESTADO_TIENDA: Record<EstadoTienda, string> = {
  ACTIVE: 'Operando',
  SUSPENDED: 'Suspendida temporalmente',
  RESTRICTED: 'Con otra restricción vigente'
};

export const ESTADO_SUSPENSION: Record<EstadoSuspension, string> = {
  ACTIVE: 'Vigente',
  EXPIRED: 'Terminó en su fecha',
  LIFTED: 'Levantada antes de su fin'
};

export const ESTADO_ACLARACION: Record<EstadoAclaracion, string> = {
  OPEN: 'Esperando respuesta',
  ANSWERED: 'Respondida',
  EXPIRED: 'Venció sin respuesta'
};

export const CAMBIO: Record<string, string> = {
  APPLIED: 'Aplicada',
  DURATION_CHANGED: 'Cambio de duración',
  LIFTED: 'Levantada',
  EXPIRED: 'Terminó'
};

export const DECISION: Record<string, string> = {
  REFUND_GRANTED: 'Reclamación resuelta con reembolso al comprador',
  OCULTAR_TEMPORALMENTE: 'Contenido ocultado por moderación',
  RETIRAR: 'Contenido retirado por moderación'
};

/** yyyy-MM-ddTHH:mm:ss a dd/MM/yyyy HH:mm sin pasar por la zona horaria del navegador. */
export function fechaHora(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const [fecha, hora = ''] = iso.split('T');
  const [anio, mes, dia] = fecha.split('-');
  return `${dia}/${mes}/${anio}${hora ? ' ' + hora.slice(0, 5) : ''}`;
}

/** Mensaje del backend o uno genérico; si el error señala un campo, lo devuelve para marcarlo (RNF-027). */
export function errorDeRespuesta(respuesta: { error?: { message?: string; details?: { field?: string } } }):
  { mensaje: string; campo: string | null } {
  return {
    mensaje: respuesta.error?.message ?? 'No se pudo completar la operación. Intenta de nuevo.',
    campo: respuesta.error?.details?.field ?? null
  };
}
