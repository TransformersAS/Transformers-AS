import { Component,Input,OnChanges,OnDestroy,inject } from '@angular/core';import { CommonModule } from '@angular/common';import { Subscription,forkJoin } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';import { ReportarContenidoComponent } from '../../reportes/components/reportar-contenido.component';
import { ResenasService,Review,Rating } from '../services/resenas.service';
@Component({selector:'app-resenas-producto',standalone:true,imports:[CommonModule,ReportarContenidoComponent],styleUrl:'./resenas.scss',template:`
<section aria-label="Reseñas del producto"><h3>Reseñas de compras verificadas</h3>
<p *ngIf="error" role="alert">{{error}}</p><button type="button" (click)="cargar()">Actualizar reseñas</button>
<ng-container *ngIf="summary"><p>Promedio: {{summary.average|number:'1.1-2'}} / 5 · {{summary.count}} reseñas</p><ul><li *ngFor="let count of summary.distribution;let i=index">{{i+1}} estrellas: {{count}}</li></ul></ng-container>
<p *ngIf="!rows.length && !error">Todavía no hay reseñas.</p>
<article *ngFor="let r of rows"><strong>{{r.rating}} / 5 · Compra verificada</strong><p>{{r.comment}}</p><small>{{r.createdAt|date:'medium'}}</small>
<div class="fotos"><img *ngFor="let id of r.images" [src]="service.image(id)" loading="lazy" alt="Imagen de reseña"></div>
<div class="respuesta" *ngIf="r.reply"><strong>Respuesta del vendedor</strong><p>{{r.reply.text}}</p><app-reportar-contenido *ngIf="auth.autenticada()" tipo="RESPUESTA_RESENA" [contenidoId]="r.id"></app-reportar-contenido></div>
<app-reportar-contenido *ngIf="auth.autenticada()" tipo="RESENA" [contenidoId]="r.id"></app-reportar-contenido></article>
<button type="button" *ngIf="rows.length===20" (click)="page=page+1;cargar(false)">Siguiente página de reseñas</button>
<button type="button" *ngIf="page>0" (click)="page=page-1;cargar(false)">Anterior página de reseñas</button></section>`})
export class ResenasProductoComponent implements OnChanges,OnDestroy {
 @Input({required:true}) productId!:number;readonly service=inject(ResenasService);readonly auth=inject(AuthService);
 rows:Review[]=[];summary:Rating|null=null;error='';page=0;private request=new Subscription();
 ngOnChanges(){this.page=0;this.cargar();}ngOnDestroy(){this.request.unsubscribe();}
 cargar(reset=true){if(reset)this.page=0;this.request.unsubscribe();this.request=new Subscription();this.error='';this.request.add(forkJoin({rows:this.service.product(this.productId,this.page),summary:this.service.summary(this.productId)}).subscribe({next:r=>{this.rows=Array.isArray(r.rows)?r.rows:[];this.summary=r.summary;},error:()=>{this.error='No se pudieron consultar las reseñas. Puedes reintentar.';}}));}
}
