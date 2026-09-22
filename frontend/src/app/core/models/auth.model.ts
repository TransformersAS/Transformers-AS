/** Contratos HTTP de CU-08; no representan un perfil personal completo. */
export type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN' | 'SOPORTE';

export interface CuentaSesion {
  accountId: number;
  email: string;
  roles: Rol[];
  activeRole: Rol | null;
}

export interface CredencialesLogin {
  email: string;
  password: string;
  rememberMe: boolean;
}

export interface CambioRol {
  role: Rol;
}

export interface SesionActiva {
  /** Identificador público de gestión, no la cookie de autenticación. */
  id: string;
  createdAt: string;
  lastAccessedAt: string;
  expiresAt: string | null;
  current: boolean;
}

export interface CambioContrasena {
  currentPassword: string;
  newPassword: string;
}

export interface SolicitudRecuperacion {
  email: string;
}

export interface ConfirmacionRecuperacion {
  token: string;
  newPassword: string;
}

export interface RespuestaRecuperacion {
  message: string;
}

export interface RespuestaCsrf {
  headerName: string;
  token: string;
}

/** Representación local del token, almacenada únicamente en memoria. */
export interface TokenCsrf {
  header: string;
  token: string;
}
