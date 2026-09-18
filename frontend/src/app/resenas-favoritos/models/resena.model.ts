/** Opinión verificable de producto que la API podrá paginar y moderar. */
export interface Resena { id: string; productoId: string; puntuacion: 1 | 2 | 3 | 4 | 5; comentario: string; creadaEn: string; }
