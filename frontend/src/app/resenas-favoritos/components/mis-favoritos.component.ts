import { Component, EventEmitter, Output, inject, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, forkJoin } from 'rxjs';
import { Alerta, Favorito, FavoritosService } from '../services/favoritos.service';
@Component({
  selector: 'app-mis-favoritos', standalone: true, imports: [CommonModule, FormsModule],
  template: `
    <section role="dialog" aria-modal="true" aria-labelledby="titulo-favoritos">
      <header><h2 id="titulo-favoritos">Mis favoritos y alertas</h2><button type="button" (click)="cerrar.emit()" aria-label="Cerrar favoritos">×</button></header>
      <p role="alert" *ngIf="error">{{error}}</p><p role="status" *ngIf="mensaje">{{mensaje}}</p>
      <p role="alert" *ngIf="servicio.error()">{{servicio.error()}}</p>
      <button type="button" (click)="cargar()" [disabled]="ocupado">Actualizar favoritos y alertas</button>
      <label>Ordenar favoritos <select [(ngModel)]="orden"><option value="recientes">Más recientes</option><option value="nombre">Nombre</option><option value="precio">Menor precio</option></select></label>
      <p *ngIf="!ocupado && !ordenados().length">Todavía no tienes favoritos. Guárdalos con el corazón del catálogo.</p>
      <article *ngFor="let p of ordenados()">
        <h3>{{p.name}}</h3><p>{{p.price | currency:'COP':'symbol-narrow':'1.0-0'}} · {{p.active ? 'Activa' : 'Publicación inactiva'}} · Stock: {{p.stock}}</p>
        <button type="button" (click)="abrir.emit(p.id)" [disabled]="!p.active">Ver producto</button>
        <button type="button" (click)="agregar.emit(p.id)" [disabled]="!p.active || p.stock < 1">Agregar al carrito</button>
        <button type="button" (click)="comparar.emit(p.id)" [disabled]="!p.active">Comparar</button>
        <button type="button" (click)="compartir(p.id)">Compartir enlace</button>
        <button type="button" (click)="servicio.toggle(p.id)" [disabled]="servicio.ocupados().includes(p.id)">{{servicio.ids().includes(p.id) ? 'Retirar de favoritos' : 'Guardar de nuevo'}}</button>
        <button type="button" (click)="seleccionar(p)" [disabled]="!p.active">Configurar alerta</button>
      </article>
      <button *ngIf="hayMas" type="button" (click)="mas()" [disabled]="ocupado">Cargar más favoritos</button>
      <form *ngIf="seleccionado as p" (ngSubmit)="crear()">
        <h3>Alerta para {{p.name}}</h3>
        <label>Tipo <select name="tipo" aria-label="Tipo" [(ngModel)]="tipo"><option value="PRICE">Precio objetivo</option><option value="AVAILABILITY">Disponibilidad de variante</option></select></label>
        <label *ngIf="tipo === 'PRICE'">Precio objetivo <input name="precio" type="number" min="0.01" step="0.01" required [(ngModel)]="precio"></label>
        <label *ngIf="tipo === 'AVAILABILITY'">Variante agotada <select name="variante" aria-label="Variante agotada" required [(ngModel)]="variante"><option value="">Selecciona una variante</option><option *ngFor="let v of agotadas(p)" [value]="v.name">{{v.name}}</option></select></label>
        <p *ngIf="tipo === 'AVAILABILITY' && !agotadas(p).length">Este producto no tiene variantes agotadas.</p>
        <button type="submit" [disabled]="ocupado || (tipo === 'PRICE' ? !precio || precio <= 0 : !variante)">Crear alerta</button>
        <button type="button" (click)="seleccionado = null">Cerrar formulario</button>
      </form>
      <h2>Mis alertas</h2><p *ngIf="!alertas.length">No tienes alertas registradas.</p>
      <article *ngFor="let a of alertas">
        <h3>{{a.productName}}</h3><p>{{a.kind === 'PRICE' ? 'Precio objetivo' : 'Disponibilidad'}} · {{a.variantName || (a.targetPrice | currency:'COP':'symbol-narrow':'1.0-0')}} · {{estadoTexto(a.status)}}</p>
        <button type="button" (click)="abrir.emit(a.productId)">Ver producto</button>
        <ng-container *ngIf="a.kind === 'PRICE'"><label>Nuevo precio <input type="number" min="0.01" step="0.01" [(ngModel)]="precios[a.id]"></label><button type="button" (click)="editar(a)" [disabled]="ocupado || !precios[a.id] || precios[a.id] <= 0">Modificar</button></ng-container>
        <button *ngIf="a.status === 'ACTIVE'" type="button" (click)="estado(a,'PAUSED')" [disabled]="ocupado">Pausar</button>
        <ng-container *ngIf="a.kind === 'AVAILABILITY'">
          <button type="button" (click)="cargarVariantes(a)" [disabled]="ocupado">Cambiar variante</button>
          <label *ngIf="variantesEdicion[a.id]">Variante agotada para modificar
            <select [(ngModel)]="nuevaVariante[a.id]"><option value="">Selecciona una variante</option><option *ngFor="let v of variantesEdicion[a.id]" [value]="v.name">{{v.name}}</option></select>
          </label>
          <button *ngIf="variantesEdicion[a.id]" type="button" (click)="editarVariante(a)" [disabled]="ocupado || !nuevaVariante[a.id]">Guardar variante</button>
        </ng-container>
        <button *ngIf="a.status !== 'ACTIVE'" type="button" (click)="estado(a,'ACTIVE')" [disabled]="ocupado">Reactivar</button>
        <button type="button" (click)="eliminar(a)" [disabled]="ocupado">Eliminar alerta</button>
      </article>
    </section>`,
  styles: [`:host{display:block;overflow:auto;height:100%;background:#fffdf6;color:#183037}section{max-width:64rem;margin:auto;padding:1.5rem}header{display:flex;justify-content:space-between;align-items:center}article,form{padding:1rem;margin:1rem 0;border:1px solid #ddd;border-radius:1rem}button,select,input{font:inherit;padding:.6rem;margin:.3rem;border-radius:.5rem;border:1px solid #8997a6}button{background:#183037;color:white;cursor:pointer}button:disabled{opacity:.5;cursor:default}label{display:inline-block}input{max-width:10rem}[role=alert]{color:#a21c12}`]
})
export class MisFavoritosComponent implements OnDestroy {
  @Output() cerrar = new EventEmitter<void>(); @Output() abrir = new EventEmitter<number>();
  @Output() agregar = new EventEmitter<number>(); @Output() comparar = new EventEmitter<number>();
  readonly servicio = inject(FavoritosService);
  private requests = new Subscription();
  favoritos: Favorito[] = []; alertas: Alerta[] = []; seleccionado: Favorito | null = null;
  precios: Record<string, number> = {}; precio: number | null = null; variante = ''; tipo = 'PRICE'; orden = 'recientes';
  variantesEdicion: Record<string, Favorito['variants']> = {}; nuevaVariante: Record<string,string> = {};
  error = ''; mensaje = ''; ocupado = false; pagina = 0; hayMas = false;
  constructor() { this.cargar(); }
  ngOnDestroy() { this.requests.unsubscribe(); }
  cargar() { this.ocupado=true; this.error=''; this.requests.add(forkJoin({favoritos:this.servicio.listar(0),alertas:this.servicio.alertas(),ids:this.servicio.obtenerIds()}).subscribe({next: r=>{this.servicio.ids.set(r.ids);this.favoritos=r.favoritos;this.alertas=r.alertas;this.pagina=0;this.hayMas=r.favoritos.length===50;this.ocupado=false;},error:()=>{this.error='No se pudieron cargar favoritos y alertas. Reintenta.';this.ocupado=false;}})); }
  mas() { this.ocupado=true;this.requests.add(this.servicio.listar(this.pagina+1).subscribe({next:rows=>{this.favoritos=[...this.favoritos,...rows];this.pagina++;this.hayMas=rows.length===50;this.ocupado=false;},error:()=>{this.error='No se pudo cargar la siguiente página.';this.ocupado=false;}})); }
  ordenados() { return this.favoritos.filter(p=>this.servicio.ids().includes(p.id)).sort((a,b)=>this.orden==='nombre'?a.name.localeCompare(b.name):this.orden==='precio'?a.price-b.price:0); }
  agotadas(p: Favorito) { return p.variants.filter(v=>v.stock===0); }
  seleccionar(p: Favorito) { this.seleccionado=p;this.precio=null;this.variante='';this.tipo='PRICE';this.mensaje=''; }
  estadoTexto(s: string) { return ({ACTIVE:'Activa',PAUSED:'Pausada',TRIGGERED:'Cumplida'} as Record<string,string>)[s] || s; }
  private resultado(already: boolean) { this.mensaje=already?'El precio actual ya cumple tu objetivo; no se dejó una alerta activa.':'Alerta guardada.';this.seleccionado=null;this.cargar(); }
  private fallo(e: any) { this.error=e.error?.detail || e.error?.message || 'No se pudo guardar el cambio. Revisa la condición y reintenta.';this.ocupado=false; }
  crear() { if (!this.seleccionado || this.ocupado) return;this.ocupado=true;this.error='';this.requests.add(this.servicio.crear({productId:this.seleccionado.id,kind:this.tipo,variantName:this.tipo==='AVAILABILITY'?this.variante:null,targetPrice:this.tipo==='PRICE'?this.precio:null}).subscribe({next:r=>this.resultado(r.alreadySatisfied),error:e=>this.fallo(e)})); }
  editar(a: Alerta) { this.ocupado=true;this.requests.add(this.servicio.editar(a.id,{productId:a.productId,kind:a.kind,variantName:a.variantName,targetPrice:this.precios[a.id]}).subscribe({next:r=>this.resultado(r.alreadySatisfied),error:e=>this.fallo(e)})); }
  cargarVariantes(a: Alerta) { this.ocupado=true;this.requests.add(this.servicio.producto(a.productId).subscribe({next:p=>{this.variantesEdicion[a.id]=p.variants.filter(v=>v.stock===0);this.nuevaVariante[a.id]='';this.ocupado=false;},error:e=>this.fallo(e)})); }
  editarVariante(a: Alerta) { this.ocupado=true;this.requests.add(this.servicio.editar(a.id,{productId:a.productId,kind:a.kind,variantName:this.nuevaVariante[a.id],targetPrice:null}).subscribe({next:r=>this.resultado(r.alreadySatisfied),error:e=>this.fallo(e)})); }
  estado(a: Alerta,s: string) { this.ocupado=true;this.requests.add(this.servicio.estado(a.id,s).subscribe({next:()=>this.cargar(),error:e=>this.fallo(e)})); }
  eliminar(a: Alerta) { this.ocupado=true;this.requests.add(this.servicio.eliminar(a.id).subscribe({next:()=>this.cargar(),error:e=>this.fallo(e)})); }
  async compartir(id: number) { const url=new URL(location.pathname,location.origin);url.searchParams.set('producto',String(id));try { await navigator.clipboard.writeText(url.toString());this.mensaje='Enlace copiado.'; } catch { this.mensaje='Enlace del producto: '+url.toString(); } }
}
