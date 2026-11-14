/** Contratos de CU-27: /api/admin/store-evaluations, /api/admin/store-suspensions y /api/seller/store/standing. */

export type EstadoTienda = 'ACTIVE' | 'SUSPENDED' | 'RESTRICTED';
export type EstadoSuspension = 'ACTIVE' | 'EXPIRED' | 'LIFTED';
export type EstadoAclaracion = 'OPEN' | 'ANSWERED' | 'EXPIRED';

export interface CambioSuspension {
  type: 'APPLIED' | 'DURATION_CHANGED' | 'LIFTED' | 'EXPIRED';
  previousEndsAt: string | null;
  newEndsAt: string | null;
  justification: string | null;
  /** Nulo si actuó el sistema o si la vista es del vendedor. */
  actorId: number | null;
  reactivationBlocked: boolean;
  createdAt: string;
}

export interface Suspension {
  id: number;
  storeId: number;
  reason: string;
  startsAt: string;
  endsAt: string;
  status: EstadoSuspension;
  appliedBy: number | null;
  closedAt: string | null;
  closedBy: number | null;
  closeReason: string | null;
  changes: CambioSuspension[];
}

export interface Aclaracion {
  id: number;
  message: string;
  requestedAt: string;
  dueAt: string;
  status: EstadoAclaracion;
  response: string | null;
  respondedAt: string | null;
}

export interface OperacionesEnCurso {
  ordersInProgress: number;
  openClaims: number;
  openReturns: number;
}

/** Vista del vendedor: el estado de su tienda y lo que le piden. */
export interface EstadoMiTienda {
  status: EstadoTienda;
  statusReason: string | null;
  activeSuspension: Suspension | null;
  suspensions: Suspension[];
  clarifications: Aclaracion[];
  blocked: string[];
  allowed: string[];
}

export interface TiendaConAntecedentes {
  storeId: number;
  storeName: string;
  storeStatus: EstadoTienda;
  claims: number;
  moderations: number;
  suspensions: number;
  recentRecords: number;
  lastRecordAt: string | null;
  recidivist: boolean;
  activeSuspension: boolean;
  openClarification: boolean;
}

export interface Antecedente {
  type: 'CLAIM' | 'MODERATION';
  referenceId: number;
  decidedAt: string;
  subject: string;
  decision: 'REFUND_GRANTED' | 'OCULTAR_TEMPORALMENTE' | 'RETIRAR';
  detail: string | null;
  amount: number | null;
}

export interface Revision {
  id: number;
  decision: 'NO_ACTION' | 'SUSPENDED';
  note: string;
  suspensionId: number | null;
  reviewedBy: number;
  createdAt: string;
}

export interface EvaluacionTienda {
  storeId: number;
  storeName: string;
  storeStatus: EstadoTienda;
  statusReason: string | null;
  pending: OperacionesEnCurso;
  records: Antecedente[];
  recentRecords: number;
  recidivist: boolean;
  activeSuspension: Suspension | null;
  suspensions: Suspension[];
  reviews: Revision[];
  clarifications: Aclaracion[];
}

export interface ResumenSuspension {
  storeId: number;
  storeName: string;
  reason: string;
  durationDays: number;
  startsAt: string;
  endsAt: string;
  blocked: string[];
  allowed: string[];
  pending: OperacionesEnCurso;
  warning: string | null;
}

export interface ResultadoFinalizacion {
  suspensionId: number;
  status: EstadoSuspension;
  /** Falso si otra restricción impidió reactivar la tienda (A6). */
  reactivated: boolean;
}
