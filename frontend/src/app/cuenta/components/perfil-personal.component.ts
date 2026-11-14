import { Component, EventEmitter, Output, inject, OnDestroy, AfterViewInit, ElementRef, HostListener, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonButton, IonInput, IonItem, IonCheckbox } from '@ionic/angular/standalone';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, finalize } from 'rxjs';
import { CuentaService } from '../services/cuenta.service';
import { PerfilPersonal, CambioPerfil, ElegibilidadCierre } from '../models/perfil-personal.model';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector:'app-perfil-personal',standalone:true,
  imports:[CommonModule,FormsModule,IonButton,IonInput,IonItem,IonCheckbox],
  template:`
  <section class="perfil" role="dialog" aria-modal="true" aria-labelledby="titulo-perfil" [attr.aria-busy]="ocupado">
    <header><h2 id="titulo-perfil" tabindex="-1">Mi perfil y preferencias</h2><ion-button fill="clear" [disabled]="ocupado" (click)="cerrar.emit()">Cerrar perfil</ion-button></header>
    <div class="contenido">
      <p *ngIf="error" role="alert">{{error}}</p><p *ngIf="aviso" role="status">{{aviso}}</p><p *ngIf="ocupado" role="status">Procesando…</p>
      <ng-container *ngIf="perfil as p">
        <ng-container *ngIf="!editando && !cierre">
          <dl><dt>Nombre completo</dt><dd>{{p.fullName || 'Sin registrar'}}</dd><dt>Fecha de nacimiento</dt><dd>{{p.birthDate || 'Sin registrar'}}</dd>
            <dt>Correo actual</dt><dd>{{p.email}}</dd><dt>Teléfono</dt><dd>{{p.phone || 'Sin registrar'}}</dd>
            <dt>Promociones</dt><dd>{{p.promotions ? 'Activadas' : 'Desactivadas'}}</dd><dt>Novedades del marketplace</dt><dd>{{p.marketplaceNews ? 'Activadas' : 'Desactivadas'}}</dd></dl>
          <p>Las comunicaciones esenciales de seguridad, pedidos, pagos, reclamaciones y devoluciones permanecen activas.</p>
          <ion-button [disabled]="ocupado" (click)="editar()">Editar perfil y preferencias</ion-button>
          <section *ngIf="p.pendingEmail" aria-label="Cambio de correo pendiente">
            <h3>Verificar nuevo correo</h3><p>Pendiente: {{p.pendingEmail}}. El correo actual sigue siendo {{p.email}} hasta confirmar.</p>
            <p>El código vence en 30 minutos. Si está vencido o pediste otro, utiliza el último recibido.</p>
            <form (ngSubmit)="confirmarCorreo()"><ion-item><ion-input label="Código de cambio de correo" labelPlacement="stacked" name="codigoCorreo" type="password" autocomplete="off" [(ngModel)]="codigo" [disabled]="ocupado"></ion-input></ion-item>
              <ion-button type="submit" [disabled]="ocupado || !codigo.trim()">Confirmar nuevo correo</ion-button></form>
            <ion-item><ion-input label="Contraseña actual para reenviar" labelPlacement="stacked" type="password" name="claveCorreo" autocomplete="current-password" [(ngModel)]="claveCorreo" [disabled]="ocupado"></ion-input></ion-item>
            <p *ngIf="errores['claveCorreo']" role="alert">{{errores['claveCorreo']}}</p>
            <ion-button [disabled]="ocupado || !claveCorreo" (click)="reenviar()">Reenviar cambio de correo</ion-button>
          </section>
          <hr><ion-button color="danger" fill="outline" [disabled]="ocupado" (click)="consultarCierre()">Solicitar cierre de cuenta</ion-button>
        </ng-container>
        <form *ngIf="editando" (ngSubmit)="revisando ? guardar() : revisar()" novalidate>
          <h3>{{revisando ? 'Revisa y confirma los cambios' : 'Editar datos personales'}}</h3>
          <ng-container *ngFor="let campo of campos">
            <ion-item><ion-input [label]="campo.label" labelPlacement="stacked" [name]="campo.key" [attr.name]="campo.key" [type]="campo.type"
              [(ngModel)]="form[campo.key]" (ngModelChange)="corregir(campo.key)" (ionBlur)="tocados[campo.key]=true" [readonly]="revisando" [disabled]="ocupado"
              [attr.aria-describedby]="'error-perfil-'+campo.key"></ion-input></ion-item>
            <p *ngIf="(intentado || tocados[campo.key]) && errorCampo(campo.key)" [id]="'error-perfil-'+campo.key" role="alert">{{errorCampo(campo.key)}}</p>
          </ng-container>
          <ion-checkbox name="promotions" [(ngModel)]="form.promotions" [disabled]="ocupado || revisando">Recibir promociones</ion-checkbox>
          <ion-checkbox name="marketplaceNews" [(ngModel)]="form.marketplaceNews" [disabled]="ocupado || revisando">Recibir novedades del marketplace</ion-checkbox>
          <p>Las comunicaciones esenciales permanecen activas y no se pueden deshabilitar.</p>
          <ng-container *ngIf="revisando">
            <p *ngIf="form.email.trim().toLowerCase() !== p.email">El nuevo correo no sustituirá al actual hasta verificarlo. Tras confirmar el código deberás iniciar sesión nuevamente.</p>
            <ion-item *ngIf="datosPersonalesCambian"><ion-input label="Contraseña actual para confirmar cambios" labelPlacement="stacked" name="currentPassword" type="password" autocomplete="current-password"
              [(ngModel)]="form.currentPassword" (ngModelChange)="corregir('currentPassword')" [disabled]="ocupado"></ion-input></ion-item>
            <p *ngIf="errores['currentPassword']" role="alert">{{errores['currentPassword']}}</p>
          </ng-container>
          <ion-button type="submit" [disabled]="ocupado">{{revisando ? 'Confirmar y guardar' : 'Revisar cambios'}}</ion-button>
          <ion-button *ngIf="revisando" fill="clear" [disabled]="ocupado" (click)="revisando=false">Volver a editar</ion-button>
          <ion-button fill="clear" [disabled]="ocupado" (click)="cancelarEdicion()">Cancelar edición</ion-button>
        </form>
        <section *ngIf="cierre" aria-label="Cierre de cuenta">
          <h3>Cierre de cuenta</h3><p>{{cierre.consequences}}</p>
          <ul><li *ngFor="let b of cierre.blockers">{{b.message}}: {{b.count}}</li></ul>
          <p *ngIf="!cierre.allowed">No puedes cerrar la cuenta hasta finalizar estos procesos.</p>
          <ion-button fill="outline" [disabled]="ocupado" (click)="consultarCierre()">Actualizar impedimentos</ion-button>
          <ng-container *ngIf="cierre.allowed">
            <ion-item><ion-input label="Contraseña actual para cerrar cuenta" labelPlacement="stacked" name="claveCierre" type="password" autocomplete="current-password" [(ngModel)]="claveCierre" [disabled]="ocupado"></ion-input></ion-item>
            <p *ngIf="errores['claveCierre']" role="alert">{{errores['claveCierre']}}</p>
            <ion-checkbox name="confirmClosure" [(ngModel)]="confirmarCierre" [disabled]="ocupado">Comprendo las consecuencias y confirmo el cierre</ion-checkbox>
            <ion-button color="danger" [disabled]="ocupado || !confirmarCierre || !claveCierre" (click)="cerrarCuenta()">Desactivar mi cuenta</ion-button>
          </ng-container>
          <ion-button fill="clear" [disabled]="ocupado" (click)="cierre=null;claveCierre='';confirmarCierre=false">Volver al perfil</ion-button>
        </section>
      </ng-container>
      <ion-button *ngIf="!perfil && !ocupado" (click)="cargar()">Reintentar consulta</ion-button>
    </div>
  </section>`,
  styles:[`:host{display:flex;height:100%}.perfil{display:flex;flex-direction:column;width:100%;background:var(--ion-background-color,#fff)}header{display:flex;justify-content:space-between;align-items:center;padding:0 1rem;border-bottom:1px solid #ddd}.contenido{overflow:auto;padding:1rem;flex:1}form,dl,section{max-width:48rem}ion-checkbox{display:block;margin:1rem 0}[role=alert]{color:var(--ion-color-danger,#b42318)}dt{font-weight:600;margin-top:.6rem}dd{margin-left:0}`]
})
export class PerfilPersonalComponent implements OnDestroy, AfterViewInit {
  private readonly elemento=inject<ElementRef<HTMLElement>>(ElementRef);
  private focoAnterior:HTMLElement|null=null;
  private readonly cuenta=inject(CuentaService);
  private readonly auth=inject(AuthService);
  @Output() cerrar=new EventEmitter<void>();
  @Output() finalizado=new EventEmitter<'correo'|'cierre'>();
  perfil:PerfilPersonal|null=null;cierre:ElegibilidadCierre|null=null;
  form:CambioPerfil={fullName:'',birthDate:'',email:'',phone:'',promotions:false,marketplaceNews:false,currentPassword:'',version:0,confirmChanges:false};
  campos:{key:'fullName'|'birthDate'|'email'|'phone';label:string;type:'text'|'date'|'email'|'tel'}[]=[
    {key:'fullName',label:'Nombre completo',type:'text'},{key:'birthDate',label:'Fecha de nacimiento',type:'date'},
    {key:'email',label:'Correo electrónico',type:'email'},{key:'phone',label:'Teléfono',type:'tel'}];
  errores:Record<string,string>={};tocados:Record<string,boolean>={};
  editando=false;revisando=false;intentado=false;ocupado=false;error='';aviso='';codigo='';claveCorreo='';claveCierre='';confirmarCierre=false;
  private suscripciones=new Subscription();private identidad:number|null=null;
  constructor() {effect(()=>{const id=this.auth.cuenta()?.accountId ?? null;if(id!==this.identidad){this.suscripciones.unsubscribe();this.suscripciones=new Subscription();this.identidad=id;this.perfil=null;this.editando=false;this.cierre=null;if(id)this.cargar();}},{allowSignalWrites:true});}
  ngAfterViewInit() {
    this.focoAnterior=document.activeElement instanceof HTMLElement?document.activeElement:null;
    this.elemento.nativeElement.querySelector<HTMLElement>('#titulo-perfil')?.focus();
  }
  @HostListener('keydown',['$event']) teclado(event:KeyboardEvent) {
    if(event.key==='Escape' && !this.ocupado){event.preventDefault();this.cerrar.emit();return;}
    if(event.key!=='Tab')return;
    const nodos=Array.from(this.elemento.nativeElement.querySelectorAll<HTMLElement>('button,ion-button,ion-input,ion-checkbox,[tabindex="0"]'))
      .filter(n=>n.offsetParent!==null && !(n as HTMLElement & {disabled?:boolean}).disabled);
    const indice=nodos.findIndex(n=>n===document.activeElement || n.contains(document.activeElement));
    if(!nodos.length){event.preventDefault();return;}
    if(event.shiftKey && indice<=0){event.preventDefault();nodos[nodos.length-1].focus();}
    else if(!event.shiftKey && (indice===nodos.length-1 || indice<0)){event.preventDefault();nodos[0].focus();}
  }
  ngOnDestroy() {this.suscripciones.unsubscribe();this.focoAnterior?.focus();}
  cargar() {this.ejecutar(this.cuenta.consultarPerfilPersonal(),p=>{this.perfil=p;});}
  editar() {if(!this.perfil)return;const p=this.perfil;this.form={fullName:p.fullName??'',birthDate:p.birthDate??'',email:p.email,phone:p.phone??'',promotions:p.promotions,marketplaceNews:p.marketplaceNews,currentPassword:'',version:p.version,confirmChanges:false};this.editando=true;this.revisando=false;this.intentado=false;this.error='';this.aviso='';this.errores={};this.tocados={};}
  cancelarEdicion(){this.editando=false;this.revisando=false;this.form.currentPassword='';this.errores={};}
  get datosPersonalesCambian(){const p=this.perfil;return !!p && (this.form.fullName.trim()!==p.fullName || this.form.birthDate!==p.birthDate || this.form.phone.trim()!==p.phone || this.form.email.trim().toLowerCase()!==p.email);}
  errorCampo(c:string):string {
    if(this.errores[c])return this.errores[c];
    switch(c){
      case 'fullName':return !this.form.fullName.trim()?'Escribe tu nombre completo.':this.form.fullName.length>150?'El nombre admite hasta 150 caracteres.':'';
      case 'birthDate':{const d=new Date();const hoy=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;return !this.form.birthDate?'Indica tu fecha de nacimiento.':this.form.birthDate>hoy?'La fecha no puede ser futura.':'';}
      case 'email':return !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.form.email.trim()) || this.form.email.trim().length>254?'Escribe un correo válido de hasta 254 caracteres.':'';
      case 'phone':return !/^[+]?[0-9][0-9 ()-]{5,30}[0-9]$/.test(this.form.phone)?'Escribe un teléfono válido.':'';
      default:return '';
    }
  }
  corregir(c:string){delete this.errores[c];}
  revisar(){this.intentado=true;if(this.campos.some(c=>this.errorCampo(c.key)))return;this.revisando=true;}
  guardar(){if(this.datosPersonalesCambian && !this.form.currentPassword){this.errores['currentPassword']='Confirma tu identidad con la contraseña actual.';return;}
    this.ejecutar(this.cuenta.guardarPerfil({...this.form,confirmChanges:true}),r=>{this.perfil=r.profile;this.cancelarEdicion();this.aviso=r.verificationDeliveryFailed?'Los datos se guardaron, pero no se pudo enviar la verificación. El correo anterior sigue vigente; puedes reenviar el código.':r.profile.pendingEmail?'Datos guardados. Verifica el nuevo correo para sustituir el actual.':'Perfil y preferencias actualizados.';});}
  reenviar(){this.ejecutar(this.cuenta.reenviarCambioCorreo(this.claveCorreo),r=>{this.perfil=r.profile;this.claveCorreo='';this.aviso=r.verificationDeliveryFailed?'No se pudo enviar la verificación. El correo actual sigue vigente; vuelve a intentarlo.':'Se envió un nuevo código al correo pendiente.';});}
  confirmarCorreo(){this.ejecutar(this.cuenta.confirmarCambioCorreo(this.codigo.trim()),()=>{this.codigo='';this.finalizado.emit('correo');});}
  consultarCierre(){this.claveCierre='';this.confirmarCierre=false;this.ejecutar(this.cuenta.consultarCierre(),r=>this.cierre=r);}
  cerrarCuenta(){this.ejecutar(this.cuenta.cerrarCuenta(this.claveCierre,this.perfil!.version,this.confirmarCierre),()=>{this.claveCierre='';this.finalizado.emit('cierre');});}
  private ejecutar<T>(operation:Observable<T>,next:(value:T)=>void){if(this.ocupado)return;this.ocupado=true;this.error='';this.errores={};
    this.suscripciones.add(operation.pipe(finalize(()=>this.ocupado=false)).subscribe({next,error:(e:HttpErrorResponse)=>{
      this.error=e.error?.message ?? 'No se pudo completar la operación. Tus cambios sin guardar se conservan.';
      if(e.error?.code==='EMAIL_ALREADY_REGISTERED')this.errores['email']=this.error;
      if(e.error?.code==='CURRENT_PASSWORD_INVALID')this.errores[this.cierre?'claveCierre':this.editando?'currentPassword':'claveCorreo']=this.error;
      if(e.error?.code==='ACCOUNT_HAS_PENDING_PROCESSES' && this.cierre)this.consultarCierreTrasError(e.error.details);
      for(const fragmento of this.error.split(';')){const [field,...message]=fragmento.trim().split(':');if(this.campos.some(c=>c.key===field))this.errores[field]=message.join(':').trim();}
    }}));
  }
  private consultarCierreTrasError(details:ElegibilidadCierre['blockers']){if(this.cierre && Array.isArray(details))this.cierre={...this.cierre,allowed:false,blockers:details};}
}
