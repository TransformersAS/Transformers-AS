/** Funciones puras de la analítica (CU-26): formato, fechas, validación del periodo y geometría de las gráficas. */

const PESOS = new Intl.NumberFormat('es-CO', {
  style: 'currency', currency: 'COP', minimumFractionDigits: 0, maximumFractionDigits: 2
});
const ENTERO = new Intl.NumberFormat('es-CO', { maximumFractionDigits: 2 });

/** Máximo de días de un periodo; el backend aplica el mismo límite. */
export const MAX_DIAS = 731;

export function moneda(valor: number): string {
  return PESOS.format(valor);
}

export function numero(valor: number): string {
  return ENTERO.format(valor);
}

/** yyyy-MM-dd a dd/MM/yyyy, sin pasar por la zona horaria del navegador. */
export function fechaCorta(iso: string): string {
  const [anio, mes, dia] = iso.slice(0, 10).split('-');
  return `${dia}/${mes}/${anio}`;
}

/** Fecha de hoy en el calendario local, como yyyy-MM-dd. */
export function hoy(fecha: Date = new Date()): string {
  return aIso(fecha);
}

export function sumarDias(iso: string, dias: number): string {
  const [anio, mes, dia] = iso.split('-').map(Number);
  return aIso(new Date(anio, mes - 1, dia + dias));
}

export function diasEntre(desde: string, hasta: string): number {
  const [a1, m1, d1] = desde.split('-').map(Number);
  const [a2, m2, d2] = hasta.split('-').map(Number);
  return Math.round((Date.UTC(a2, m2 - 1, d2) - Date.UTC(a1, m1 - 1, d1)) / 86_400_000);
}

/** El periodo de la misma longitud que termina justo antes del indicado: la comparación más habitual. */
export function periodoAnterior(desde: string, hasta: string): { desde: string; hasta: string } {
  const largo = diasEntre(desde, hasta) + 1;
  return { desde: sumarDias(desde, -largo), hasta: sumarDias(desde, -1) };
}

/**
 * Errores de un periodo por campo, con el mismo criterio que el backend (A2), para avisar junto al campo antes de
 * consultar. Devuelve un objeto vacío si el periodo es válido.
 */
export function validarPeriodo(desde: string, hasta: string, hoyIso: string): Record<'desde' | 'hasta', string> {
  const errores = {} as Record<'desde' | 'hasta', string>;
  if (!desde) {
    errores.desde = 'Indica la fecha inicial.';
  }
  if (!hasta) {
    errores.hasta = 'Indica la fecha final.';
  }
  if (!desde || !hasta) {
    return errores;
  }
  if (desde > hasta) {
    errores.desde = 'La fecha inicial no puede ser posterior a la final.';
  } else if (desde > hoyIso) {
    errores.desde = 'El periodo no puede empezar después de hoy.';
  } else if (diasEntre(desde, hasta) + 1 > MAX_DIAS) {
    errores.hasta = `El periodo admite como máximo ${MAX_DIAS} días.`;
  }
  return errores;
}

/** Variación para mostrar: "+12,5 %", "−3 %" o el aviso de que no se puede calcular (A5). */
export function variacion(porcentaje: number | null): string {
  if (porcentaje === null) {
    return 'No calculable (base en cero)';
  }
  const signo = porcentaje > 0 ? '+' : porcentaje < 0 ? '−' : '';
  return `${signo}${numero(Math.abs(porcentaje))} %`;
}

/**
 * Coordenadas de una línea en un área de ancho × alto con un margen, escalada a {@code maximo}. Con un solo valor la
 * línea se dibuja como un punto en el centro.
 */
export function puntosLinea(valores: number[], ancho: number, alto: number, margen: number, maximo: number): string {
  if (valores.length === 0) {
    return '';
  }
  const util = { x: ancho - 2 * margen, y: alto - 2 * margen };
  const paso = valores.length > 1 ? util.x / (valores.length - 1) : 0;
  return valores.map((valor, indice) => {
    const x = valores.length > 1 ? margen + indice * paso : ancho / 2;
    const y = alto - margen - (maximo > 0 ? Math.max(valor, 0) / maximo : 0) * util.y;
    return `${redondear(x)},${redondear(y)}`;
  }).join(' ');
}

/** Ancho de una barra en porcentaje del mayor valor; un valor positivo nunca queda invisible. */
export function anchoBarra(valor: number, maximo: number): number {
  if (maximo <= 0 || valor <= 0) {
    return 0;
  }
  return Math.max(2, Math.round((valor / maximo) * 100));
}

function aIso(fecha: Date): string {
  const mes = String(fecha.getMonth() + 1).padStart(2, '0');
  const dia = String(fecha.getDate()).padStart(2, '0');
  return `${fecha.getFullYear()}-${mes}-${dia}`;
}

function redondear(valor: number): number {
  return Math.round(valor * 10) / 10;
}
