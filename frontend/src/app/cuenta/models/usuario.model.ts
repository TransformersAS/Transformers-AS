/** Perfil del comprador o vendedor autenticado, listo para hidratarse desde /me. */
export interface Usuario { id: string; nombre: string; correo: string; rol: 'comprador' | 'vendedor' | 'admin' | 'soporte'; }
