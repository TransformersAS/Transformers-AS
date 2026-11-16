/** Contratos de CU-22: /api/admin/accounts, /api/admin/restrictions y /api/account. */

export type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN' | 'SOPORTE';
export type AlcanceRestriccion = 'ACCOUNT' | 'ROLE';
export type EstadoRestriccion = 'ACTIVE' | 'EXPIRED' | 'WITHDRAWN';
export type EstadoInformacion = 'OPEN' | 'ANSWERED' | 'EXPIRED';
export type TipoCambioRestriccion = 'APPLIED' | 'DURATION_CHANGED' | 'MADE_PERMANENT' | 'MADE_TEMPORARY'
  | 'WITHDRAWN' | 'EXPIRED';
export type TipoAntecedente = 'CLAIM' | 'MODERATION' | 'RESTRICTION';

export interface Cuenta {
  id: number;
  email: string;
  active: boolean;
  roles: Rol[];
}

export interface CambioRestriccion {
  type: TipoCambioRestriccion;
  previousEndsAt: string | null;
  newEndsAt: string | null;
  justification: string | null;
  /** Nulo si actuó el sistema o si la vista es de la propia cuenta. */
  actorId: number | null;
  createdAt: string;
}

export interface Restriccion {
  id: number;
  accountId: number;
  scope: AlcanceRestriccion;
  /** Nulo cuando el alcance es ACCOUNT. */
  role: Rol | null;
  reason: string;
  permanent: boolean;
  startsAt: string;
  /** Nulo si es permanente. */
  endsAt: string | null;
  status: EstadoRestriccion;
  appliedBy: number | null;
  closedAt: string | null;
  closedBy: number | null;
  closeReason: string | null;
  changes: CambioRestriccion[];
}

export interface SolicitudInformacion {
  id: number;
  message: string;
  requestedAt: string;
  dueAt: string;
  status: EstadoInformacion;
  response: string | null;
  respondedAt: string | null;
}

export interface Antecedente {
  type: TipoAntecedente;
  referenceId: number;
  decidedAt: string;
  subject: string;
  decision: string;
  detail: string | null;
  amount: number | null;
}

/** Vista de administración: la cuenta, sus antecedentes y todas sus restricciones. */
export interface EvaluacionCuenta {
  account: Cuenta;
  records: Antecedente[];
  recentRecords: number;
  recidivist: boolean;
  activeRestrictions: Restriccion[];
  restrictions: Restriccion[];
  informationRequests: SolicitudInformacion[];
}

/** Vista de la propia persona: sus restricciones vigentes y las solicitudes que puede responder. */
export interface EstadoCuenta {
  activeRestrictions: Restriccion[];
  informationRequests: SolicitudInformacion[];
}
