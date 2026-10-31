import { Component, EventEmitter, Output, effect, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, finalize } from 'rxjs';
import { AuthService } from '../core/services/auth.service';
import { Aviso, NotificacionesService } from './notificaciones.service';
import { MisPedidosComponent } from '../pedidos/components/mis-pedidos.component';
import { PedidosRecibidosComponent } from '../panel-vendedor/components/pedidos-recibidos.component';
@Component({
  selector: 'app-notificaciones', standalone: true,
  imports: [CommonModule, MisPedidosComponent, PedidosRecibidosComponent],
  template: `
    <section *ngIf="!pedido" class="bandeja" role="dialog" aria-modal="true" aria-labelledby="titulo-notificaciones">
      <button type="button" (click)="cerrar.emit()" aria-label="Cerrar notificaciones">×</button>
      <h2 id="titulo-notificaciones">Notificaciones</h2>
      <p *ngIf="cargando" role="status">Cargando notificaciones…</p>
      <p *ngIf="error" role="alert">{{ error }}</p>
      <button *ngIf="error" type="button" (click)="cargar()" [disabled]="cargando">Reintentar</button>
      <p *ngIf="!cargando && !error && avisos.length === 0">No hay notificaciones.</p>
      <ul><li *ngFor="let aviso of avisos">
        <h3>{{ aviso.title }}</h3><p>{{ aviso.message }}</p>
        <p>{{ aviso.createdAt | date:'dd/MM/yyyy HH:mm:ss' }}</p>
        <button *ngIf="aviso.orderId" type="button" (click)="pedido = aviso.orderId">Abrir pedido #{{ aviso.orderId }}</button>
      </li></ul>
      <button *ngIf="hayMas && !error" type="button" (click)="cargar()" [disabled]="cargando">Cargar más</button>
    </section>
    <app-mis-pedidos *ngIf="pedido && !vendedor" [pedidoInicial]="pedido" (cerrar)="pedido = null"></app-mis-pedidos>
    <div *ngIf="pedido && vendedor" class="bandeja"><app-pedidos-recibidos [pedidoInicial]="pedido" (cerrar)="pedido = null"></app-pedidos-recibidos></div>
  `,
  styles: [`.bandeja{position:fixed;inset:5vh 5vw;z-index:1100;background:white;color:#243342;overflow:auto;padding:1.5rem;border-radius:1rem;box-shadow:0 0 0 100vmax #0008;max-width:52rem;margin:auto}li{padding:.75rem;border-bottom:1px solid #ddd}button{margin:.4rem;padding:.5rem}`]
})
export class NotificacionesComponent {
  @Output() cerrar = new EventEmitter<void>();
  private readonly auth = inject(AuthService);
  private readonly servicio = inject(NotificacionesService);
  private peticion?: Subscription;
  avisos: Aviso[] = [];
  pedido: number | null = null;
  vendedor = false;
  cargando = false;
  error = '';
  hayMas = false;
  private pagina = 0;
  constructor() {
    effect(onCleanup => {
      const cuenta = this.auth.cuenta();
      this.peticion?.unsubscribe(); this.avisos = []; this.pedido = null;
      this.pagina = 0; this.error = ''; this.hayMas = false; this.cargando = false;
      this.vendedor = cuenta?.activeRole === 'VENDEDOR';
      if (cuenta?.activeRole === 'COMPRADOR' || this.vendedor) this.cargar();
      onCleanup(() => this.peticion?.unsubscribe());
    });
  }
  cargar(): void {
    const rol = this.auth.cuenta()?.activeRole;
    if (this.cargando || (rol !== 'COMPRADOR' && rol !== 'VENDEDOR')) return;
    this.cargando = true; this.error = '';
    this.peticion = this.servicio.listar(this.vendedor, this.pagina).pipe(finalize(() => this.cargando = false)).subscribe({
      next: page => {
        const ids = new Set(this.avisos.map(n => n.id));
        this.avisos = [...this.avisos, ...page.items.filter(n => !ids.has(n.id))];
        this.hayMas = page.hasMore; this.pagina++;
      }, error: () => this.error = 'No se pudieron cargar las notificaciones. Inténtalo de nuevo.'
    });
  }
}
