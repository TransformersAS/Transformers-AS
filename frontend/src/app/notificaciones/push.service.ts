import { Injectable, inject, signal, computed, effect } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, Subject } from 'rxjs';
import { AuthService } from '../core/services/auth.service';
import { API_BASE } from '../core/config/api.config';

@Injectable({providedIn:'root'})
export class PushService {
  private readonly sw=inject(SwPush);
  private readonly http=inject(HttpClient);
  private readonly auth=inject(AuthService);
  private key=''; private revision=0;
  readonly disponible=signal(false); readonly activo=signal(false); readonly ocupado=signal(false);
  readonly mensaje=signal(''); readonly recibida=new Subject<void>();
  readonly compatible=computed(()=>this.sw.isEnabled && typeof Notification!=='undefined' && 'PushManager' in window);
  constructor() {
    this.sw.messages.subscribe(()=>this.recibida.next());
    effect(onCleanup=>{
      const cuenta=this.auth.cuenta();const revision=++this.revision;
      this.disponible.set(false);this.activo.set(false);this.mensaje.set('');this.ocupado.set(false);
      if(!cuenta || !['COMPRADOR','VENDEDOR'].includes(cuenta.activeRole || '') || !this.compatible()) return;
      const request=this.http.get<{enabled:boolean;publicKey:string}>(`${API_BASE}/push/config`).subscribe({
        next: config=>{if(revision!==this.revision || config.enabled!==true) return;
          this.key=config.publicKey;this.disponible.set(true);void this.comprobar(revision);},
        error:()=>this.mensaje.set('Las notificaciones del navegador no están disponibles en este momento.')
      });
      onCleanup(()=>{++this.revision;request.unsubscribe();});
    },{allowSignalWrites:true});
  }
  private async comprobar(revision:number) {
    try {
      const subscription=await firstValueFrom(this.sw.subscription);
      if(!subscription || revision!==this.revision) return;
      const status=await firstValueFrom(this.http.post<{registered:boolean}>(`${API_BASE}/push/status`,{endpoint:subscription.endpoint}));
      if(revision!==this.revision) return;
      this.activo.set(status.registered);
      // Remove a registration left in another role of this account. Session ownership is checked server-side.
      if(!status.registered) await firstValueFrom(this.http.delete(`${API_BASE}/push/subscriptions`,{body:{endpoint:subscription.endpoint}}));
    } catch { /* Permission can remain granted even when there is no subscription. */ }
  }
  async activar() {
    if(this.ocupado() || !this.disponible()) return;
    const revision=this.revision;this.ocupado.set(true);this.mensaje.set('');
    let subscription:PushSubscription|null=null;
    try {
      if(await Notification.requestPermission()!=='granted') {this.mensaje.set('No autorizaste las notificaciones. Puedes cambiar el permiso en la configuración del navegador.');return;}
      if(revision!==this.revision) return;
      const existing=await firstValueFrom(this.sw.subscription);
      if(existing) {await firstValueFrom(this.http.delete(`${API_BASE}/push/subscriptions`,{body:{endpoint:existing.endpoint}}));await this.sw.unsubscribe();}
      subscription=await this.sw.requestSubscription({serverPublicKey:this.key});
      if(revision!==this.revision) {await subscription.unsubscribe();return;}
      const data=subscription.toJSON();
      await firstValueFrom(this.http.post(`${API_BASE}/push/subscriptions`,{endpoint:data.endpoint,keys:data.keys}));
      if(revision!==this.revision) {await subscription.unsubscribe();return;}
      this.activo.set(true);this.mensaje.set('Notificaciones del navegador activadas para esta sesión.');
    } catch {
      if(subscription) await subscription.unsubscribe().catch(()=>false);
      if(revision===this.revision) this.mensaje.set('No se pudieron activar las notificaciones. Reintenta.');
    } finally {if(revision===this.revision) this.ocupado.set(false);}
  }
  async desactivar() {
    if(this.ocupado()) return;const revision=this.revision;this.ocupado.set(true);
    try {
      const subscription=await firstValueFrom(this.sw.subscription);
      if(subscription) await firstValueFrom(this.http.delete(`${API_BASE}/push/subscriptions`,{body:{endpoint:subscription.endpoint}}));
      if(subscription) await this.sw.unsubscribe();
      if(revision===this.revision) {this.activo.set(false);this.mensaje.set('Notificaciones del navegador desactivadas.');}
    } catch {if(revision===this.revision) this.mensaje.set('No se pudo desactivar. Reintenta.');}
    finally {if(revision===this.revision) this.ocupado.set(false);}
  }
}
