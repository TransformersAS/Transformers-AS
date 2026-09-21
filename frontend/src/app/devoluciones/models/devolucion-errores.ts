import { HttpErrorResponse } from '@angular/common/http';

import { ErrorDevolucion } from './devolucion.model';

/** Campos de los formularios de devoluciones a los que puede apuntar un error del backend. */
export type CampoDevolucion = 'motivo' | 'descripcion' | 'imagenes' | 'respuesta' | 'mensaje' | 'nota' | 'metodo'
    | 'problema';

export interface ErrorInterpretado {
    /** Mensaje que no corresponde a un campo (no elegible, servicio no disponible, sin permiso, sin conexión...). */
    general: string | null;
    /** Mensaje junto al campo que lo causó. */
    campo: { nombre: CampoDevolucion; mensaje: string } | null;
    /** Código estable del backend, para decidir qué ofrecer (reintentar, ver la existente, elegir otro método). */
    codigo: string | null;
}

/** El backend nombra los campos en inglés; los formularios los llaman en español. */
function campoDelFormulario(campoApi: string | undefined): CampoDevolucion | null {
    switch (campoApi) {
        case 'reason': return 'motivo';
        case 'description': return 'descripcion';
        case 'text': return 'respuesta';
        case 'message': return 'mensaje';
        case 'note': return 'nota';
        case 'method': return 'metodo';
        default: return campoApi && campoApi.startsWith('evidences') ? 'imagenes' : null;
    }
}

/**
 * Traduce un error HTTP en algo que la pantalla pueda mostrar junto al campo correcto (RNF-027). Sin campo, el mensaje
 * va arriba del formulario. {@code campoPorDefecto} sirve para acciones con un solo campo (la descripción del problema).
 */
export function interpretarErrorDevolucion(e: HttpErrorResponse, porDefecto: string,
                                           campoPorDefecto: CampoDevolucion | null = null): ErrorInterpretado {
    if (e.status === 0) {
        return { general: 'No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo.', campo: null,
            codigo: null };
    }
    if (e.status === 413) {
        return { general: null, campo: { nombre: 'imagenes', mensaje: 'Las imágenes superan el tamaño permitido.' },
            codigo: null };
    }
    if (e.status === 401) {
        return { general: 'Tu sesión no es válida. Inicia sesión de nuevo.', campo: null, codigo: null };
    }
    const cuerpo = e.error as ErrorDevolucion | null;
    const mensaje = cuerpo?.message || porDefecto;
    const codigo = cuerpo?.code ?? null;
    const campo = campoDelFormulario(cuerpo?.details?.field)
        ?? (campoPorDefecto !== null && e.status === 400 ? campoPorDefecto : null);
    if (campo) {
        return { general: null, campo: { nombre: campo, mensaje }, codigo };
    }
    return { general: mensaje, campo: null, codigo };
}
