import { Component,Input,Output,EventEmitter,inject,effect,OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';import { FormsModule } from '@angular/forms';import { Subscription,forkJoin,of,finalize } from 'rxjs';
import { ResenasService,Review,Rating } from '../services/resenas.service';import { AuthService } from '../../core/services/auth.service';
import { EditorResenaComponent } from './editor-resena.component';import { ReportarContenidoComponent } from '../../reportes/components/reportar-contenido.component';
@Component({selector:'app-mis-resenas',standalone:true,imports:[CommonModule,FormsModule,EditorResenaComponent,ReportarContenidoComponent],styleUrl:'./resenas.scss',styles:[':host{height:100%;min-height:0;overflow:auto}'],template:`
<section role="dialog" aria-modal="true" [attr.aria-label]="seller?'Reseñas recibidas':'Mis reseñas'">
<header><h2>{{seller?'Reseñas recibidas':'Mis reseñas'}}</h2><button type="button" (click)="cerrar.emit()">Cerrar reseñas</button></header>
<p *ngIf="error" role="alert">{{error}}</p><p *ngIf="mensaje" role="status">{{mensaje}}</p>
<form *ngIf="seller" (ngSubmit)="page=0;cargar()"><label>Producto (ID opcional)<input name="product" type="number" min="1" [(ngModel)]="product"></label>
<label>Calificación<select name="rating" [(ngModel)]="rating"><option value="">Todas</option><option *ngFor="let n of [1,2,3,4,5]" [value]="n">{{n}}</option></select></label>
<label>Respuesta<select name="answered" [(ngModel)]="answered"><option value="">Todas</option><option value="true">Respondidas</option><option value="false">Sin responder</option></select></label>
<button type="submit" [disabled]="busy">Filtrar reseñas</button></form>
<button type="button" (click)="cargar()" [disabled]="busy">Actualizar mis reseñas</button>
<p *ngIf="busy" role="status">Consultando reseñas…</p><p *ngIf="!busy&&!rows.length&&!error">No hay reseñas para mostrar.</p>
<section *ngIf="seller&&summaries.length" aria-label="Valoraciones consolidadas"><h3>Valoraciones de tus productos</h3><article *ngFor="let s of summaries"><strong>Producto #{{s.productId}}</strong><p>{{s.average|number:'1.1-2'}} / 5 · {{s.count}} reseñas vigentes</p><span *ngFor="let count of s.distribution;let i=index">{{i+1}}★: {{count}} · </span></article></section>
<article *ngFor="let r of rows"><h3>{{r.productName}}</h3><p>{{r.rating}} / 5 · Compra verificada · {{r.createdAt|date:'medium'}}</p><p>{{r.comment}}</p>
<div class="fotos"><img *ngFor="let id of r.images" [src]="service.image(id)" loading="lazy" alt="Imagen de reseña"></div>
<div *ngIf="r.reply" class="respuesta"><strong>Respuesta del vendedor</strong><p>{{r.reply.text}}</p><app-reportar-contenido *ngIf="!seller" tipo="RESPUESTA_RESENA" [contenidoId]="r.id"></app-reportar-contenido></div>
<ng-container *ngIf="!seller"><button type="button" (click)="editing=editing===r.id?null:r.id">Modificar mi reseña</button>
<app-editor-resena *ngIf="editing===r.id" [orderId]="r.orderId!" [productId]="r.productId" [nombre]="r.productName" [iniciar]="true" (changed)="changed.emit();cargar()"></app-editor-resena>
<button type="button" (click)="confirmDelete=r.id">Eliminar mi reseña</button><div *ngIf="confirmDelete===r.id"><p>¿Eliminar esta reseña y retirar su calificación del promedio?</p><button type="button" [disabled]="busy" (click)="eliminar(r.id)">Confirmar eliminación de reseña</button><button type="button" (click)="confirmDelete=null">Conservar reseña</button></div></ng-container>
<ng-container *ngIf="seller"><button type="button" (click)="compra(r.id)">Consultar compra asociada</button>
<p *ngIf="purchases[r.id]">{{purchases[r.id]}}</p><app-reportar-contenido tipo="RESENA" [contenidoId]="r.id"></app-reportar-contenido>
<form *ngIf="r.canManageReply" (ngSubmit)="responder(r.id)"><label>Respuesta a {{r.productName}}<textarea [name]="'reply-'+r.id" [(ngModel)]="replies[r.id]" maxlength="2000"></textarea></label>
<small class="error" role="alert" *ngIf="replyErrors[r.id]">{{replyErrors[r.id]}}</small><button type="submit" [disabled]="busy">{{r.reply?'Guardar respuesta':'Publicar respuesta'}}</button>
<button type="button" *ngIf="r.reply" [disabled]="busy" (click)="quitarRespuesta(r.id)">Eliminar mi respuesta</button></form></ng-container></article>
<button type="button" *ngIf="page>0" [disabled]="busy" (click)="page=page-1;cargar()">Página anterior de mis reseñas</button>
<button type="button" *ngIf="rows.length===50" [disabled]="busy" (click)="page=page+1;cargar()">Más reseñas</button>
</section>`})
export class MisResenasComponent implements OnDestroy {
 @Input() seller=false;@Output() cerrar=new EventEmitter<void>();@Output() changed=new EventEmitter<void>();
 readonly service=inject(ResenasService);private auth=inject(AuthService);private requests=new Subscription();
 rows:Review[]=[];summaries:Rating[]=[];error='';mensaje='';busy=false;page=0;product='';rating='';answered='';editing:number|null=null;confirmDelete:number|null=null;
 replies:Record<number,string>={};replyErrors:Record<number,string>={};purchases:Record<number,string>={};
 constructor(){effect(()=>{const role=this.auth.cuenta()?.activeRole;this.requests.unsubscribe();this.requests=new Subscription();this.rows=[];this.summaries=[];this.purchases={};this.replies={};this.editing=null;this.confirmDelete=null;this.error='';this.page=0;if(role===(this.seller?'VENDEDOR':'COMPRADOR'))this.cargar();});}
 ngOnDestroy(){this.requests.unsubscribe();}
 cargar(){this.busy=true;this.error='';this.requests.add(forkJoin({rows:this.seller?this.service.seller(this.product,this.rating,this.answered,this.page):this.service.mine(this.page),summaries:this.seller?this.service.sellerSummary():of([])}).pipe(finalize(()=>this.busy=false)).subscribe({next:r=>{this.rows=r.rows;this.summaries=r.summaries;r.rows.forEach(row=>this.replies[row.id]=row.reply?.text??'');},error:e=>this.error=this.message(e)}));}
 eliminar(id:number){this.busy=true;this.requests.add(this.service.remove(id).pipe(finalize(()=>this.busy=false)).subscribe({next:()=>{this.confirmDelete=null;this.editing=null;this.mensaje='Reseña eliminada. Valoración actualizada.';this.changed.emit();this.cargar();},error:e=>this.error=this.message(e)}));}
 compra(id:number){this.requests.add(this.service.purchase(id).subscribe({next:p=>this.purchases[id]=`Pedido #${p.orderId} · ${p.productName} · ${p.quantity} unidades · ${p.status} · ${p.createdAt}`,error:e=>this.error=this.message(e)}));}
 responder(id:number){this.replyErrors[id]='';if(!this.replies[id]?.trim()){this.replyErrors[id]='Escribe una respuesta de 1 a 2000 caracteres.';return;}this.busy=true;this.requests.add(this.service.reply(id,this.replies[id]).pipe(finalize(()=>this.busy=false)).subscribe({next:()=>{this.mensaje='Respuesta guardada.';this.cargar();},error:e=>this.replyErrors[id]=this.message(e)}));}
 quitarRespuesta(id:number){this.busy=true;this.requests.add(this.service.removeReply(id).pipe(finalize(()=>this.busy=false)).subscribe({next:()=>{this.mensaje='Respuesta eliminada.';this.cargar();},error:e=>this.error=this.message(e)}));}
 private message(e:any){return e.error?.message||e.error?.detail||'No se pudo completar la operación. Conservamos tus datos; puedes reintentar.';}
}
