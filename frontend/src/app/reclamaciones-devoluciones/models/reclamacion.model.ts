/** Contratos de las reclamaciones de compra (CU-13): /api/claims, /api/seller/claims y /api/support/claims. */

export type EstadoReclamacion = 'OPEN' | 'INFO_REQUESTED' | 'SOLUTION_PROPOSED' | 'ESCALATED' | 'RESOLVED';

export type ResolucionReclamacion = 'SOLUTION_ACCEPTED' | 'REFUND_GRANTED' | 'REJECTED';

/** Quién escribió cada entrada del hilo y de qué tipo es. */
export interface MensajeReclamacion {
  author: 'BUYER' | 'SELLER' | 'SUPPORT';
  kind: 'MESSAGE' | 'INFO_REQUEST' | 'PROPOSAL' | 'ESCALATION' | 'DECISION';
  message: string;
  createdAt: string;
}

/** Reclamación con su hilo, igual para el comprador, el vendedor y soporte. */
export interface Reclamacion {
  id: number;
  orderId: number;
  productId: number;
  productName: string;
  /** Lo pagado por ese producto: tope de cualquier reembolso. */
  itemTotal: number;
  storeId: number;
  description: string;
  status: EstadoReclamacion;
  evidenceUrls: string[];
  proposalText: string | null;
  proposedRefund: number | null;
  resolution: ResolucionReclamacion | null;
  resolutionNote: string | null;
  refundAmount: number | null;
  refundStatus: string | null;
  createdAt: string;
  updatedAt: string;
  messages: MensajeReclamacion[];
}

/** Datos para abrir una reclamación. */
export interface NuevaReclamacion {
  orderId: number;
  productId: number;
  description: string;
  evidenceUrls: string[];
}

/** Decisión de soporte sobre una reclamación escalada. */
export type DecisionSoporte = 'REFUND_GRANTED' | 'REJECTED';
