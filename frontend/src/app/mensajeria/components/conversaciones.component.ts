import { Component, OnDestroy, effect, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize, forkJoin } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { MensajeriaService } from '../services/mensajeria.service';
import { Conversacion, Mensaje } from '../models/conversacion.model';
import { BandejaVaciaComponent } from './bandeja-vacia.component';
import { ReportarContenidoComponent } from '../../reportes/components/reportar-contenido.component';

@Component({ selector: 'app-conversaciones', standalone: true,
  imports: [CommonModule, FormsModule, BandejaVaciaComponent, ReportarContenidoComponent],
  styles: [`:host{position:fixed;inset:5vh 4vw;z-index:10050;background:white;color:#17263b;border:1px solid #ddd;border-radius:16px;box-shadow:0 12px 60px #0005;overflow:auto;padding:24px} .layout{display:grid;grid-template-columns:minmax(180px,1fr) 3fr;gap:24px} button,textarea,input{font:inherit;margin:6px;padding:8px} textarea{display:block;width:90%} article{border-bottom:1px solid #ddd;padding:12px;white-space:pre-wrap} img{max-width:240px;max-height:240px} .error{color:#a32121} @media(max-width:700px){.layout{display:block}}`],
  template: `<section aria-label="Mensajería">
    <button type="button" (click)="servicio.cerrar()">Salir de mensajería</button><h2>Conversaciones</h2>
    <p role="alert" class="error" *ngIf="error">{{error}}</p>
    <div class="layout"><nav aria-label="Bandeja de conversaciones">
      <label><input type="checkbox" [(ngModel)]="archivadas" (change)="cambiarBandeja()">Archivadas</label>
      <app-bandeja-vacia *ngIf="!lista.length"></app-bandeja-vacia>
      <div *ngFor="let c of lista"><button type="button" (click)="abrir(c.id)">{{c.storeName}} · {{c.productName}} <strong *ngIf="c.unread">{{c.unread}} sin leer</strong>{{c.closed ? ' · Cerrada' : ''}}</button></div>
      <button *ngIf="pagina>0" (click)="pagina=pagina-1;cargarLista()">Anteriores</button>
      <button *ngIf="lista.length===30" (click)="pagina=pagina+1;cargarLista()">Más conversaciones</button>
    </nav><main *ngIf="actual as c">
      <h3>{{c.productName}} · {{c.storeName}}</h3><p>Producto #{{c.productId}} <span *ngIf="c.orderId">· Pedido #{{c.orderId}}</span></p>
      <p>{{c.closed ? 'Conversación cerrada' : 'Conversación abierta'}}</p>
      <button (click)="estado('archive',!c.archived)">{{c.archived ? 'Restaurar' : 'Archivar'}}</button>
      <button (click)="estado('closed',!c.closed)">{{c.closed ? 'Reabrir conversación' : 'Cerrar conversación'}}</button>
      <button *ngIf="hayAnteriores" (click)="anteriores()">Mensajes anteriores</button>
      <article *ngFor="let m of mensajes; trackBy: porId">
        <strong>{{m.senderId===cuentaId ? 'Tú' : 'Participante'}}</strong> <time>{{m.createdAt | date:'dd/MM/yyyy HH:mm:ss'}}</time>
        <p>{{m.text}}</p><img *ngIf="m.image" [src]="servicio.imagen(m.id)" alt="Imagen del mensaje" loading="lazy">
        <app-reportar-contenido *ngIf="m.senderId!==cuentaId" tipo="MENSAJE" [contenidoId]="''+m.id"></app-reportar-contenido>
      </article>
      <form (ngSubmit)="enviar()">
        <label for="mensaje-cu10">Mensaje</label><textarea id="mensaje-cu10" name="texto" [(ngModel)]="texto" maxlength="4000" [disabled]="enviando || c.closed"></textarea>
        <label>Imagen opcional (JPG, PNG o WebP; máximo 5 MiB)<input type="file" accept="image/jpeg,image/png,image/webp" (change)="adjuntar($event)" [disabled]="enviando || c.closed"></label>
        <button type="button" *ngIf="archivo" (click)="archivo=null;clave=null">Quitar {{archivo.name}}</button>
        <button type="submit" [disabled]="enviando || c.closed || (!texto.trim() && !archivo)">{{enviando ? 'Enviando…' : 'Enviar mensaje'}}</button>
      </form>
    </main></div></section>` })
export class ConversacionesComponent implements OnDestroy {
  readonly servicio = inject(MensajeriaService);
  private readonly auth = inject(AuthService);
  private subs = new Subscription();
  private consulta?: Subscription;
  private timer = window.setInterval(() => { if (!document.hidden && !this.enviando) this.actualizar(); }, 3000);
  lista: Conversacion[] = []; actual: Conversacion | null = null; mensajes: Mensaje[] = [];
  texto = ''; archivo: File | null = null; clave: string | null = null; error = ''; enviando = false;
  archivadas = false; pagina = 0; hayAnteriores = false; private actualizando = false; private cargandoLista = false;
  private claveTexto = ''; private claveArchivo: File | null = null;
  private borradores = new Map<number, {texto: string; archivo: File | null; clave: string | null; claveTexto: string; claveArchivo: File | null}>();
  get cuentaId() { return this.auth.cuenta()?.accountId; }
  constructor() {
    effect(() => {
      const cuenta = this.auth.cuenta(); const id = this.servicio.seleccionada();
      if (!cuenta) return;
      this.cargarLista(); if (id) this.abrir(id);
    });
  }
  private fallo(e: any) { this.error = e.error?.message || 'No se pudo completar la operación. Reintenta; tu borrador se conserva.'; }
  cargarLista() { if(this.cargandoLista)return;this.cargandoLista=true;this.subs.add(this.servicio.obtenerConversaciones(this.archivadas,this.pagina).pipe(finalize(()=>this.cargandoLista=false)).subscribe({next: x => this.lista=x,error: e=>this.fallo(e)})); }
  cambiarBandeja() { this.pagina=0;this.cargarLista(); }
  abrir(id: number) {
    if(this.enviando) return;
    if(this.actual) this.borradores.set(this.actual.id,{texto:this.texto,archivo:this.archivo,clave:this.clave,claveTexto:this.claveTexto,claveArchivo:this.claveArchivo});
    this.consulta?.unsubscribe(); this.error=''; this.actual=null;this.mensajes=[];
    const draft=this.borradores.get(id);this.texto=draft?.texto||'';this.archivo=draft?.archivo||null;this.clave=draft?.clave||null;this.claveTexto=draft?.claveTexto||'';this.claveArchivo=draft?.claveArchivo||null;
    this.consulta=forkJoin({c:this.servicio.detalle(id),m:this.servicio.historial(id)}).subscribe({next:({c,m})=>{
      this.actual=c;this.mensajes=m;this.hayAnteriores=m.length===50;this.marcar();
    },error:e=>this.fallo(e)});
  }
  private marcar() {
    const c=this.actual,last=this.mensajes.at(-1)?.id;
    if(c && last && last>c.readThrough && !document.hidden) this.subs.add(this.servicio.leer(c.id,last).subscribe({next:()=>{c.readThrough=last;c.unread=0;this.cargarLista();},error:e=>this.fallo(e)}));
  }
  actualizar() {
    if(this.actualizando) return;
    this.cargarLista();const c=this.actual;if(!c) return;this.actualizando=true;
    this.subs.add(forkJoin({c:this.servicio.detalle(c.id),m:this.servicio.historial(c.id,this.mensajes.at(-1)?.id||0)})
      .pipe(finalize(()=>this.actualizando=false)).subscribe({next:({c:next,m})=>{
        if(this.actual?.id!==c.id) return;this.actual=next;this.mezclar(m);this.marcar();
      },error:e=>this.fallo(e)}));
  }
  private mezclar(nuevos: Mensaje[]) {const all=new Map(this.mensajes.map(m=>[m.id,m]));nuevos.forEach(m=>all.set(m.id,m));this.mensajes=[...all.values()].sort((a,b)=>a.id-b.id);}
  anteriores() { const id=this.actual?.id;if(!id) return;this.subs.add(this.servicio.historial(id,0,this.mensajes[0]?.id).subscribe({next:m=>{if(this.actual?.id===id){this.mezclar(m);this.hayAnteriores=m.length===50;}},error:e=>this.fallo(e)})); }
  adjuntar(event: Event) {
    const input=event.target as HTMLInputElement;const f=input.files?.[0];this.error='';this.archivo=null;this.clave=null;
    if(!f) return;
    if(!['image/jpeg','image/png','image/webp'].includes(f.type) || f.size>5242880) {this.error='Selecciona JPG, PNG o WebP de hasta 5 MiB.';input.value='';return;}
    this.archivo=f;
  }
  enviar() {
    const c=this.actual;if(!c || this.enviando) return;
    this.enviando=true;this.error='';
    if(this.texto!==this.claveTexto || this.archivo!==this.claveArchivo)this.clave=null;
    this.clave ||= crypto.randomUUID();this.claveTexto=this.texto;this.claveArchivo=this.archivo;
    this.subs.add(this.servicio.enviar(c.id,this.texto,this.clave,this.archivo).pipe(finalize(()=>this.enviando=false)).subscribe({
      next:m=>{this.mezclar([m]);this.texto='';this.archivo=null;this.clave=null;this.borradores.delete(c.id);this.marcar();this.cargarLista();},
      error:e=>{this.fallo(e);if(e.status===400){this.archivo=null;this.clave=null;}else if(e.status===409 && e.error?.code==='MESSAGE_KEY_REUSED')this.clave=null;}
    }));
  }
  estado(action: 'archive'|'closed',value:boolean) {const id=this.actual?.id;if(id)this.subs.add(this.servicio.estado(id,action,value).subscribe({next:()=>{if(this.actual?.id===id){if(action==='closed')this.actual.closed=value;else this.actual.archived=value;}this.cargarLista();},error:e=>this.fallo(e)}));}
  porId(_:number,m:Mensaje) {return m.id;}
  ngOnDestroy() {window.clearInterval(this.timer);this.subs.unsubscribe();this.consulta?.unsubscribe();this.borradores.clear();}
}
