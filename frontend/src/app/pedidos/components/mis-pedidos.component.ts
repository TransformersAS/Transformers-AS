import { Component, EventEmitter, Output, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, finalize } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { PedidosService } from '../services/pedidos.service';
import { DetallePedido, EstadoPedido, MotivoCancelacion, Pedido } from '../models/pedido.model';
import { ResumenPedidoComponent } from './resumen-pedido.component';
import { SeguimientoLogisticoComponent } from '../../seguimiento/components/seguimiento-logistico.component';
import { ConsultaDevolucionComponent } from '../../seguimiento/components/consulta-devolucion.component';
import { estadoConSeguimiento } from '../../seguimiento/models/seguimiento.model';
import { DevolverLineaComponent } from '../../devoluciones/components/devolver-linea.component';

@Component({
  selector: 'app-mis-pedidos', standalone: true,
  imports: [CommonModule, FormsModule, ResumenPedidoComponent, SeguimientoLogisticoComponent, ConsultaDevolucionComponent,
    DevolverLineaComponent],
  templateUrl: './mis-pedidos.component.html',
  styleUrl: './mis-pedidos.component.scss'
})
export class MisPedidosComponent {
  @Output() cerrar = new EventEmitter<void>();
  readonly auth = inject(AuthService);
  private readonly servicio = inject(PedidosService);
  private peticiones = new Subscription();
  pedidos: Pedido[] = [];
  detalle: DetallePedido | null = null;
  cargando = false;
  cancelando = false;
  confirmar = false;
  conflicto = false;
  error = '';
  exito = '';
  motivo: MotivoCancelacion | '' = '';
  explicacion = '';
  readonly motivos: { codigo: MotivoCancelacion; etiqueta: string }[] = [
    { codigo: 'CHANGED_MIND', etiqueta: 'Ya no quiero el pedido' },
    { codigo: 'OTHER', etiqueta: 'Otro' }
  ];

  get puedeCancelar(): boolean {
    return this.detalle?.status === 'CONFIRMED' || this.detalle?.status === 'IN_PREPARATION';
  }

  get motivoValido(): boolean {
    return this.motivos.some(m => m.codigo === this.motivo)
      && (this.motivo !== 'OTHER' || (this.explicacion.trim().length > 0 && this.explicacion.length <= 1000));
  }

  prepararCancelacion(): void {
    this.motivo = '';
    this.explicacion = '';
    this.confirmar = true;
  }

  constructor() {
    effect(onCleanup => {
      const cuenta = this.auth.cuenta();
      this.peticiones = new Subscription();
      this.pedidos = [];
      this.detalle = null;
      this.error = this.exito = '';
      this.confirmar = this.conflicto = false;
      if (cuenta?.activeRole === 'COMPRADOR') this.cargarLista();
      const pendientes = this.peticiones;
      onCleanup(() => pendientes.unsubscribe());
    });
  }

  get ocupada(): boolean { return this.cargando || this.cancelando; }

  cargarLista(): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.detalle = null;
    this.confirmar = false;
    this.error = this.exito = '';
    this.cargando = true;
    this.peticiones.add(this.servicio.listarMisPedidos().pipe(
      finalize(() => this.cargando = false)
    ).subscribe({
      next: pedidos => this.pedidos = pedidos,
      error: error => this.error = this.mensaje(error)
    }));
  }

  verDetalle(id: number, conservarError = false): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.detalle = null;
    this.confirmar = this.conflicto = false;
    if (!conservarError) this.error = this.exito = '';
    this.cargando = true;
    this.peticiones.add(this.servicio.obtenerPedido(id).pipe(
      finalize(() => this.cargando = false)
    ).subscribe({
      next: detalle => this.detalle = detalle,
      error: error => this.error = this.mensaje(error)
    }));
  }

  cancelarPedido(): void {
    const pedido = this.detalle;
    if (this.ocupada || !this.confirmar || this.conflicto || !pedido || !this.puedeCancelar || !this.motivoValido
        || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.cancelando = true;
    this.error = this.exito = '';
    this.peticiones.add(this.servicio.cancelarPedido(pedido.id, {
      reasonCode: this.motivo as MotivoCancelacion,
      ...(this.motivo === 'OTHER' ? { details: this.explicacion.trim() } : {})
    }).pipe(
      finalize(() => this.cancelando = false)
    ).subscribe({
      next: resultado => {
        this.detalle = { ...pedido, status: resultado.status };
        this.pedidos = this.pedidos.map(p => p.id === pedido.id
          ? { ...p, status: resultado.status } : p);
        this.confirmar = false;
        this.exito = 'Pedido cancelado. ' + resultado.refund.message;
      },
      error: (error: HttpErrorResponse) => {
        this.confirmar = false;
        this.error = this.mensaje(error);
        if (error.status === 404) {
          this.detalle = null;
          this.pedidos = this.pedidos.filter(p => p.id !== pedido.id);
        }
        if (error.status === 409) this.conflicto = true;
      }
    }));
  }

  /** El envío ya existe (o debería) desde que el pedido queda listo para despacho: ahí aparece el seguimiento. */
  conSeguimiento(estado: EstadoPedido): boolean { return estadoConSeguimiento(estado); }

  /** El seguimiento detectó un estado nuevo informado por logística: el detalle y la lista quedan al día. */
  estadoActualizado(estado: string): void {
    const pedido = this.detalle;
    if (!pedido) return;
    const nuevo = estado as EstadoPedido;
    this.detalle = { ...pedido, status: nuevo };
    this.pedidos = this.pedidos.map(p => p.id === pedido.id ? { ...p, status: nuevo } : p);
  }

  private mensaje(error: HttpErrorResponse): string {
    switch (error.status) {
      case 401: return 'La sesión ha finalizado. Inicia sesión de nuevo.';
      case 403: return 'No tienes permiso para esta operación. Comprueba tu rol activo.';
      case 404: return 'Pedido no disponible o no encontrado.';
      case 409: return 'El pedido ya no está en un estado cancelable. Actualiza el detalle.';
      default: return 'No se pudo completar la operación. Inténtalo de nuevo.';
    }
  }
}
