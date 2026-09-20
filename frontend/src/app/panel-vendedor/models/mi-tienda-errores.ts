import { HttpErrorResponse } from '@angular/common/http';

import { CampoTienda } from './mi-tienda-formulario';
import { ErrorTienda, EstadoTienda, LIMITES_TIENDA, TipoImagen } from './mi-tienda.model';

/**
 * Qué hacer con un error del backend. Los errores no dicen qué campo fallo: lo indica `code`, y aquí se traduce a un
 * campo y a un mensaje que explica cómo corregirlo (RNF-027).
 */
export type ErrorInterpretado =
    | { tipo: 'campo'; campo: CampoTienda; mensaje: string; /** Métodos que el marketplace ofrece hoy (A6), para refrescar la lista. */ disponibles?: string[] }
    | { tipo: 'bloqueada'; mensaje: string; estado?: EstadoTienda; motivo?: string }
    | { tipo: 'concurrencia'; mensaje: string; }
    | { tipo: 'sin-tienda' | 'sin-rol' | 'no-autorizada' | 'sesion' | 'conexion' | 'general'; mensaje: string };

const IMAGEN_MAXIMO_MB = 5;

/** Errores de un campo que no necesitan los detalles del error. */
const MENSAJE_POR_CODIGO: Record<string, { campo: CampoTienda; mensaje: string }> = {
    STORE_NAME_REQUIRED: { campo: 'name', mensaje: 'Escribe el nombre de tu tienda.' },
    STORE_NAME_TOO_LONG: {
        campo: 'name', mensaje: `El nombre es demasiado largo: usa hasta ${LIMITES_TIENDA.nombre} caracteres.`
    },
    STORE_NAME_INVALID: {
        campo: 'name', mensaje: 'El nombre tiene caracteres que no se admiten. Escríbelo solo con letras, números y signos comunes.'
    },
    STORE_NAME_TAKEN: {
        campo: 'name', mensaje: 'Ya existe otra tienda con ese nombre. Prueba con uno distinto.'
    },
    STORE_DESCRIPTION_TOO_LONG: {
        campo: 'description',
        mensaje: `La descripción es demasiado larga: usa hasta ${LIMITES_TIENDA.descripcion} caracteres.`
    },
    STORE_DESCRIPTION_INVALID: {
        campo: 'description', mensaje: 'La descripción tiene caracteres que no se admiten. Quítalos e inténtalo de nuevo.'
    },
    STORE_CONTACT_EMAIL_INVALID: {
        campo: 'contactEmail', mensaje: 'Escribe un correo válido, por ejemplo ventas@mitienda.com, o deja el campo vacío.'
    },
    STORE_CONTACT_PHONE_INVALID: {
        campo: 'contactPhone',
        mensaje: 'Escribe un teléfono de 7 a 15 dígitos (puedes usar +, espacios, guiones y paréntesis), o deja el campo vacío.'
    },
    STORE_HOURS_TOO_LONG: {
        campo: 'businessHours', mensaje: `Los horarios son demasiado largos: usa hasta ${LIMITES_TIENDA.horarios} caracteres.`
    },
    STORE_HOURS_INVALID: {
        campo: 'businessHours', mensaje: 'Los horarios tienen caracteres que no se admiten. Quítalos e inténtalo de nuevo.'
    },
    STORE_RETURN_WINDOW_INVALID: {
        campo: 'returnWindowDays',
        mensaje: `El plazo de devolución debe estar entre 1 y ${LIMITES_TIENDA.plazoMaximo} días.`
    },
    STORE_POLICY_TEXT_TOO_LONG: {
        campo: 'policyText', mensaje: `El texto de la política es demasiado largo: usa hasta ${LIMITES_TIENDA.politica} caracteres.`
    },
    STORE_POLICY_TEXT_INVALID: {
        campo: 'policyText', mensaje: 'El texto de la política tiene caracteres que no se admiten. Quítalos e inténtalo de nuevo.'
    },
    STORE_SHIPPING_METHODS_REQUIRED: {
        campo: 'shippingMethods', mensaje: 'Elige al menos un método de envío: la tienda debe ofrecer alguno.'
    }
};

/** Códigos de imagen (A4). El campo depende de qué imagen se estaba subiendo. */
const MENSAJE_IMAGEN: Record<string, string> = {
    IMAGE_EMPTY: 'El archivo está vacío. Elige otra imagen.',
    IMAGE_TOO_LARGE: `La imagen pesa más de ${IMAGEN_MAXIMO_MB} MB. Reduce su tamaño o elige otra.`,
    IMAGE_FORMAT_UNSUPPORTED: 'Solo se admiten imágenes JPG y PNG. Elige otro archivo.',
    IMAGE_DIMENSIONS_TOO_LARGE: 'La imagen es demasiado grande en píxeles. Redúcela e inténtalo de nuevo.',
    IMAGE_CORRUPT: 'No se pudo leer la imagen: el archivo parece dañado. Elige otra.'
};

/**
 * Una imagen que el backend rechazó al aplicar los cambios. Lleva qué imagen fue, para mostrar el error bajo ella y
 * no guardar el texto (A4).
 */
export class ImagenRechazada {
    constructor(readonly tipo: TipoImagen, readonly error: HttpErrorResponse) {}
}

/** `imagen` indica qué imagen se estaba subiendo, para asignarle el error de imagen. */
export function interpretarError(e: HttpErrorResponse, imagen?: TipoImagen): ErrorInterpretado {
    const cuerpo = e.error as ErrorTienda | null;
    const codigo = cuerpo?.code;
    const detalles = cuerpo?.details;

    if (e.status === 0) {
        return { tipo: 'conexion', mensaje: 'No se pudo conectar con el servidor. Comprueba tu conexión y vuelve a intentarlo.' };
    }
    if (e.status === 401 && codigo === 'STORE_IDENTITY_MISSING') {
        return { tipo: 'sin-tienda', mensaje: 'Tu cuenta todavía no tiene una tienda asociada.' };
    }
    if (e.status === 401) {
        return { tipo: 'sesion', mensaje: 'Tu sesión ya no es válida. Inicia sesión de nuevo con una cuenta de vendedor.' };
    }
    if (e.status === 403 && codigo === 'SELLER_ROLE_REQUIRED') {
        return { tipo: 'sin-rol', mensaje: 'Necesitas el rol activo Vendedor. Cámbialo en «Acceso».' };
    }
    if (e.status === 403 && codigo === 'STORE_MODIFICATION_BLOCKED') {
        return {
            tipo: 'bloqueada',
            mensaje: 'Tu tienda ya no se puede modificar.',
            estado: detalles?.status,
            motivo: detalles?.reason
        };
    }
    if (e.status === 403) {
        return { tipo: 'no-autorizada', mensaje: 'Tu cuenta no está autorizada para configurar esta tienda.' };
    }
    if (codigo === 'STORE_CONCURRENT_UPDATE') {
        return {
            tipo: 'concurrencia',
            mensaje: 'Otra sesión modificó tu tienda mientras la editabas. Recarga para ver los cambios más recientes; ' +
                'los que hiciste aquí se descartarán.'
        };
    }
    if (codigo === 'STORE_POLICY_BELOW_MINIMUM') {
        const minimo = detalles?.minReturnWindowDays;
        return {
            tipo: 'campo',
            campo: 'returnWindowDays',
            mensaje: minimo === undefined
                ? 'El plazo de devolución es menor al mínimo que exige el marketplace. Súbelo e inténtalo de nuevo.'
                : `El plazo de devolución no puede ser menor a ${minimo} días: es una regla obligatoria del marketplace. ` +
                  `Sube el plazo a ${minimo} días o más.`
        };
    }
    if (codigo === 'STORE_SHIPPING_METHOD_UNAVAILABLE') {
        const no = detalles?.unavailable ?? [];
        return {
            tipo: 'campo',
            campo: 'shippingMethods',
            mensaje: (no.length > 0
                ? `El marketplace ya no ofrece: ${no.join(', ')}. `
                : 'Hay métodos de envío que el marketplace no ofrece. ') +
                'Quítalos y elige entre los métodos disponibles.',
            disponibles: detalles?.available
        };
    }
    if (codigo && MENSAJE_POR_CODIGO[codigo]) {
        return { tipo: 'campo', ...MENSAJE_POR_CODIGO[codigo] };
    }
    if (codigo && MENSAJE_IMAGEN[codigo] && imagen) {
        return { tipo: 'campo', campo: imagen === 'LOGO' ? 'logo' : 'portada', mensaje: MENSAJE_IMAGEN[codigo] };
    }
    if (e.status === 413 && imagen) {
        return {
            tipo: 'campo',
            campo: imagen === 'LOGO' ? 'logo' : 'portada',
            mensaje: MENSAJE_IMAGEN['IMAGE_TOO_LARGE']
        };
    }
    return { tipo: 'general', mensaje: cuerpo?.message || 'No se pudo completar la operación. Inténtalo de nuevo.' };
}
