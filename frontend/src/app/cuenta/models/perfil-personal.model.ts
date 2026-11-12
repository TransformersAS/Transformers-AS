/** CU09: un único perfil para todos los roles de la identidad. */
export interface PerfilPersonal {
  fullName: string|null; birthDate: string|null; email: string; phone: string|null;
  promotions: boolean; marketplaceNews: boolean; essentialCommunications: true; version: number;
  pendingEmail: string|null; pendingEmailExpiresAt: string|null;
}
export interface CambioPerfil {
  fullName:string; birthDate:string; email:string; phone:string; promotions:boolean; marketplaceNews:boolean;
  currentPassword:string; version:number; confirmChanges:boolean;
}
export interface PerfilGuardado { profile:PerfilPersonal; verificationDeliveryFailed:boolean; }
export interface ElegibilidadCierre { allowed:boolean; blockers:{code:string;message:string;count:number}[]; consequences:string; }
