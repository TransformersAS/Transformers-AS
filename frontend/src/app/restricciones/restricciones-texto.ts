/** Textos y formato compartidos por las vistas de CU-22 del administrador y de la propia cuenta. */

import { EstadoInformacion, EstadoRestriccion, Rol, TipoAntecedente, TipoCambioRestriccion } from './models/restricciones.model';

export const ESTADO_RESTRICCION: Record<EstadoRestriccion, string> = {
  ACTIVE: 'Vigente',
  EXPIRED: 'Terminó en su fecha',
  WITHDRAWN: 'Retirada antes de su fin'
};

export const ESTADO_INFORMACION: Record<EstadoInformacion, string> = {
  OPEN: 'Esperando respuesta',
  ANSWERED: 'Respondida',
  EXPIRED: 'Venció sin respuesta'
};

export const CAMBIO_RESTRICCION: Record<TipoCambioRestriccion, string> = {
  APPLIED: 'Aplicada',
  DURATION_CHANGED: 'Cambio de duración',
  MADE_PERMANENT: 'Se volvió permanente',
  MADE_TEMPORARY: 'Se volvió temporal',
  WITHDRAWN: 'Retirada',
  EXPIRED: 'Terminó'
};

export const TIPO_ANTECEDENTE: Record<TipoAntecedente, string> = {
  CLAIM: 'Reclamación resuelta con reembolso al comprador',
  MODERATION: 'Decisión de moderación',
  RESTRICTION: 'Restricción anterior sobre la cuenta'
};

export const ROL: Record<Rol, string> = {
  COMPRADOR: 'Comprador',
  VENDEDOR: 'Vendedor',
  ADMIN: 'Administrador',
  SOPORTE: 'Soporte'
};

/** "Toda la cuenta" o "Rol <X>", según el alcance. */
export function alcance(role: Rol | null): string {
  return role ? `Rol ${ROL[role]}` : 'Toda la cuenta';
}

/** Decisiones de reclamaciones (CU-13) y de moderación (CU-21) que cuentan como antecedente. */
const DECISION_ANTECEDENTE: Record<string, string> = {
  REFUND_GRANTED: 'Reembolso aprobado por soporte',
  RETIRAR: 'Publicación retirada',
  OCULTAR_TEMPORALMENTE: 'Ocultada temporalmente'
};

/**
 * Decisión de un antecedente para mostrar. Llega como valor del enum de su módulo; el estado de una restricción
 * anterior se traduce con ESTADO_RESTRICCION y el resto con DECISION_ANTECEDENTE. Una desconocida se muestra tal cual.
 */
export function decisionAntecedente(tipo: TipoAntecedente, decision: string): string {
  const texto = tipo === 'RESTRICTION' ? ESTADO_RESTRICCION[decision as EstadoRestriccion] : DECISION_ANTECEDENTE[decision];
  return texto ?? decision;
}

/** Monto en pesos colombianos sin decimales: 99000 → «$ 99.000». */
export function pesos(valor: number): string {
  return new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP', maximumFractionDigits: 0 }).format(valor);
}

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
