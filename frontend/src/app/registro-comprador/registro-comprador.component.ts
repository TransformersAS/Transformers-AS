import { Component, EventEmitter, Output, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, finalize, switchMap, map } from 'rxjs';
import { IonButton, IonInput, IonCheckbox, IonItem } from '@ionic/angular/standalone';
import { AuthService } from '../core/services/auth.service';
import { BuyerForm, BuyerNotices, RegistroCompradorService } from './registro-comprador.service';
@Component({
  selector: 'app-registro-comprador', standalone: true,
  imports: [CommonModule, FormsModule, IonButton, IonInput, IonCheckbox, IonItem],
  template: `
    <header class="heading"><h2 id="titulo-registro-comprador">Registro de comprador</h2><ion-button fill="clear" [disabled]="busy" (click)="cerrar.emit()">Cerrar</ion-button></header>
    <section class="body">
      <p *ngIf="error" role="alert" class="error">{{error}}</p>
      <p *ngIf="message" role="status">{{message}}</p>
      <p *ngIf="busy" role="status">Procesando…</p>
      <ng-container *ngIf="step === 'verify'; else registration">
        <h3>Verifica tu correo</h3><p>Tu cuenta permanece pendiente hasta verificar. El código vence en 30 minutos; usa el último recibido.</p>
        <form (ngSubmit)="verify()">
          <ion-item><ion-input label="Código de verificación" labelPlacement="stacked" name="token" type="password" autocomplete="off" [(ngModel)]="token" [disabled]="busy"></ion-input></ion-item>
          <ion-button type="submit" [disabled]="busy || !token.trim()">Confirmar correo</ion-button>
        </form>
        <ion-item><ion-input label="Correo para reenviar" labelPlacement="stacked" type="email" [(ngModel)]="form.email" [disabled]="busy"></ion-input></ion-item>
        <ion-item><ion-input label="Contraseña para reenviar" labelPlacement="stacked" type="password" autocomplete="current-password" [(ngModel)]="resendPassword" [disabled]="busy"></ion-input></ion-item>
        <ion-button [disabled]="busy || !resendPassword || !form.email" (click)="resend()">Reenviar verificación</ion-button>
        <ion-button fill="clear" [disabled]="busy" (click)="login()">Ir al inicio de sesión</ion-button>
      </ng-container>
      <ng-template #registration>
        <ng-container *ngIf="auth.autenticada(); else newAccount">
          <p>Cuenta: {{auth.cuenta()?.email}}</p>
          <ng-container *ngIf="hasBuyer; else enableBuyer">
            <p>Tu cuenta ya tiene COMPRADOR; no necesitas registrarte nuevamente. Puedes seleccionar el rol activo desde tu cuenta.</p>
            <ion-button (click)="login()">Volver a mi cuenta</ion-button>
          </ng-container>
          <ng-template #enableBuyer>
            <p>Habilita COMPRADOR conservando tu cuenta y tu rol activo.</p>
            <ng-container *ngTemplateOutlet="legal"></ng-container>
            <ion-checkbox [(ngModel)]="form.acceptTerms" [disabled]="busy">Acepto los términos y condiciones</ion-checkbox>
            <ion-checkbox [(ngModel)]="form.acceptPrivacy" [disabled]="busy">Acepto la política de privacidad</ion-checkbox>
            <ion-button [disabled]="busy || !notices || !form.acceptTerms || !form.acceptPrivacy" (click)="enable()">Habilitar COMPRADOR</ion-button>
          </ng-template>
        </ng-container>
      </ng-template>
      <ng-template #newAccount>
        <p>¿Ya tienes cuenta? Inicia sesión para consultar o habilitar COMPRADOR.</p>
        <ion-button fill="outline" (click)="login()" [disabled]="busy">Ya tengo cuenta</ion-button>
        <form (ngSubmit)="register()" novalidate>
          <ng-container *ngFor="let field of fields">
            <ion-item><ion-input [label]="field.label" labelPlacement="stacked" [type]="field.type" [name]="field.key" [attr.name]="field.key"
              [autocomplete]="field.autocomplete" [(ngModel)]="form[field.key]" (ionBlur)="touched[field.key] = true" [disabled]="busy"
              [class.ion-invalid]="!!fieldError(field.key) && !!touched[field.key]" [class.ion-touched]="!!touched[field.key]"
              [errorText]="touched[field.key] ? fieldError(field.key) : undefined"></ion-input></ion-item>
            <p *ngIf="touched[field.key] && fieldError(field.key)" class="error" role="alert">{{fieldError(field.key)}}</p>
          </ng-container>
          <p>Mínimo 12 caracteres y máximo 72 bytes UTF-8.</p>
          <ng-container *ngTemplateOutlet="legal"></ng-container>
          <ion-checkbox name="terms" [(ngModel)]="form.acceptTerms" [disabled]="busy">Acepto los términos y condiciones</ion-checkbox>
          <p *ngIf="submitted && !form.acceptTerms" class="error" role="alert">Acepta los términos y condiciones.</p>
          <ion-checkbox name="privacy" [(ngModel)]="form.acceptPrivacy" [disabled]="busy">Acepto la política de privacidad</ion-checkbox>
          <p *ngIf="submitted && !form.acceptPrivacy" class="error" role="alert">Acepta la política de privacidad.</p>
          <ion-button type="submit" [disabled]="busy || !notices">Crear cuenta de comprador</ion-button>
        </form>
        <ion-button fill="clear" [disabled]="busy" (click)="step = 'verify'; error = ''; message = ''">Ya tengo un código o necesito reenviarlo</ion-button>
      </ng-template>
      <ng-template #legal>
        <ng-container *ngIf="notices"><h3>Términos ({{notices.termsVersion}})</h3><p>{{notices.terms}}</p>
          <h3>Privacidad ({{notices.privacyVersion}})</h3><p>{{notices.privacy}}</p></ng-container>
      </ng-template>
    </section>`,
  styles: [`:host {display:flex; flex-direction:column; height:100%;} .heading{display:flex; align-items:center; justify-content:space-between; padding:0 1rem; border-bottom:1px solid #ddd}.body{flex:1; overflow:auto; padding:1rem} ion-checkbox{display:block; margin:1rem 0} .error{color:var(--ion-color-danger, #b42318)} form{max-width:40rem} p{line-height:1.5}`]
})
export class RegistroCompradorComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(RegistroCompradorService);
  @Output() cerrar = new EventEmitter<void>();
  @Output() acceso = new EventEmitter<void>();
  form: BuyerForm = {fullName:'', birthDate:'', email:'', phone:'', password:'', passwordConfirmation:'', acceptTerms:false, acceptPrivacy:false};
  fields: {key: 'fullName'|'birthDate'|'email'|'phone'|'password'|'passwordConfirmation'; label:string; type:'text'|'date'|'email'|'tel'|'password'; autocomplete:string}[] = [
    {key:'fullName', label:'Nombre completo', type:'text', autocomplete:'name'},
    {key:'birthDate', label:'Fecha de nacimiento', type:'date', autocomplete:'bday'},
    {key:'email', label:'Correo electrónico', type:'email', autocomplete:'email'},
    {key:'phone', label:'Teléfono', type:'tel', autocomplete:'tel'},
    {key:'password', label:'Contraseña', type:'password', autocomplete:'new-password'},
    {key:'passwordConfirmation', label:'Confirmación de contraseña', type:'password', autocomplete:'new-password'}];
  touched: Record<string, boolean> = {};
  notices: BuyerNotices|null = null;
  step: 'form'|'verify' = 'form';
  busy=false; submitted=false; error=''; message=''; token=''; resendPassword='';
  get hasBuyer() { return this.auth.cuenta()?.roles.includes('COMPRADOR') ?? false; }
  ngOnInit() { this.api.terms().subscribe({next:n => this.notices=n, error:() => this.error='No se pudieron cargar los términos. Cierra y vuelve a intentar.'}); }
  fieldError(key: string): string {
    const f=this.form;
    switch(key) {
      case 'fullName': return !f.fullName.trim() ? 'Escribe tu nombre completo.' : f.fullName.length>150 ? 'El nombre admite hasta 150 caracteres.' : '';
      case 'birthDate': return !f.birthDate ? 'Indica tu fecha de nacimiento.' : f.birthDate > this.today() ? 'La fecha no puede ser futura.' : '';
      case 'email': return !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim()) || f.email.length>254 ? 'Escribe un correo válido de hasta 254 caracteres.' : '';
      case 'phone': return !/^[+]?[0-9][0-9 ()-]{5,30}[0-9]$/.test(f.phone) ? 'Escribe un teléfono válido, con dígitos y separadores opcionales.' : '';
      case 'password': return !f.password.trim() || Array.from(f.password).length<12 ? 'La contraseña requiere al menos 12 caracteres.' : new TextEncoder().encode(f.password).length>72 ? 'La contraseña supera 72 bytes UTF-8.' : '';
      case 'passwordConfirmation': return !f.passwordConfirmation ? 'Confirma tu contraseña.' : f.password!==f.passwordConfirmation ? 'Las contraseñas no coinciden.' : '';
      default: return '';
    }
  }
  private today() { const d=new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
  register() {
    this.submitted=true; this.fields.forEach(f=>this.touched[f.key]=true);
    if(this.busy || this.fields.some(f=>this.fieldError(f.key)) || !this.form.acceptTerms || !this.form.acceptPrivacy) return;
    this.run(this.api.register(this.form), result=> {
      this.step='verify'; this.form.password=this.form.passwordConfirmation=''; this.message=result.message;
      if(result.verificationDeliveryFailed) this.error='El registro se conservó pendiente. Solicita un reenvío.';
    });
  }
  enable() { this.run(this.api.enable(this.form.acceptTerms,this.form.acceptPrivacy).pipe(switchMap(result=>this.auth.obtenerCuentaActual().pipe(map(()=>result.message)))), message=>this.message=message); }
  verify() { this.run(this.auth.confirmarCorreo(this.token.trim()), ()=>{this.token=this.resendPassword=''; this.login();}); }
  resend() { this.run(this.auth.reenviarVerificacion(this.form.email.trim(),this.resendPassword), ()=>{this.resendPassword='';this.message='Se envió una verificación a tu correo electrónico. Usa el último código.';}); }
  login() { this.cerrar.emit(); this.acceso.emit(); }
  private run<T>(operation: Observable<T>, success:(result:T)=>void) {
    if(this.busy) return; this.busy=true;this.error='';this.message='';
    operation.pipe(finalize(()=>this.busy=false)).subscribe({next:success,error:(e:HttpErrorResponse)=>{
      this.error=e.error?.message ?? 'No se pudo completar la operación. Inténtalo nuevamente.';
      if(this.step==='verify' && e.status===400) this.error+=' Solicita una nueva verificación si el código venció o ya fue utilizado.';
    }});
  }
}
