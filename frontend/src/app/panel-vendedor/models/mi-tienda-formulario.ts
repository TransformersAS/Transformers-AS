import {
    ConfiguracionTienda,
    LIMITES_TIENDA,
    SolicitudTienda
} from './mi-tienda.model';

/** Campos que pueden mostrar un error junto a ellos (los de imagen se añaden con las imágenes). */
export type CampoTienda =
    | 'name'
    | 'description'
    | 'contactEmail'
    | 'contactPhone'
    | 'businessHours'
    | 'returnWindowDays'
    | 'policyText'
    | 'shippingMethods'
    | 'logo'
    | 'portada';

export type ErroresTienda = Partial<Record<CampoTienda, string>>;

/**
 * Copia editable de la configuración. Los textos son siempre `string` (vacío = sin dato) para enlazarlos a los
 * controles; al enviar, el vacío se convierte en null. Cancelar la edición es volver a crearlo desde lo guardado (A9).
 */
export interface BorradorTienda {
    name: string;
    description: string;
    contactEmail: string;
    contactPhone: string;
    businessHours: string;
    returnWindowDays: number | null;
    policyText: string;
    shippingMethods: string[];
}

export function borradorDe(configuracion: ConfiguracionTienda): BorradorTienda {
    return {
        name: configuracion.name,
        description: configuracion.description ?? '',
        contactEmail: configuracion.contactEmail ?? '',
        contactPhone: configuracion.contactPhone ?? '',
        businessHours: configuracion.businessHours ?? '',
        returnWindowDays: configuracion.returnWindowDays,
        policyText: configuracion.policyText ?? '',
        shippingMethods: [...configuracion.shippingMethods.enabled]
    };
}

function textoOpcional(valor: string): string | null {
    return valor.trim() === '' ? null : valor.trim();
}

/** Cuerpo para POST /preview y PUT. El backend normaliza y valida; aquí solo se vacían los textos en blanco. */
export function aSolicitud(borrador: BorradorTienda, plazoPorDefecto: number): SolicitudTienda {
    return {
        name: borrador.name.trim(),
        description: textoOpcional(borrador.description),
        contactEmail: textoOpcional(borrador.contactEmail),
        contactPhone: textoOpcional(borrador.contactPhone),
        businessHours: textoOpcional(borrador.businessHours),
        returnWindowDays: borrador.returnWindowDays ?? plazoPorDefecto,
        policyText: textoOpcional(borrador.policyText),
        shippingMethods: [...borrador.shippingMethods]
    };
}

// Mismas reglas de forma que el backend (StoreProfile); si el cliente las relaja o se equivoca, decide el backend.
const CORREO = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;
const CARACTERES_TELEFONO = /^\+?[0-9 ()\-.]+$/;

/**
 * Comprobaciones locales, solo como ayuda: evitan enviar lo que se sabe que se rechazará y dicen cómo corregirlo
 * (RNF-027). El backend es la autoridad y vuelve a validar todo.
 */
export function validarBorrador(borrador: BorradorTienda, minimoPlazo: number, disponibles: string[]): ErroresTienda {
    const errores: ErroresTienda = {};

    const nombre = borrador.name.trim();
    if (nombre === '') {
        errores.name = 'Escribe el nombre de tu tienda.';
    } else if (nombre.length > LIMITES_TIENDA.nombre) {
        errores.name = `El nombre es demasiado largo: usa hasta ${LIMITES_TIENDA.nombre} caracteres.`;
    }

    if (borrador.description.trim().length > LIMITES_TIENDA.descripcion) {
        errores.description = `La descripción es demasiado larga: usa hasta ${LIMITES_TIENDA.descripcion} caracteres.`;
    }

    const correo = borrador.contactEmail.trim().toLowerCase();
    if (correo !== '' && (correo.length > LIMITES_TIENDA.correo || !CORREO.test(correo))) {
        errores.contactEmail = 'Escribe un correo válido, por ejemplo ventas@mitienda.com, o deja el campo vacío.';
    }

    const telefono = borrador.contactPhone.trim().replace(/\s+/g, ' ');
    if (telefono !== '') {
        const digitos = (telefono.match(/\d/g) ?? []).length;
        if (telefono.length > LIMITES_TIENDA.telefono || !CARACTERES_TELEFONO.test(telefono) || digitos < 7 || digitos > 15) {
            errores.contactPhone =
                'Escribe un teléfono de 7 a 15 dígitos (puedes usar +, espacios, guiones y paréntesis), o deja el campo vacío.';
        }
    }

    if (borrador.businessHours.trim().length > LIMITES_TIENDA.horarios) {
        errores.businessHours = `Los horarios son demasiado largos: usa hasta ${LIMITES_TIENDA.horarios} caracteres.`;
    }

    const plazo = borrador.returnWindowDays;
    if (plazo === null || Number.isNaN(plazo)) {
        errores.returnWindowDays = `Escribe el plazo de devolución en días (mínimo ${minimoPlazo}).`;
    } else if (!Number.isInteger(plazo)) {
        errores.returnWindowDays = 'El plazo de devolución debe ser un número entero de días.';
    } else if (plazo < minimoPlazo) {
        errores.returnWindowDays =
            `El plazo de devolución no puede ser menor a ${minimoPlazo} días: es una regla obligatoria del marketplace. ` +
            `Sube el plazo a ${minimoPlazo} días o más.`;
    } else if (plazo > LIMITES_TIENDA.plazoMaximo) {
        errores.returnWindowDays = `El plazo de devolución no puede superar ${LIMITES_TIENDA.plazoMaximo} días.`;
    }

    if (borrador.policyText.trim().length > LIMITES_TIENDA.politica) {
        errores.policyText = `El texto de la política es demasiado largo: usa hasta ${LIMITES_TIENDA.politica} caracteres.`;
    }

    const noDisponibles = borrador.shippingMethods.filter(metodo => !disponibles.includes(metodo));
    if (borrador.shippingMethods.length === 0) {
        errores.shippingMethods = 'Elige al menos un método de envío: la tienda debe ofrecer alguno.';
    } else if (noDisponibles.length > 0) {
        errores.shippingMethods =
            `El marketplace ya no ofrece: ${noDisponibles.join(', ')}. Quítalos y elige entre los métodos disponibles.`;
    }

    return errores;
}

/** Un cambio entre lo guardado y lo que se va a guardar, para mostrarlo en la vista previa. */
export interface CambioTienda {
    campo: string;
    antes: string;
    despues: string;
}

function mostrar(valor: string | number | null | undefined): string {
    return valor === null || valor === undefined || valor === '' ? 'Sin dato' : String(valor);
}

/** Compara lo guardado con lo que devolvió la vista previa (ya normalizado por el backend). */
export function cambiosEntre(guardada: ConfiguracionTienda, previa: ConfiguracionTienda): CambioTienda[] {
    const comparaciones: [string, string | number | null, string | number | null][] = [
        ['Nombre', guardada.name, previa.name],
        ['Descripción', guardada.description, previa.description],
        ['Correo de contacto', guardada.contactEmail, previa.contactEmail],
        ['Teléfono de contacto', guardada.contactPhone, previa.contactPhone],
        ['Horarios', guardada.businessHours, previa.businessHours],
        ['Plazo de devolución (días)', guardada.returnWindowDays, previa.returnWindowDays],
        ['Texto de la política', guardada.policyText, previa.policyText],
        ['Métodos de envío', guardada.shippingMethods.enabled.join(', '), previa.shippingMethods.enabled.join(', ')]
    ];
    return comparaciones
        .filter(([, antes, despues]) => (antes ?? '') !== (despues ?? ''))
        .map(([campo, antes, despues]) => ({ campo, antes: mostrar(antes), despues: mostrar(despues) }));
}
