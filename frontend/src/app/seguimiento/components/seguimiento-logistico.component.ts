import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription } from 'rxjs';

import { SeguimientoService } from '../services/seguimiento.service';
import {
    ETIQUETA_ESTADO_DEVOLUCION,
    ETIQUETA_ESTADO_ENVIO,
    ETIQUETA_EVENTO_DEVOLUCION,
    ETIQUETA_EVENTO_PEDIDO,
    ETIQUETA_RESULTADO_EVENTO,
    EventoSeguimiento,
    EvidenciaEntrega,
    ResultadoConsulta,
    RolConsulta,
    SeguimientoDevolucion,
    SeguimientoPedido,
    TipoSeguimiento
} from '../models/seguimiento.model';

interface EventoVista {
    id: number;
    etiqueta: string;
    fecha: string;
    lugar: string | null;
    descripcion: string | null;
    nota: string;
    fueraDeOrden: boolean;
}

interface Vista {
    estado: string;
    codigo: string | null;
    /** El pedido todavía no tiene envío creado: no hay nada que consultar ni que dar por finalizado. */
    sinEnvio: boolean;
    /** Mientras sea true el marketplace sigue consultando al servicio logístico. */
    activo: boolean;
    consultaFallida: boolean;
    detalle: string[];
    alerta: string | null;
    entrega: { fecha: string; evidencia: EvidenciaEntrega | null } | null;
    eventos: EventoVista[];
}

const MENSAJE_CONSULTA: Record<ResultadoConsulta, string> = {
    UPDATED: 'Seguimiento actualizado.',
    NO_CHANGES: 'El servicio logístico no informó novedades.',
    UNAVAILABLE: 'El servicio logístico no responde ahora. Se muestra el último seguimiento conocido.',
    THROTTLED: 'Acabas de actualizar. Espera unos segundos antes de volver a consultar.',
    NOT_TRACKED: 'Este seguimiento ya no se consulta.'
};

/** Cada cuánto se relee el seguimiento guardado mientras el envío sigue activo (no llama al servicio logístico). */
const RELECTURA_MS = 10_000;

/**
 * Seguimiento logístico de un pedido (CU-24) o de una devolución (CU-25) para su comprador o su tienda. Solo muestra:
 * los estados los informa el servicio logístico y nadie los cambia a mano; «Actualizar seguimiento» únicamente pide
 * al backend que lo vuelva a consultar. Conserva la secuencia histórica, marca lo recibido fuera de orden y, si el
 * servicio no responde, sigue mostrando el último seguimiento conocido.
 */
@Component({
    selector: 'app-seguimiento-logistico',
    standalone: true,
    imports: [DatePipe],
    template: `
    <section class="seguimiento" [attr.aria-label]="tipo === 'pedido' ? 'Seguimiento del envío' : 'Seguimiento de la devolución'">
      <h3>{{ tipo === 'pedido' ? 'Seguimiento del envío' : 'Seguimiento de la devolución #' + id }}</h3>

      @if (cargando && !vista) {
        <p role="status">Cargando seguimiento…</p>
      }
      @if (error) {
        <p class="error" role="alert">{{ error }}</p>
      }
      @if (aviso) {
        <p class="aviso" role="status">{{ aviso }}</p>
      }

      @if (vista; as v) {
        <p class="estado"><strong>{{ v.estado }}</strong>@if (v.codigo) { · Guía {{ v.codigo }} }</p>
        @for (linea of v.detalle; track linea) {
          <p class="nota">{{ linea }}</p>
        }
        @if (v.alerta) {
          <p class="alerta" role="alert">{{ v.alerta }}</p>
        }
        @if (v.consultaFallida) {
          <p class="alerta" role="status">
            El servicio logístico no respondió la última vez: se muestra el último seguimiento conocido y se volverá a consultar.
          </p>
        }
        @if (v.entrega; as entrega) {
          <p class="entrega">Entregado el {{ entrega.fecha | date: 'medium' }}
            @if (entrega.evidencia?.reference) { · Confirmación: {{ entrega.evidencia?.reference }} }</p>
        }

        @if (v.eventos.length === 0) {
          <p class="nota">Todavía no hay actualizaciones de transporte.</p>
        }
        <ol class="linea">
          @for (evento of v.eventos; track evento.id) {
            <li [class.fuera]="evento.fueraDeOrden">
              <strong>{{ evento.etiqueta }}</strong>
              <time>{{ evento.fecha | date: 'dd/MM/yyyy HH:mm:ss' }}</time>
              @if (evento.lugar) { <span> · {{ evento.lugar }}</span> }
              @if (evento.descripcion) { <p>{{ evento.descripcion }}</p> }
              @if (evento.nota) { <p class="nota">{{ evento.nota }}</p> }
            </li>
          }
        </ol>

        @if (v.activo) {
          <button type="button" [disabled]="actualizando" (click)="actualizar()">
            {{ actualizando ? 'Consultando…' : 'Actualizar seguimiento' }}
          </button>
        } @else if (!v.sinEnvio) {
          <p class="nota">El seguimiento finalizó: ya no se consulta al servicio logístico.</p>
        }
      }
    </section>
  `,
    styles: [`
    .seguimiento { margin: 1rem 0; padding: 0.75rem 1rem; border: 1px solid #d5e2e5; border-radius: 0.75rem; background: #fff; }
    h3 { margin: 0 0 0.5rem; }
    .estado { margin: 0.25rem 0; }
    .nota { color: #5d6b70; font-size: 0.85rem; margin: 0.2rem 0; }
    .error { color: #a32121; }
    .aviso { color: #176541; }
    .alerta { color: #7a4b00; background: #fff8e1; border: 1px solid #e0a800; border-radius: 0.5rem; padding: 0.4rem 0.6rem; }
    .entrega { color: #176541; font-weight: 600; }
    ol.linea { list-style: none; margin: 0.75rem 0; padding: 0 0 0 1rem; border-left: 3px solid #cfe3e6; }
    ol.linea li { position: relative; margin: 0 0 0.75rem; padding: 0.1rem 0 0.1rem 0.5rem; overflow-wrap: anywhere; }
    ol.linea li::before { content: ''; position: absolute; left: -1.45rem; top: 0.45rem; width: 0.7rem; height: 0.7rem; border-radius: 50%; background: #0a7f8f; }
    ol.linea li.fuera { opacity: 0.65; }
    ol.linea li.fuera::before { background: #b7c0c8; }
    ol.linea li p { margin: 0.15rem 0; }
    time { color: #5d6b70; font-size: 0.85rem; margin-left: 0.4rem; }
    button { cursor: pointer; border: 1px solid #d5e2e5; border-radius: 0.5rem; padding: 0.55rem 0.8rem; background: #f0f7f7; color: #073b4c; }
    button:disabled { opacity: 0.6; cursor: default; }
  `]
})
export class SeguimientoLogisticoComponent implements OnInit, OnChanges, OnDestroy {
    private readonly servicio = inject(SeguimientoService);

    @Input({ required: true }) tipo!: TipoSeguimiento;
    @Input({ required: true }) id!: number;
    @Input({ required: true }) rol!: RolConsulta;
    /** Se emite cuando el estado vigente cambió respecto de la última lectura, para que el detalle padre se actualice. */
    @Output() estadoCambio = new EventEmitter<string>();

    vista: Vista | null = null;
    cargando = false;
    actualizando = false;
    error = '';
    aviso = '';

    private ultimoEstado: string | null = null;
    private peticion = new Subscription();
    private temporizador: ReturnType<typeof setInterval> | null = null;

    ngOnInit(): void {
        this.temporizador = setInterval(() => {
            if (this.vista?.activo && !this.actualizando && !this.cargando) {
                this.cargar(true);
            }
        }, RELECTURA_MS);
    }

    ngOnChanges(cambios: SimpleChanges): void {
        if (cambios['id'] || cambios['tipo'] || cambios['rol']) {
            this.vista = null;
            this.ultimoEstado = null;
            this.error = this.aviso = '';
            this.cargar(false);
        }
    }

    ngOnDestroy(): void {
        this.peticion.unsubscribe();
        if (this.temporizador) {
            clearInterval(this.temporizador);
        }
    }

    /** Relee el seguimiento guardado (no consulta al servicio logístico). silencioso = relectura periódica. */
    cargar(silencioso: boolean): void {
        this.peticion.unsubscribe();
        this.peticion = new Subscription();
        if (!silencioso) {
            this.cargando = true;
        }
        this.peticion.add(this.consultar(false).subscribe({
            next: datos => this.mostrar(datos, silencioso),
            error: (e: HttpErrorResponse) => {
                this.cargando = false;
                if (!silencioso) {
                    this.error = this.mensajeError(e);
                }
            }
        }));
    }

    /** Pide al backend que vuelva a consultar al servicio logístico y muestra lo que devuelva. */
    actualizar(): void {
        if (this.actualizando) {
            return;
        }
        this.actualizando = true;
        this.error = this.aviso = '';
        this.peticion.add(this.consultar(true).subscribe({
            next: datos => {
                this.actualizando = false;
                this.mostrar(datos, false);
                const resultado = datos.refresh;
                this.aviso = resultado ? MENSAJE_CONSULTA[resultado] : '';
            },
            error: (e: HttpErrorResponse) => {
                this.actualizando = false;
                this.error = this.mensajeError(e);
            }
        }));
    }

    private consultar(actualizar: boolean): Observable<SeguimientoPedido | SeguimientoDevolucion> {
        return this.tipo === 'pedido'
            ? this.servicio.pedido(this.id, this.rol, actualizar)
            : this.servicio.devolucion(this.id, this.rol, actualizar);
    }

    private mostrar(datos: SeguimientoPedido | SeguimientoDevolucion, silencioso: boolean): void {
        this.cargando = false;
        if (!silencioso) {
            this.error = '';
        }
        this.vista = this.tipo === 'pedido'
            ? this.vistaPedido(datos as SeguimientoPedido)
            : this.vistaDevolucion(datos as SeguimientoDevolucion);
        if (this.ultimoEstado !== null && this.ultimoEstado !== datos.status) {
            this.estadoCambio.emit(datos.status);
        }
        this.ultimoEstado = datos.status;
    }

    private vistaPedido(datos: SeguimientoPedido): Vista {
        const sinEnvio = !datos.trackingCode;
        return {
            estado: ETIQUETA_ESTADO_ENVIO[datos.status] ?? datos.status,
            codigo: datos.trackingCode ?? null,
            sinEnvio,
            activo: !sinEnvio && datos.tracking,
            consultaFallida: datos.lastPollFailed,
            detalle: sinEnvio ? ['Este pedido todavía no tiene un envío creado con el servicio logístico.'] : [],
            alerta: null,
            entrega: datos.deliveredAt ? { fecha: datos.deliveredAt, evidencia: datos.deliveryEvidence ?? null } : null,
            eventos: datos.events.map(evento => this.eventoVista(evento, ETIQUETA_EVENTO_PEDIDO))
        };
    }

    private vistaDevolucion(datos: SeguimientoDevolucion): Vista {
        const detalle = [`Recogidas fallidas: ${datos.failedPickups} de ${datos.maxFailedPickups}.`];
        if (datos.canRequestNewPickup) {
            detalle.push('Si el servicio lo permite, puedes usar una nueva opción de recogida.');
        }
        const entregada = datos.status === 'DELIVERED_TO_SELLER';
        return {
            estado: ETIQUETA_ESTADO_DEVOLUCION[datos.status] ?? datos.status,
            codigo: datos.trackingCode,
            sinEnvio: false,
            activo: datos.tracking,
            consultaFallida: datos.lastPollFailed,
            detalle,
            alerta: datos.pickupStopped
                ? 'Se alcanzaron tres intentos de recogida fallidos: no se solicitarán más recogidas automáticas y el retorno no pudo continuar. El caso será gestionado.'
                : null,
            entrega: entregada && datos.deliveredAt
                ? { fecha: datos.deliveredAt, evidencia: datos.events.find(e => e.type === 'DELIVERED_TO_SELLER' && e.outcome === 'APPLIED')?.evidence ?? null }
                : null,
            eventos: datos.events.map(evento => this.eventoVista(evento, ETIQUETA_EVENTO_DEVOLUCION))
        };
    }

    private eventoVista(evento: EventoSeguimiento, etiquetas: Record<string, string>): EventoVista {
        return {
            id: evento.id,
            etiqueta: etiquetas[evento.type] ?? evento.type,
            fecha: evento.occurredAt,
            lugar: evento.location,
            descripcion: evento.description,
            nota: ETIQUETA_RESULTADO_EVENTO[evento.outcome] ?? '',
            fueraDeOrden: evento.outcome === 'OUT_OF_ORDER'
        };
    }

    private mensajeError(e: HttpErrorResponse): string {
        switch (e.status) {
            case 0: return 'No se pudo conectar con el servidor.';
            case 401: return 'Tu sesión no es válida. Inicia sesión de nuevo.';
            case 403: return 'No tienes permiso para ver este seguimiento. Comprueba tu rol activo.';
            case 404: return this.tipo === 'pedido' ? 'El pedido no existe.' : 'La devolución no existe o no es tuya.';
            default: return 'No se pudo cargar el seguimiento. Inténtalo de nuevo.';
        }
    }
}
