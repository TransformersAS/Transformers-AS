import { HttpErrorResponse } from '@angular/common/http';

import { ErrorReporte } from './reporte.model';

/** Campos del formulario de reporte a los que puede apuntar un error del backend. */
export type CampoReporte = 'motivo' | 'descripcion' | 'imagenes' | 'respuesta';

export interface ErrorInterpretado {
    /** Mensaje que no corresponde a un campo concreto (contenido inexistente, propio, sin permiso, sin conexión...). */
    general: string | null;
    /** Mensaje junto al campo que lo causó. */
    campo: { nombre: CampoReporte; mensaje: string } | null;
    /** Id del reporte ya existente cuando el backend responde 409 REPORT_ALREADY_OPEN. */
    reporteExistente: number | null;
}

/** El backend nombra los campos en inglés; el formulario los llama en español. */
function campoDelFormulario(campoApi: string | undefined): CampoReporte | null {
    if (campoApi === 'reason') {
        return 'motivo';
    }
    if (campoApi === 'description') {
        return 'descripcion';
    }
    if (campoApi === 'text') {
        return 'respuesta';
    }
    if (campoApi && campoApi.startsWith('evidences')) {
        return 'imagenes';
    }
    return null;
}

/**
 * Traduce un error HTTP en algo que la pantalla pueda mostrar junto al campo correcto (RNF-027). Si el backend no
 * indica campo, el mensaje va arriba del formulario.
 */
export function interpretarErrorReporte(e: HttpErrorResponse, porDefecto: string): ErrorInterpretado {
    if (e.status === 0) {
        return sinCampo('No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo.');
    }
    if (e.status === 413) {
        return { general: null, campo: { nombre: 'imagenes', mensaje: 'Las imágenes superan el tamaño permitido.' },
            reporteExistente: null };
    }
    const cuerpo = e.error as ErrorReporte | null;
    const mensaje = cuerpo?.message || porDefecto;
    if (e.status === 409 && cuerpo?.code === 'REPORT_ALREADY_OPEN') {
        return { general: mensaje, campo: null, reporteExistente: cuerpo.details?.reportId ?? null };
    }
    if (e.status === 401) {
        return sinCampo('Tu sesión no es válida. Inicia sesión de nuevo.');
    }
    if (cuerpo?.code === 'REPORTER_ROLE_REQUIRED') {
        return sinCampo('Para reportar necesitas el rol activo de comprador o vendedor.');
    }
    const campo = campoDelFormulario(cuerpo?.details?.field);
    if (campo) {
        return { general: null, campo: { nombre: campo, mensaje }, reporteExistente: null };
    }
    return sinCampo(mensaje);
}

function sinCampo(general: string): ErrorInterpretado {
    return { general, campo: null, reporteExistente: null };
}
