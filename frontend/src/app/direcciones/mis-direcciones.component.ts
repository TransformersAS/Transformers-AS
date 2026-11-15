import { Component,Input,Output,EventEmitter,inject,effect,OnDestroy,ElementRef,ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription,finalize } from 'rxjs';
import { AuthService } from '../core/services/auth.service';
import { AddressInput,DeliveryAddress,DireccionesService } from './direcciones.service';

@Component({selector:'app-mis-direcciones',standalone:true,imports:[CommonModule,FormsModule],
styles:[`:host{display:block;overflow:auto;min-height:0;height:100%;color:#183037;background:#fffdf6}section{max-width:64rem;margin:auto;padding:1rem}header{display:flex;align-items:center;justify-content:space-between;gap:1rem}article,form{border:1px solid #ddd;border-radius:1rem;padding:1rem;margin:1rem 0}label{display:block;margin:.6rem 0}input{display:block;width:100%;box-sizing:border-box}input[type=radio]{display:inline-block;width:auto}input,button{font:inherit;padding:.65rem;border:1px solid #8997a6;border-radius:.5rem}button{background:#183037;color:white;cursor:pointer;margin:.25rem}button:disabled{opacity:.5;cursor:default}button.primary{background:#f56b4b;border-color:#f56b4b}.compact{padding:0}.address-summary{border:1px solid #ddd;border-radius:1rem;padding:1rem}.address-summary p{margin:.5rem 0}.error,[role=alert]{color:#a21c12}small{display:block}h3{margin:.25rem 0}`],template:`
<section [class.compact]="checkout" [attr.role]="checkout?'region':'dialog'" [attr.aria-modal]="checkout?null:'true'" [attr.aria-label]="checkout?'Direcciones para la compra':'Mis direcciones'">
 <header *ngIf="!checkout"><h2>Mis direcciones</h2><button type="button" (click)="close.emit()">Cerrar direcciones</button></header>
 <p *ngIf="error" role="alert">{{error}}</p><p *ngIf="message" role="status">{{message}}</p>
 <div *ngIf="checkout" class="address-summary">
  <ng-container *ngIf="selectedAddress as row"><strong>{{row.recipientName}}</strong><p>{{row.street}} · {{row.city}}</p><small *ngIf="row.defaultAddress">Predeterminada</small></ng-container>
  <p *ngIf="!selectedAddress">Selecciona o registra una dirección para continuar.</p>
  <button #addressToggle type="button" (click)="addressOpen=!addressOpen" [attr.aria-expanded]="addressOpen" aria-controls="checkout-address-options">{{addressOpen?'Cerrar selector de direcciones':'Elegir o cambiar dirección'}}</button>
 </div>
 <div *ngIf="!checkout||addressOpen" [attr.id]="checkout?'checkout-address-options':null">
 <button type="button" (click)="load()" [disabled]="busy">Actualizar direcciones</button>
 <button type="button" class="primary" (click)="edit(null)" [disabled]="busy">Nueva dirección</button>
 <form *ngIf="form" (ngSubmit)="save()" novalidate aria-label="Formulario de dirección"><h3>{{editing===null?'Registrar dirección':'Editar dirección'}}</h3>
  <div *ngFor="let field of fields"><label>{{field.label}}<input [name]="field.key" [type]="field.key==='phone'?'tel':'text'" [(ngModel)]="form[field.key]" [maxlength]="field.max" [required]="field.key!=='postalCode'" [attr.aria-invalid]="!!errors[field.key]" [disabled]="busy"></label>
   <small class="error" role="alert" *ngIf="errors[field.key]">{{errors[field.key]}}</small></div>
  <button type="submit" class="primary" [disabled]="busy">Guardar dirección</button><button type="button" (click)="form=null;errors={}" [disabled]="busy">Cancelar edición</button>
 </form>
 <p *ngIf="loading" role="status">Cargando direcciones…</p>
 <p *ngIf="!loading&&!rows.length&&!error">No tienes direcciones guardadas. Registra una con tus datos de entrega.</p>
 <label *ngIf="checkout&&rows.length">Buscar dirección<input type="search" name="address-search" [(ngModel)]="search" placeholder="Nombre, dirección o ciudad"></label>
 <p *ngIf="checkout&&rows.length&&!visibleRows.length" role="status">No hay direcciones que coincidan con tu búsqueda.</p>
 <div [style.max-height]="checkout?'18rem':null" [style.overflow-y]="checkout?'auto':null" [attr.tabindex]="checkout?0:null" [attr.aria-label]="checkout?'Direcciones guardadas':null">
 <article *ngFor="let row of visibleRows"><h3>{{row.recipientName}}</h3><strong *ngIf="row.defaultAddress">Predeterminada</strong>
  <p>{{row.street}} · {{row.city}} · {{row.department}}</p><p>Teléfono: {{row.phone}} <span *ngIf="row.postalCode">· Código postal: {{row.postalCode}}</span></p>
  <label *ngIf="checkout"><input type="radio" name="delivery-address" [value]="row.id" [checked]="selectedId===row.id" (change)="selectForCheckout(row)" [disabled]="busy">Usar esta dirección: {{row.street}}</label>
  <button type="button" (click)="edit(row)" [disabled]="busy">Editar dirección de {{row.recipientName}}</button>
  <button type="button" *ngIf="!row.defaultAddress" (click)="makeDefault(row.id)" [disabled]="busy">Usar como predeterminada: {{row.street}}</button>
  <button type="button" (click)="confirm=row.id;error=''" [disabled]="busy">Eliminar dirección de {{row.recipientName}}</button>
  <div *ngIf="confirm===row.id"><p>¿Eliminar esta dirección de tus direcciones guardadas? Los pedidos anteriores conservan sus datos.</p>
   <button type="button" (click)="remove(row.id)" [disabled]="busy">Confirmar eliminación de dirección</button><button type="button" (click)="confirm=null" [disabled]="busy">Conservar dirección</button></div>
 </article>
 </div>
 </div>
</section>`})
export class MisDireccionesComponent implements OnDestroy {
 @Input() checkout=false;@Output() close=new EventEmitter<void>();@Output() selected=new EventEmitter<DeliveryAddress|null>();
 private readonly service=inject(DireccionesService);private readonly auth=inject(AuthService);
 private requests=new Subscription();private readonly changes:Subscription;private ownerKey='';
 rows:DeliveryAddress[]=[];form:AddressInput|null=null;editing:number|null=null;selectedId:number|null=null;confirm:number|null=null;
 busy=false;loading=false;error='';message='';errors:Record<string,string>={};
 addressOpen=false;search='';
 @ViewChild('addressToggle') private addressToggle?:ElementRef<HTMLButtonElement>;
 get selectedAddress(){return this.rows.find(row=>row.id===this.selectedId)??null;}
 get visibleRows(){const term=this.normalize(this.search.trim());return !this.checkout||!term?this.rows:this.rows.filter(row=>this.normalize([row.recipientName,row.street,row.city,row.department].join(' ')).includes(term));}
 private normalize(value:string){return value.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g,'');}
 selectForCheckout(row:DeliveryAddress){this.choose(row);this.addressOpen=false;this.search='';this.addressToggle?.nativeElement.focus();}
 readonly fields:{key:keyof AddressInput;label:string;max:number}[]=[{key:'recipientName',label:'Destinatario',max:255},{key:'street',label:'Dirección',max:255},{key:'city',label:'Ciudad',max:255},{key:'department',label:'Departamento',max:255},{key:'postalCode',label:'Código postal (opcional)',max:50},{key:'phone',label:'Teléfono',max:50}];
 constructor(){this.changes=this.service.changes.subscribe(()=>this.load());effect(()=>{const account=this.auth.cuenta();const key=String(account?.accountId)+':'+account?.activeRole;if(this.ownerKey===key)return;this.ownerKey=key;this.requests.unsubscribe();this.requests=new Subscription();this.rows=[];this.form=null;this.addressOpen=false;this.search='';this.selectedId=null;this.confirm=null;this.errors={};this.error='';this.message='';this.busy=false;this.loading=false;this.selected.emit(null);if(account?.activeRole==='COMPRADOR')this.load();});}
 ngOnDestroy(){this.requests.unsubscribe();this.changes.unsubscribe();}
 load(){if(this.auth.cuenta()?.activeRole!=='COMPRADOR')return;this.loading=true;this.error='';this.requests.add(this.service.list().pipe(finalize(()=>this.loading=false)).subscribe({next:rows=>{this.rows=rows;if(this.checkout&&!rows.length)this.addressOpen=true;const selected=rows.find(r=>r.id===this.selectedId)??rows.find(r=>r.defaultAddress)??rows[0]??null;this.choose(selected);},error:e=>{this.error=this.failure(e);this.choose(null);}}));}
 choose(row:DeliveryAddress|null){this.selectedId=row?.id??null;if(this.checkout)this.selected.emit(row);}
 edit(row:DeliveryAddress|null){this.editing=row?.id??null;this.error='';this.message='';this.errors={};this.form=row?{recipientName:row.recipientName,street:row.street,city:row.city,department:row.department,postalCode:row.postalCode??'',phone:row.phone}:{recipientName:'',street:'',city:'',department:'',postalCode:'',phone:''};}
 save(){if(!this.form||this.busy)return;this.errors={};for(const f of this.fields){const value=this.form[f.key].trim();if(f.key!=='postalCode'&&!value)this.errors[f.key]='Este campo es obligatorio.';else if(value.length>f.max)this.errors[f.key]=`Máximo ${f.max} caracteres.`;}
  const phone=this.form.phone.trim(),digits=phone.replace(/\D/g,'').length;
  if(phone&&(!/^\+?\d[\d ()-]*$/.test(phone)||digits<7||digits>15))this.errors.phone='Ingresa un teléfono de 7 a 15 dígitos.';
  if(this.form.postalCode&&!/^[A-Za-z0-9 -]*$/.test(this.form.postalCode))this.errors.postalCode='Usa letras, números, espacios o guiones.';
  if(Object.keys(this.errors).length)return;this.busy=true;this.error='';this.requests.add(this.service.save(this.editing,this.form).pipe(finalize(()=>this.busy=false)).subscribe({next:row=>{this.selectedId=row.id;this.form=null;this.addressOpen=false;this.search='';this.message='Dirección guardada.';this.service.changes.next();},error:e=>{this.error=this.failure(e);const msg=e.error?.message??'';for(const f of this.fields){const part=msg.split('; ').find((s:string)=>s.startsWith(f.key+':'));if(part)this.errors[f.key]=part.slice(part.indexOf(':')+1).trim();}}}));}
 makeDefault(id:number){this.busy=true;this.error='';this.requests.add(this.service.defaultAddress(id).pipe(finalize(()=>this.busy=false)).subscribe({next:()=>{this.message='Dirección predeterminada actualizada.';this.service.changes.next();},error:e=>this.error=this.failure(e)}));}
 remove(id:number){this.busy=true;this.error='';this.requests.add(this.service.remove(id).pipe(finalize(()=>this.busy=false)).subscribe({next:()=>{this.confirm=null;this.message='Dirección eliminada. Los pedidos anteriores conservan su información.';this.service.changes.next();},error:e=>this.error=this.failure(e)}));}
 private failure(e:any){return e.error?.message||e.error?.detail||'No se pudo completar la operación. Tus datos se conservan; puedes reintentar.';}
}
