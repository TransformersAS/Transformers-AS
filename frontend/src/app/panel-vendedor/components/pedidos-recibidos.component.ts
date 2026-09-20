import { Component, EventEmitter, OnInit, Output, ViewChild, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import {
    IonBadge,
    IonButton,
    IonButtons,
    IonContent,
    IonHeader,
    IonInput,
    IonItem,
    IonLabel,
    IonList,
    IonListHeader,
    IonSelect,
    IonSelectOption,
    IonText,
    IonTextarea,
    IonTitle,
    IonToolbar
} from '@ionic/angular/standalone';

import { PedidosVendedorService } from '../services/pedidos-vendedor.service';
import {
    ErrorApi,
    EstadoPagoPedido,
    EstadoPedido,
    MotivoCancelacion,
    PedidoDetalle,
    PedidoResumen,
    TipoNovedad
} from '../models/pedido-vendedor.model';

type FiltroClave = 'POR_ATENDER' | 'LISTOS' | 'CANCELADOS' | 'SOLICITUDES' | 'TODOS';

const ETIQUETA_ESTADO: Record<EstadoPedido, string> = {
    CONFIRMED: 'Confirmado',
    IN_PREPARATION: 'En preparación',
    READY_FOR_DISPATCH: 'Listo para despacho',
    PICKED_UP: 'Recogido',
    IN_TRANSIT: 'En tránsito',
    DELIVERED: 'Entregado',
    DELIVERY_EXCEPTION: 'Novedad de entrega',
    DELIVERY_ATTEMPT_FAILED: 'Entrega fallida',
    RETURNED_TO_SELLER: 'Devuelto al vendedor',
    CANCELLED: 'Cancelado',
    CANCELLATION_REQUESTED: 'Cancelación solicitada'
};

const ETIQUETA_PAGO: Record<EstadoPagoPedido, string> = {
    APPROVED: 'Pago aprobado',
    REFUND_PENDING: 'Reembolso pendiente',
    REFUNDED: 'Reembolsado'
};

const ETIQUETA_NOVEDAD: Record<TipoNovedad, string> = {
    INVENTORY_INCONSISTENCY: 'Inconsistencia de inventario',
    DAMAGED_PRODUCT: 'Producto dañado',
    OTHER: 'Otra'
};

const ETIQUETA_ACTOR: Record<string, string> = {
    SELLER: 'Vendedor',
    BUYER: 'Comprador',
    SYSTEM: 'Sistema',
    LOGISTICS: 'Logística'
};

const TODOS_LOS_ESTADOS = Object.keys(ETIQUETA_ESTADO) as EstadoPedido[];

/**
 * Pedidos recibidos por la tienda (CU-23): lista con filtros, detalle y las acciones del vendedor (preparar,
 * registrar novedades, dejar listo para despacho, reintentar el envío y cancelar). Las reglas las decide siempre
 * el backend; aquí solo se ocultan las acciones que el estado del pedido no permite.
 */
@Component({
    selector: 'app-pedidos-recibidos',
    standalone: true,
    imports: [
        CurrencyPipe, DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonInput,
        IonItem, IonLabel, IonList, IonListHeader, IonSelect, IonSelectOption, IonText, IonTextarea, IonTitle,
        IonToolbar
    ],
    template: `
    <ion-header>
      <ion-toolbar color="primary">
        @if (detalle || cargandoDetalle) {
          <ion-buttons slot="start">
            <ion-button (click)="volver()">Volver</ion-button>
          </ion-buttons>
        }
        <ion-title>Pedidos recibidos</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) {
        <ion-text color="danger"><p class="mensaje" role="alert">{{ error }}</p></ion-text>
      }
      @if (aviso) {
        <ion-text color="success"><p class="mensaje" role="status">{{ aviso }}</p></ion-text>
      }

      @if (!detalle && !cargandoDetalle) {
        <div class="filtros">
          <ion-item>
            <ion-select label="Mostrar" [(ngModel)]="filtro" (ionChange)="cambiarFiltro()">
              <ion-select-option value="POR_ATENDER">Por atender</ion-select-option>
              <ion-select-option value="LISTOS">Listos para despacho</ion-select-option>
              <ion-select-option value="SOLICITUDES">Cancelación solicitada</ion-select-option>
              <ion-select-option value="CANCELADOS">Cancelados</ion-select-option>
              <ion-select-option value="TODOS">Todos</ion-select-option>
            </ion-select>
          </ion-item>
          <form class="buscar" (ngSubmit)="buscarPorNumero()">
            <ion-item>
              <ion-input label="N.º de pedido" labelPlacement="stacked" type="number" min="1" name="numero"
                [(ngModel)]="numeroBuscado" inputmode="numeric"></ion-input>
            </ion-item>
            <ion-button type="submit" fill="outline">Buscar</ion-button>
          </form>
        </div>

        <ion-list>
          @for (pedido of pedidos; track pedido.id) {
            <ion-item button detail (click)="verDetalle(pedido.id)">
              <ion-label>
                <h2>Pedido #{{ pedido.id }} · {{ pedido.total | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</h2>
                <p>{{ pedido.itemCount }} {{ pedido.itemCount === 1 ? 'ítem' : 'ítems' }} ·
                  {{ pedido.shippingMethod === 'EXPRESS' ? 'Envío exprés' : 'Envío estándar' }} ·
                  {{ pedido.createdAt | date: 'short' }}</p>
              </ion-label>
              <ion-badge slot="end" [color]="colorEstado(pedido.status)">{{ etiquetaEstado(pedido.status) }}</ion-badge>
            </ion-item>
          }
        </ion-list>

        @if (cargandoLista) {
          <p class="vacio">Cargando pedidos…</p>
        } @else if (pedidos.length === 0 && !error) {
          <p class="vacio">No hay pedidos para este filtro.</p>
        }

        @if (totalPaginas > 1) {
          <div class="paginacion">
            <ion-button fill="clear" [disabled]="pagina === 0 || cargandoLista" (click)="irAPagina(pagina - 1)">Anterior</ion-button>
            <span>Página {{ pagina + 1 }} de {{ totalPaginas }} · {{ totalElementos }} pedidos</span>
            <ion-button fill="clear" [disabled]="pagina + 1 >= totalPaginas || cargandoLista" (click)="irAPagina(pagina + 1)">Siguiente</ion-button>
          </div>
        }
      }

      @if (cargandoDetalle) {
        <p class="vacio">Cargando pedido…</p>
      }

      @if (detalle; as pedido) {
        <section class="tarjeta" aria-label="Resumen del pedido">
          <h2>Pedido #{{ pedido.id }}</h2>
          <p>
            <ion-badge [color]="colorEstado(pedido.status)">{{ etiquetaEstado(pedido.status) }}</ion-badge>
            <ion-badge color="light">{{ etiquetaPago(pedido.paymentStatus) }}</ion-badge>
          </p>
          <p><strong>Total:</strong> {{ pedido.total | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} ·
            {{ pedido.shippingMethod === 'EXPRESS' ? 'Envío exprés' : 'Envío estándar' }} ·
            {{ pedido.createdAt | date: 'medium' }}</p>
        </section>

        <section class="tarjeta" aria-label="Datos de entrega">
          <h3>Entrega</h3>
          <p>{{ pedido.delivery.recipientName }}</p>
          <p>{{ pedido.delivery.street }}, {{ pedido.delivery.city }}, {{ pedido.delivery.department }}
            @if (pedido.delivery.postalCode) { · {{ pedido.delivery.postalCode }} }</p>
          <p>Tel. {{ pedido.delivery.phone }}</p>
        </section>

        <ion-list>
          <ion-list-header>Productos</ion-list-header>
          @for (linea of pedido.items; track linea.productId) {
            <ion-item>
              <ion-label class="ion-text-wrap">
                <h3>{{ linea.quantity }} × {{ linea.name }}</h3>
                <p>{{ linea.unitPrice | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} c/u ·
                  subtotal {{ linea.subtotal | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</p>
                @if (linea.inventoryConsistent) {
                  <p>Stock actual: {{ linea.currentStock }}</p>
                } @else {
                  <ion-text color="danger"><p>Inconsistencia de inventario: {{ linea.currentStock === null ? 'el producto ya no existe' : 'el stock es negativo (' + linea.currentStock + ')' }}. No se puede preparar.</p></ion-text>
                }
              </ion-label>
            </ion-item>
          }
        </ion-list>

        @if (pedido.shipment; as envio) {
          <section class="tarjeta" aria-label="Envío">
            <h3>Envío</h3>
            <p><strong>Guía:</strong> {{ envio.trackingCode }} · {{ envio.status }}</p>
          </section>
        } @else if (pedido.status === 'READY_FOR_DISPATCH') {
          <section class="tarjeta advertencia" aria-label="Envío">
            <h3>Envío pendiente</h3>
            <p>El pedido está listo, pero todavía no se creó el envío con el proveedor logístico.</p>
          </section>
        }

        @if (pedido.openIssues.length > 0) {
          <ion-list>
            <ion-list-header>Novedades abiertas</ion-list-header>
            @for (novedad of pedido.openIssues; track novedad.id) {
              <ion-item>
                <ion-label class="ion-text-wrap">
                  <h3>{{ etiquetaNovedad(novedad.type) }}</h3>
                  <p>{{ novedad.description }}</p>
                  <p>{{ novedad.createdAt | date: 'short' }}</p>
                </ion-label>
                <ion-button slot="end" fill="outline" [disabled]="ocupado" (click)="resolverNovedad(novedad.id)">Resolver</ion-button>
              </ion-item>
            }
          </ion-list>
        }

        <section class="acciones" aria-label="Acciones">
          @if (pedido.status === 'CANCELLATION_REQUESTED') {
            <p class="nota">El comprador solicitó cancelar este pedido. Todavía no se puede preparar ni cancelar desde aquí.</p>
          }

          @if (pedido.status === 'CONFIRMED') {
            <ion-button [disabled]="ocupado" (click)="iniciarPreparacion()">Iniciar preparación</ion-button>
          }
          @if (pedido.status === 'IN_PREPARATION') {
            <ion-button color="success" [disabled]="ocupado || pedido.openIssues.length > 0" (click)="listoParaDespacho()">
              Listo para despacho
            </ion-button>
            @if (pedido.openIssues.length > 0) {
              <p class="nota">Resuelve las novedades abiertas antes de dejar el pedido listo.</p>
            }
          }
          @if (pedido.status === 'READY_FOR_DISPATCH' && !pedido.shipment) {
            <ion-button color="warning" [disabled]="ocupado" (click)="reintentarEnvio()">Reintentar envío</ion-button>
          }
          @if (permiteNovedad(pedido)) {
            <ion-button fill="outline" [disabled]="ocupado" (click)="mostrarNovedad = !mostrarNovedad; mostrarCancelar = false">
              Registrar novedad
            </ion-button>
          }
          @if (permiteCancelar(pedido)) {
            <ion-button fill="outline" color="danger" [disabled]="ocupado" (click)="mostrarCancelar = !mostrarCancelar; mostrarNovedad = false">
              No puedo cumplir el pedido
            </ion-button>
          }
        </section>

        @if (mostrarNovedad && permiteNovedad(pedido)) {
          <form class="formulario" (ngSubmit)="registrarNovedad()" aria-label="Registrar novedad">
            <ion-item>
              <ion-select label="Tipo" name="tipoNovedad" [(ngModel)]="tipoNovedad">
                <ion-select-option value="INVENTORY_INCONSISTENCY">Inconsistencia de inventario</ion-select-option>
                <ion-select-option value="DAMAGED_PRODUCT">Producto dañado</ion-select-option>
                <ion-select-option value="OTHER">Otra</ion-select-option>
              </ion-select>
            </ion-item>
            <ion-item>
              <ion-textarea label="Descripción" labelPlacement="stacked" name="descripcionNovedad" maxlength="1000"
                [(ngModel)]="descripcionNovedad" [counter]="true" rows="3"></ion-textarea>
            </ion-item>
            <ion-button type="submit" [disabled]="ocupado || !descripcionNovedad.trim()">Guardar novedad</ion-button>
          </form>
        }

        @if (mostrarCancelar && permiteCancelar(pedido)) {
          <form class="formulario" (ngSubmit)="cancelar()" aria-label="Cancelar pedido">
            <p class="nota">Se cancela el pedido completo (nunca hay despacho parcial), se repone el stock y se solicita el reembolso al comprador. No se puede deshacer.</p>
            <ion-item>
              <ion-select label="Motivo" name="motivo" [(ngModel)]="motivo">
                <ion-select-option value="OUT_OF_STOCK">Sin stock</ion-select-option>
                <ion-select-option value="PRODUCT_DAMAGED">Producto dañado</ion-select-option>
                <ion-select-option value="OTHER">Otro</ion-select-option>
              </ion-select>
            </ion-item>
            <ion-item>
              <ion-textarea label="Detalle {{ motivo === 'OTHER' ? '(obligatorio)' : '(opcional)' }}" labelPlacement="stacked"
                name="detalleCancelacion" maxlength="1000" [(ngModel)]="detalleCancelacion" [counter]="true" rows="3"></ion-textarea>
            </ion-item>
            <ion-button type="submit" color="danger"
              [disabled]="ocupado || (motivo === 'OTHER' && !detalleCancelacion.trim())">Confirmar cancelación</ion-button>
          </form>
        }

        <ion-list>
          <ion-list-header>Historial</ion-list-header>
          @for (evento of pedido.history; track $index) {
            <ion-item>
              <ion-label class="ion-text-wrap">
                <h3>{{ evento.fromStatus ? etiquetaEstado(evento.fromStatus) + ' → ' : '' }}{{ etiquetaEstado(evento.toStatus) }}</h3>
                <p>{{ etiquetaActor(evento.actorType) }} · {{ evento.createdAt | date: 'short' }}</p>
                @if (evento.reason) { <p>{{ evento.reason }}</p> }
              </ion-label>
            </ion-item>
          }
        </ion-list>
      }
    </ion-content>
  `,
    styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .mensaje { margin: 0 0 0.75rem; font-weight: 600; }
    .mensaje[role='alert'] { color: #b3261e; }
    .mensaje[role='status'] { color: #17692f; }
    .vacio, .nota { color: #666; font-size: 0.9rem; }
    .filtros .buscar { display: flex; align-items: flex-end; gap: 0.5rem; }
    .filtros .buscar ion-item { flex: 1; }
    .paginacion { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; font-size: 0.85rem; }
    .tarjeta { margin: 0 0 1rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .tarjeta h2, .tarjeta h3 { margin: 0 0 0.5rem; }
    .tarjeta p { margin: 0.25rem 0; }
    .tarjeta.advertencia { border-color: #e0a800; background: #fff8e1; }
    .acciones { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; margin: 1rem 0; }
    .formulario { margin: 0 0 1rem; padding: 0.75rem; border: 1px dashed #b7c0c8; border-radius: 0.75rem; }
  `]
})
export class PedidosRecibidosComponent implements OnInit {
    private readonly servicio = inject(PedidosVendedorService);

    @Output() cerrar = new EventEmitter<void>();
    @ViewChild(IonContent) private contenido?: IonContent;

    filtro: FiltroClave = 'POR_ATENDER';
    numeroBuscado: number | string | null = null;
    pedidos: PedidoResumen[] = [];
    pagina = 0;
    totalPaginas = 0;
    totalElementos = 0;
    cargandoLista = false;

    detalle: PedidoDetalle | null = null;
    cargandoDetalle = false;
    /** Evita acciones duplicadas mientras hay una petición en curso (el backend además es idempotente). */
    ocupado = false;

    error = '';
    aviso = '';

    mostrarNovedad = false;
    tipoNovedad: TipoNovedad = 'OTHER';
    descripcionNovedad = '';

    mostrarCancelar = false;
    motivo: MotivoCancelacion = 'OUT_OF_STOCK';
    detalleCancelacion = '';

    ngOnInit(): void {
        this.cargarLista();
    }

    // ---------- Lista ----------

    cambiarFiltro(): void {
        this.pagina = 0;
        this.cargarLista();
    }

    buscarPorNumero(): void {
        this.pagina = 0;
        this.cargarLista();
    }

    irAPagina(pagina: number): void {
        this.pagina = pagina;
        this.cargarLista();
    }

    cargarLista(): void {
        this.cargandoLista = true;
        this.error = '';
        const numero = Number(this.numeroBuscado);
        this.servicio.listar({
            estados: this.estadosDelFiltro(),
            pedidoId: Number.isInteger(numero) && numero > 0 ? numero : null,
            pagina: this.pagina
        }).pipe(finalize(() => (this.cargandoLista = false))).subscribe({
            next: respuesta => {
                this.pedidos = respuesta.content;
                this.totalPaginas = respuesta.totalPages;
                this.totalElementos = respuesta.totalElements;
            },
            error: (e: HttpErrorResponse) => {
                this.pedidos = [];
                this.totalPaginas = 0;
                this.error = this.mensajeError(e, 'No se pudieron cargar los pedidos.');
            }
        });
    }

    private estadosDelFiltro(): EstadoPedido[] | undefined {
        switch (this.filtro) {
            case 'LISTOS': return ['READY_FOR_DISPATCH'];
            case 'CANCELADOS': return ['CANCELLED'];
            case 'SOLICITUDES': return ['CANCELLATION_REQUESTED'];
            case 'TODOS': return TODOS_LOS_ESTADOS;
            default: return undefined;
        }
    }

    // ---------- Detalle ----------

    verDetalle(id: number): void {
        this.limpiarMensajes();
        this.cargandoDetalle = true;
        this.servicio.detalle(id).pipe(finalize(() => (this.cargandoDetalle = false))).subscribe({
            next: detalle => {
                this.detalle = detalle;
                this.reiniciarFormularios();
            },
            error: (e: HttpErrorResponse) => (this.error = this.mensajeError(e, 'No se pudo cargar el pedido.'))
        });
    }

    volver(): void {
        this.detalle = null;
        this.limpiarMensajes();
        this.cargarLista();
    }

    private recargarDetalle(): void {
        if (!this.detalle) {
            return;
        }
        this.servicio.detalle(this.detalle.id).subscribe({
            next: detalle => (this.detalle = detalle),
            error: (e: HttpErrorResponse) => (this.error = this.mensajeError(e, 'No se pudo actualizar el pedido.'))
        });
    }

    // ---------- Acciones ----------

    iniciarPreparacion(): void {
        this.ejecutar(id => this.servicio.iniciarPreparacion(id), () => 'Pedido en preparación.');
    }

    registrarNovedad(): void {
        const descripcion = this.descripcionNovedad.trim();
        if (!descripcion) {
            return;
        }
        this.ejecutar(id => this.servicio.registrarNovedad(id, this.tipoNovedad, descripcion), () => {
            this.reiniciarFormularios();
            return 'Novedad registrada.';
        });
    }

    resolverNovedad(novedadId: number): void {
        this.ejecutar(id => this.servicio.resolverNovedad(id, novedadId), () => 'Novedad resuelta.');
    }

    listoParaDespacho(): void {
        this.ejecutar(id => this.servicio.listoParaDespacho(id), resultado =>
            resultado.shipment.status === 'CREATED'
                ? `Pedido listo para despacho. Guía: ${resultado.shipment.trackingCode}.`
                : 'Pedido listo para despacho, pero el envío no se pudo crear con el proveedor'
                    + `${resultado.shipment.message ? ` (${resultado.shipment.message})` : ''}. Usa «Reintentar envío».`);
    }

    reintentarEnvio(): void {
        this.ejecutar(id => this.servicio.reintentarEnvio(id), resultado => `Envío creado. Guía: ${resultado.trackingCode}.`);
    }

    cancelar(): void {
        if (this.motivo === 'OTHER' && !this.detalleCancelacion.trim()) {
            return;
        }
        this.ejecutar(id => this.servicio.cancelar(id, this.motivo, this.detalleCancelacion), resultado => {
            this.reiniciarFormularios();
            return `Pedido cancelado. Reembolso: ${resultado.refund.message}`;
        });
    }

    /**
     * Ejecuta una acción sobre el pedido abierto y siempre vuelve a leerlo: si el backend rechazó la acción por un
     * cambio concurrente (409), el detalle mostrado queda al día en lugar de engañar al vendedor.
     */
    private ejecutar<T>(accion: (id: number) => Observable<T>, exito: (resultado: T) => string): void {
        if (!this.detalle || this.ocupado) {
            return;
        }
        this.limpiarMensajes();
        this.ocupado = true;
        accion(this.detalle.id).pipe(finalize(() => (this.ocupado = false))).subscribe({
            next: resultado => {
                this.aviso = exito(resultado);
                this.recargarDetalle();
                this.contenido?.scrollToTop(200);
            },
            error: (e: HttpErrorResponse) => {
                this.error = this.mensajeError(e, 'No se pudo completar la acción.');
                this.recargarDetalle();
                this.contenido?.scrollToTop(200);
            }
        });
    }

    // ---------- Reglas de visibilidad (el backend decide; esto solo evita ofrecer acciones inválidas) ----------

    permiteNovedad(pedido: PedidoDetalle): boolean {
        return pedido.status === 'CONFIRMED' || pedido.status === 'IN_PREPARATION';
    }

    permiteCancelar(pedido: PedidoDetalle): boolean {
        return (pedido.status === 'CONFIRMED' || pedido.status === 'IN_PREPARATION') && !pedido.shipment;
    }

    // ---------- Presentación ----------

    etiquetaEstado(estado: EstadoPedido): string {
        return ETIQUETA_ESTADO[estado] ?? estado;
    }

    etiquetaPago(estado: EstadoPagoPedido): string {
        return ETIQUETA_PAGO[estado] ?? estado;
    }

    etiquetaNovedad(tipo: TipoNovedad): string {
        return ETIQUETA_NOVEDAD[tipo] ?? tipo;
    }

    etiquetaActor(actor: string): string {
        return ETIQUETA_ACTOR[actor] ?? actor;
    }

    colorEstado(estado: EstadoPedido): string {
        switch (estado) {
            case 'CONFIRMED': return 'primary';
            case 'IN_PREPARATION': return 'warning';
            case 'READY_FOR_DISPATCH': return 'success';
            case 'CANCELLED': return 'medium';
            case 'CANCELLATION_REQUESTED': return 'danger';
            default: return 'tertiary';
        }
    }

    private reiniciarFormularios(): void {
        this.mostrarNovedad = false;
        this.tipoNovedad = 'OTHER';
        this.descripcionNovedad = '';
        this.mostrarCancelar = false;
        this.motivo = 'OUT_OF_STOCK';
        this.detalleCancelacion = '';
    }

    private limpiarMensajes(): void {
        this.error = '';
        this.aviso = '';
    }

    /** Muestra el mensaje en español del backend; sin conexión o sin permisos da una pista de qué hacer. */
    private mensajeError(e: HttpErrorResponse, porDefecto: string): string {
        if (e.status === 0) {
            return 'No se pudo conectar con el servidor.';
        }
        if (e.status === 401) {
            return 'Tu sesión no es válida. Inicia sesión con una cuenta de vendedor.';
        }
        if (e.status === 403) {
            return 'Para ver los pedidos debes tener el rol activo Vendedor.';
        }
        const cuerpo = e.error as ErrorApi | null;
        return cuerpo?.message || porDefecto;
    }
}
