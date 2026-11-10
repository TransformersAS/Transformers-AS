import { Component,Input,Output,EventEmitter,OnChanges,OnDestroy,inject } from '@angular/core';
import { CommonModule } from '@angular/common';import { FormsModule } from '@angular/forms';import { Subscription,finalize } from 'rxjs';
import { ResenasService,Review } from '../services/resenas.service';
@Component({selector:'app-editor-resena',standalone:true,imports:[CommonModule,FormsModule],styleUrl:'./resenas.scss',template:`
<section aria-label="Publicar o editar reseña">
 <button *ngIf="!abierto" type="button" (click)="abrir()">Reseñar producto</button>
 <ng-container *ngIf="abierto">
 <h3>Tu reseña de {{nombre}}</h3><p *ngIf="cargando" role="status">Verificando compra…</p>
 <p *ngIf="errors.general" role="alert">{{errors.general}}</p><p *ngIf="mensaje" role="status">{{mensaje}}</p>
 <form *ngIf="elegible" (ngSubmit)="guardar()" novalidate>
  <label>Calificación obligatoria<select name="rating" [(ngModel)]="rating" [attr.aria-invalid]="!!errors.rating"><option [ngValue]="null">Selecciona de 1 a 5</option><option *ngFor="let n of [1,2,3,4,5]" [ngValue]="n">{{n}} estrellas</option></select></label>
  <small class="error" *ngIf="errors.rating" role="alert">{{errors.rating}}</small>
  <label>Comentario opcional<textarea name="comment" [(ngModel)]="comment" maxlength="2000"></textarea></label><small>{{comment.length}} / 2000</small>
  <small class="error" *ngIf="errors.comment" role="alert">{{errors.comment}}</small>
  <label>Imágenes opcionales (JPG o PNG)<input type="file" accept="image/jpeg,image/png" multiple (change)="elegir($event)"></label>
  <small>Máximo 3 imágenes, 5 MB cada una. Puedes publicar solo la calificación.</small>
  <small class="error" *ngIf="errors.images" role="alert">{{errors.images}}</small>
  <div class="fotos"><div *ngFor="let id of keep"><img [src]="service.image(id)" loading="lazy" alt="Imagen de tu reseña"><button type="button" (click)="quitar(id)">Quitar imagen {{id}}</button></div></div>
  <ul><li *ngFor="let file of files;let i=index">{{file.name}} <button type="button" (click)="files.splice(i,1)">Quitar archivo {{i+1}}</button></li></ul>
  <button class="primario" type="submit" [disabled]="ocupado">{{actual?'Guardar cambios de reseña':'Publicar reseña'}}</button>
 </form><button type="button" (click)="abierto=false" [disabled]="ocupado">Cerrar formulario de reseña</button>
 </ng-container>
</section>`})
export class EditorResenaComponent implements OnChanges,OnDestroy {
 @Input({required:true}) orderId!:number;@Input({required:true}) productId!:number;@Input() nombre='producto';@Input() iniciar=false;
 @Output() changed=new EventEmitter<void>();readonly service=inject(ResenasService);
 private requests=new Subscription();abierto=false;elegible=false;cargando=false;ocupado=false;actual:Review|null=null;
 rating:number|null=null;comment='';keep:number[]=[];files:File[]=[];errors:Record<string,string>={};mensaje='';
 ngOnChanges(){this.requests.unsubscribe();this.requests=new Subscription();this.abierto=false;this.actual=null;this.elegible=false;this.files=[];this.errors={};if(this.iniciar)this.abrir();}
 ngOnDestroy(){this.requests.unsubscribe();}
 abrir(){this.abierto=true;this.cargando=true;this.mensaje='';this.errors={};this.requests.add(this.service.eligibility(this.orderId,this.productId).pipe(finalize(()=>this.cargando=false)).subscribe({next:r=>{this.elegible=r.eligible;this.actual=r.review;this.rating=r.review?.rating??null;this.comment=r.review?.comment??'';this.keep=[...(r.review?.images??[])];if(!r.eligible)this.errors.general=r.message;},error:e=>this.fallo(e)}));}
 elegir(event:Event){const input=event.target as HTMLInputElement;const next=Array.from(input.files??[]);this.errors.images='';
  if(next.some(f=>!['image/jpeg','image/png'].includes(f.type)||f.size>5*1024*1024)||next.length+this.keep.length>3){this.errors.images='Selecciona hasta 3 imágenes JPG o PNG de máximo 5 MB cada una.';input.value='';return;}this.files=next;}
 quitar(id:number){this.keep=this.keep.filter(value=>value!==id);}
 guardar(){if(this.ocupado)return;this.errors={};if(!this.rating||this.rating<1||this.rating>5)this.errors.rating='Selecciona una calificación de 1 a 5.';if(this.comment.length>2000)this.errors.comment='El comentario admite hasta 2000 caracteres.';if(Object.keys(this.errors).length)return;
  this.ocupado=true;this.requests.add(this.service.save(this.actual?.id??null,{orderId:this.orderId,productId:this.productId,rating:this.rating,comment:this.comment,keepImageIds:this.keep},this.files).pipe(finalize(()=>this.ocupado=false)).subscribe({next:r=>{this.actual=r;this.keep=[...r.images];this.files=[];this.mensaje='Reseña guardada. La valoración del producto está actualizada.';this.changed.emit();},error:e=>this.fallo(e)}));}
 private fallo(e:any){const code=e.error?.code??'';const field=code==='REVIEW_RATING'?'rating':code==='REVIEW_COMMENT'?'comment':code.startsWith('IMAGE')||code==='REVIEW_IMAGES'?'images':'general';this.errors[field]=e.error?.message||e.error?.detail||'No se pudo guardar. Conservamos tus datos; puedes reintentar.';}
}
