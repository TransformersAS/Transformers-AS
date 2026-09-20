/**
 * Contratos HTTP de "Mi tienda" (CU-18), tal como los define el backend en /api/seller/store. Los nombres de los
 * campos son los del JSON; no se traducen para no desalinearse del contrato.
 */

export type EstadoTienda = 'ACTIVE' | 'RESTRICTED' | 'SUSPENDED';

export type TipoImagen = 'LOGO' | 'PORTADA';

/** Metadatos de una imagen. `url` ya lleva la versión (?v=sha256): cambia solo cuando cambia la imagen. */
export interface ImagenTienda {
    kind: TipoImagen;
    contentType: string;
    sizeBytes: number;
    sha256: string;
    url: string;
}

export interface MetodosEnvioTienda {
    /** Los que la tienda ofrece hoy. */
    enabled: string[];
    /** Los que el marketplace permite habilitar. */
    available: string[];
}

/** Respuesta de GET, POST /preview y PUT. `version` es la que debe devolverse al guardar (A10). */
export interface ConfiguracionTienda {
    id: number;
    name: string;
    description: string | null;
    contactEmail: string | null;
    contactPhone: string | null;
    businessHours: string | null;
    returnWindowDays: number;
    /** Plazo de devolución mínimo que exige el marketplace (A5). */
    minReturnWindowDays: number;
    policyText: string | null;
    status: EstadoTienda;
    statusReason: string | null;
    /** Falso si la tienda está restringida o suspendida (A8). */
    canModify: boolean;
    version: number;
    shippingMethods: MetodosEnvioTienda;
    images: ImagenTienda[];
}

/** Cuerpo de POST /preview y de PUT. Reemplaza el perfil, la política y los métodos completos. */
export interface SolicitudTienda {
    name: string;
    description: string | null;
    contactEmail: string | null;
    contactPhone: string | null;
    businessHours: string | null;
    returnWindowDays: number;
    policyText: string | null;
    shippingMethods: string[];
}

/** Detalles que el backend añade a algunos errores. */
export interface DetallesErrorTienda {
    minReturnWindowDays?: number;
    unavailable?: string[];
    available?: string[];
    status?: EstadoTienda;
    reason?: string;
}

/** Error del backend: {status, error, message, path} más `code` y, a veces, `details`. */
export interface ErrorTienda {
    status?: number;
    message?: string;
    code?: string;
    details?: DetallesErrorTienda;
}

/** Límites que el backend aplica; en el cliente solo sirven de ayuda para no enviar datos que se rechazarán. */
export const LIMITES_TIENDA = {
    nombre: 100,
    descripcion: 1000,
    correo: 254,
    telefono: 50,
    horarios: 500,
    politica: 2000,
    plazoMaximo: 365
} as const;

/** Formatos y tamaño de imagen que acepta el backend. */
export const IMAGEN_TIENDA = {
    tiposPermitidos: ['image/jpeg', 'image/png'],
    extensionesPermitidas: ['.jpg', '.jpeg', '.png'],
    tamanoMaximoBytes: 5 * 1024 * 1024
} as const;

export const ETIQUETA_ESTADO_TIENDA: Record<EstadoTienda, string> = {
    ACTIVE: 'Activa',
    RESTRICTED: 'Restringida',
    SUSPENDED: 'Suspendida'
};

export const ETIQUETA_TIPO_IMAGEN: Record<TipoImagen, string> = {
    LOGO: 'Logo',
    PORTADA: 'Portada'
};

const ETIQUETA_METODO_ENVIO: Record<string, string> = {
    STANDARD: 'Envío estándar',
    EXPRESS: 'Envío exprés'
};

/** Nombre legible de un método de envío; uno que el cliente no conozca se muestra tal como llega. */
export function etiquetaMetodoEnvio(metodo: string): string {
    return ETIQUETA_METODO_ENVIO[metodo] ?? metodo;
}

/** Lo que ven los compradores de la tienda; lo usa la tarjeta de vista previa, que no conoce el contrato HTTP. */
export interface VistaTienda {
    nombre: string;
    descripcion: string | null;
    correo: string | null;
    telefono: string | null;
    horarios: string | null;
    plazoDevolucion: number;
    politica: string | null;
    metodosEnvio: string[];
    logoUrl: string | null;
    portadaUrl: string | null;
}

export function urlImagen(imagenes: ImagenTienda[], tipo: TipoImagen): string | null {
    return imagenes.find(imagen => imagen.kind === tipo)?.url ?? null;
}

export function vistaDeConfiguracion(configuracion: ConfiguracionTienda): VistaTienda {
    return {
        nombre: configuracion.name,
        descripcion: configuracion.description,
        correo: configuracion.contactEmail,
        telefono: configuracion.contactPhone,
        horarios: configuracion.businessHours,
        plazoDevolucion: configuracion.returnWindowDays,
        politica: configuracion.policyText,
        metodosEnvio: configuracion.shippingMethods.enabled,
        logoUrl: urlImagen(configuracion.images, 'LOGO'),
        portadaUrl: urlImagen(configuracion.images, 'PORTADA')
    };
}
