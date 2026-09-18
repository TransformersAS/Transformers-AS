/** Caso de posventa que puede derivar en devolución, reembolso o soporte humano. */
export interface Reclamacion { id: string; pedidoId: string; motivo: string; estado: 'abierta' | 'en-revision' | 'resuelta'; }
