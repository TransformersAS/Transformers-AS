import { Component, EventEmitter, Output, Input, effect, inject } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, finalize } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { PedidosService } from '../services/pedidos.service';
import { DetallePedido, EstadoPedido, MotivoCancelacion, Pedido, ETIQUETA_ESTADO_PAGO, ETIQUETA_ESTADO_PEDIDO, FiltrosPedidos } from '../models/pedido.model';
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
  @Input() pedidoInicial: number | null = null;
  @Output() cerrar = new EventEmitter<void>();
  readonly auth = inject(AuthService);
  private readonly servicio = inject(PedidosService);
  private readonly sanitizer = inject(DomSanitizer);
  private consultaComprobante?: Subscription;
  private urlComprobante: string | null = null;
  private readonly descargas = new Set<string>();
  comprobanteUrl: SafeResourceUrl | null = null;
  cargandoComprobante = false;
  errorComprobante = '';
  private peticiones = new Subscription();
  readonly etiquetasEstado = ETIQUETA_ESTADO_PEDIDO;
  readonly etiquetasPago = ETIQUETA_ESTADO_PAGO;
  readonly historicoNoDisponible = 'Información histórica no disponible';
  readonly estados = Object.entries(ETIQUETA_ESTADO_PEDIDO).map(([codigo, etiqueta]) => ({ codigo, etiqueta }));
  desde = '';
  hasta = '';
  estadoFiltro: EstadoPedido | '' = '';
  filtrosActivos: FiltrosPedidos = {};
  errorFiltros = '';
  listaCargada = false;
  private consultaLista?: Subscription;
  private consultaHistorial?: Subscription;
  cargandoHistorial = false;
  errorHistorial = '';
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
      this.peticiones.unsubscribe();
      this.cerrarComprobante();
      this.peticiones = new Subscription();
      this.cargando = this.cancelando = this.cargandoHistorial = false;
      this.errorHistorial = '';
      this.desde = this.hasta = this.estadoFiltro = '';
      this.filtrosActivos = {};
      this.errorFiltros = '';
      this.listaCargada = false;
      this.pedidos = [];
      this.detalle = null;
      this.error = this.exito = '';
      this.confirmar = this.conflicto = false;
      if (cuenta?.activeRole === 'COMPRADOR') {
        if (this.pedidoInicial != null) { const id = this.pedidoInicial; this.pedidoInicial = null; this.verDetalle(id); }
        else this.cargarLista();
      }
      const pendientes = this.peticiones;
      onCleanup(() => {
        pendientes.unsubscribe();
        this.cerrarComprobante();
        this.descargas.forEach(url => URL.revokeObjectURL(url));
        this.descargas.clear();
      });
    });
  }

  get ocupada(): boolean { return this.cargando || this.cancelando; }

  cargarLista(): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.cerrarComprobante();
    this.consultaHistorial?.unsubscribe();
    this.errorHistorial = '';
    this.detalle = null;
    this.confirmar = false;
    this.error = this.exito = '';
    this.refrescarListado();
  }

  get hayFiltrosActivos(): boolean {
    return !!(this.filtrosActivos.from || this.filtrosActivos.to || this.filtrosActivos.status);
  }

  aplicarFiltros(fechaInvalida = false): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.errorFiltros = '';
    if (fechaInvalida || !this.fechaValida(this.desde) || !this.fechaValida(this.hasta)) {
      this.errorFiltros = 'Introduce fechas válidas entre los años 1000 y 9999.';
      return;
    }
    if (this.desde && this.hasta && this.desde > this.hasta) {
      this.errorFiltros = 'La fecha inicial no puede ser posterior a la final.';
      return;
    }
    this.filtrosActivos = {
      ...(this.desde ? { from: this.desde } : {}),
      ...(this.hasta ? { to: this.hasta } : {}),
      ...(this.estadoFiltro ? { status: this.estadoFiltro } : {})
    };
    this.cargarLista();
  }

  limpiarFiltros(): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.desde = this.hasta = this.estadoFiltro = '';
    this.errorFiltros = '';
    this.filtrosActivos = {};
    this.cargarLista();
  }

  private fechaValida(value: string): boolean {
    if (!value) return true;
    if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) || value.slice(0, 4) < '1000') return false;
    const date = new Date(value + 'T00:00:00Z');
    return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }

  /** Recarga desde el servidor sin cerrar el detalle ni cambiar los filtros aplicados. */
  private refrescarListado(): void {
    this.consultaLista?.unsubscribe();
    this.cargando = true;
    this.listaCargada = false;
    this.pedidos = [];
    this.consultaLista = this.servicio.listarMisPedidos({ ...this.filtrosActivos }).pipe(
      finalize(() => this.cargando = false)
    ).subscribe({
      next: pedidos => { this.pedidos = pedidos; this.listaCargada = true; },
      error: error => this.error = this.mensaje(error)
    });
    this.peticiones.add(this.consultaLista);
  }

  verDetalle(id: number, conservarError = false): void {
    if (this.ocupada || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.cerrarComprobante();
    this.consultaHistorial?.unsubscribe();
    this.errorHistorial = '';
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
    this.cerrarComprobante();
    this.cancelando = true;
    this.error = this.exito = '';
    this.peticiones.add(this.servicio.cancelarPedido(pedido.id, {
      reasonCode: this.motivo as MotivoCancelacion,
      ...(this.motivo === 'OTHER' ? { details: this.explicacion.trim() } : {})
    }).pipe(
      finalize(() => this.cancelando = false)
    ).subscribe({
      next: resultado => {
        this.detalle = { ...pedido, status: resultado.status, paymentStatus: resultado.paymentStatus };
        this.pedidos = this.pedidos.map(p => p.id === pedido.id
          ? { ...p, status: resultado.status } : p);
        this.confirmar = false;
        this.exito = 'Pedido cancelado. ' + resultado.refund.message;
        this.refrescarListado();
        this.actualizarHistorial();
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
    this.cerrarComprobante();
    const nuevo = estado as EstadoPedido;
    this.detalle = { ...pedido, status: nuevo };
    this.refrescarListado();
    this.actualizarHistorial();
  }

  /** El historial siempre procede del backend; cancelar no crea eventos en el navegador. */
  actualizarHistorial(): void {
    const id = this.detalle?.id;
    if (!id || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.consultaHistorial?.unsubscribe();
    this.cargandoHistorial = true;
    this.errorHistorial = '';
    this.consultaHistorial = this.servicio.obtenerPedido(id).pipe(
      finalize(() => this.cargandoHistorial = false)
    ).subscribe({
      next: detalle => {
        if (this.detalle?.id === id) this.detalle = { ...this.detalle, history: detalle.history,
          paymentStatus: detalle.paymentStatus ?? this.detalle.paymentStatus,
          refunds: detalle.refunds ?? this.detalle.refunds };
      },
      error: () => this.errorHistorial = 'No se pudo actualizar el historial. Inténtalo de nuevo.'
    });
    this.peticiones.add(this.consultaHistorial);
  }

  verComprobante(descargar = false): void {
    const pedido = this.detalle;
    if (!pedido || this.ocupada || this.cargandoComprobante || this.auth.cuenta()?.activeRole !== 'COMPRADOR') return;
    this.cerrarComprobante();
    this.cargandoComprobante = true;
    this.consultaComprobante = this.servicio.obtenerComprobante(pedido.id, descargar).pipe(
      finalize(() => this.cargandoComprobante = false)
    ).subscribe({
      next: archivo => {
        if (this.detalle?.id !== pedido.id) return;
        if (archivo.type !== 'application/pdf' || archivo.size === 0) {
          this.errorComprobante = 'No se recibió un PDF válido. Inténtalo de nuevo.';
          return;
        }
        const url = URL.createObjectURL(archivo);
        if (descargar) {
          this.descargas.add(url);
          const enlace = document.createElement('a');
          enlace.href = url;
          enlace.download = `comprobante-pedido-${pedido.id}.pdf`;
          document.body.appendChild(enlace);
          enlace.click();
          enlace.remove();
          setTimeout(() => { URL.revokeObjectURL(url); this.descargas.delete(url); }, 1000);
        } else {
          this.urlComprobante = url;
          // Solo un blob PDF recibido de nuestro endpoint autenticado, nunca una URL del usuario.
          this.comprobanteUrl = this.sanitizer.bypassSecurityTrustResourceUrl(url);
        }
      },
      error: (error: HttpErrorResponse) => {
        this.errorComprobante = error.status === 401 ? 'La sesión ha finalizado. Inicia sesión de nuevo.'
          : error.status === 403 || error.status === 404 ? 'Comprobante no disponible para esta cuenta.'
          : 'No se pudo obtener el comprobante. Inténtalo de nuevo.';
      }
    });
    this.peticiones.add(this.consultaComprobante);
  }

  cerrarComprobante(): void {
    this.consultaComprobante?.unsubscribe();
    if (this.urlComprobante) URL.revokeObjectURL(this.urlComprobante);
    this.urlComprobante = null;
    this.comprobanteUrl = null;
    this.cargandoComprobante = false;
    this.errorComprobante = '';
  }

  private mensaje(error: HttpErrorResponse): string {
    switch (error.status) {
      case 400: return error.error?.message || 'Los filtros del pedido no son válidos.';
      case 401: return 'La sesión ha finalizado. Inicia sesión de nuevo.';
      case 403: return 'No tienes permiso para esta operación. Comprueba tu rol activo.';
      case 404: return 'Pedido no disponible o no encontrado.';
      case 409: return 'El pedido ya no está en un estado cancelable. Actualiza el detalle.';
      default: return 'No se pudo completar la operación. Inténtalo de nuevo.';
    }
  }
}
