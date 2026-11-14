import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../core/config/api.config';
export interface BuyerForm { fullName: string; birthDate: string; email: string; phone: string; password: string; passwordConfirmation: string; acceptTerms: boolean; acceptPrivacy: boolean; }
export interface BuyerNotices { termsVersion: string; terms: string; privacyVersion: string; privacy: string; }
@Injectable({ providedIn: 'root' })
export class RegistroCompradorService {
  private readonly http = inject(HttpClient);
  terms() { return this.http.get<BuyerNotices>(`${API_BASE}/buyers/terms`); }
  register(form: BuyerForm) { return this.http.post<{emailVerificationRequired: boolean; verificationDeliveryFailed: boolean; message: string}>(`${API_BASE}/buyers/register`, form); }
  enable(acceptTerms: boolean, acceptPrivacy: boolean) { return this.http.post<{buyerRoleAdded: boolean; message: string}>(`${API_BASE}/buyers/enable`, {acceptTerms, acceptPrivacy}); }
}
