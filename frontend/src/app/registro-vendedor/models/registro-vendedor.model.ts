/** Contratos de /api/sellers (CU-12). */

/** Condiciones para vender: lo que se lee antes de aceptar y la versión que queda registrada. */
export interface CondicionesVendedor {
  version: string;
  text: string;
}

/** Resultado de registrarse o de habilitar el rol con una cuenta existente. */
export interface ResultadoRegistro {
  storeId: number;
  storeName: string;
  /** Si es true, el rol de vendedor llega cuando se confirma el registro. */
  emailVerificationRequired: boolean;
  /** Si es true, la cuenta ya tiene el rol VENDEDOR. */
  sellerRoleActive: boolean;
  /** La cuenta se creó, pero debe reintentarse el envío del correo. */
  verificationDeliveryFailed: boolean;
}
