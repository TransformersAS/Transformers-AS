import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import {
    IonBadge,
    IonButton,
    IonButtons,
    IonContent,
    IonHeader,
    IonItem,
    IonLabel,
    IonList,
    IonText,
    IonTitle,
    IonToolbar
} from '@ionic/angular/standalone';

import { SeguimientoLogisticoComponent } from '../../seguimiento/components/seguimiento-logistico.component';
import { interpretarErrorDevolucion } from '../models/devolucion-errores';
import {
    COLOR_ESTADO,
    DetalleDevolucion,
    ETIQUETA_ESTADO,
    EstadoDevolucion,
    HORAS_PLAZO,
    MetodoRetorno,
    ResumenDevolucion,
    SolicitudInformacionDevolucion
} from '../models/devolucion.model';
import { DevolucionesService } from '../services/devoluciones.service';
import { LineaTiempoDevolucionComponent } from './linea-tiempo-devolucion.component';

/**
 * «Mis devoluciones» (CU-19, comprador): la lista, el detalle con su línea de tiempo, responder a lo que el vendedor
 * pide (con las horas que quedan de las 24), elegir el método de retorno y el seguimiento logístico ya existente.
 */
@Component({
    selector: 'app-mis-devoluciones',
    standalone: true,
    imports: [CurrencyPipe, DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonItem,
        IonLabel, IonList, IonText, IonTitle, IonToolbar, LineaTiempoDevolucionComponent, SeguimientoLogisticoComponent],
    template: `
    <ion-header>
      <ion-toolbar color="primary">
        @if (detalle || cargandoDetalle) {
          <ion-buttons slot="start">
            <ion-button (click)="volver()">Volver</ion-button>
          </ion-buttons>
        }
        <ion-title>Mis devoluciones</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) {
        <ion-text color="danger"><p class="mensaje" role="alert">{{ error }}</p></ion-text>
      }

      @if (!detalle && !cargandoDetalle) {
        @if (cargando) {
          <p class="nota">Cargando tus devoluciones…</p>
        } @else if (devoluciones.length === 0 && !error) {
          <p class="nota">Todavía no has solicitado devoluciones. Puedes hacerlo con el botón «Devolver» en el detalle de
            un pedido entregado.</p>
        }
        <ion-list>
          @for (devolucion of devoluciones; track devolucion.id) {
            <ion-item button detail (click)="verDetalle(devolucion.id)">
              <ion-label>
                <h2>Devolución #{{ devolucion.id }} · {{ devolucion.productName }}</h2>
                <p>Pedido #{{ devolucion.orderId }} · {{ devolucion.reasonLabel }} · {{ devolucion.createdAt | date: 'medium' }}</p>
                @if (devolucion.sellerDecisionOverdue) {
                  <p class="atraso">El vendedor está tardando en responder.</p>
                }
                @if (devolucion.methodSelectionOverdue) {
                  <p class="atraso">Te falta elegir el método de retorno.</p>
                }
                @if (devolucion.pickupBlocked) {
                  <p class="atraso">La recogida está bloqueada: revisa el detalle.</p>
                }
              </ion-label>
              @if (devolucion.awaitingBuyerResponse) {
                <ion-badge color="danger" slot="end">Requiere tu respuesta</ion-badge>
              }
              <ion-badge [color]="color(devolucion.status)" slot="end">{{ etiqueta(devolucion.status) }}</ion-badge>
            </ion-item>
          }
        </ion-list>
      }

      @if (cargandoDetalle) {
        <p class="nota">Cargando la devolución…</p>
      }

      @if (detalle; as d) {
        <section class="tarjeta">
          <h2>Devolución #{{ d.id }}</h2>
          <p><ion-badge [color]="color(d.status)">{{ etiqueta(d.status) }}</ion-badge></p>
          <p><strong>Producto:</strong> {{ d.productName }} × {{ d.quantity }} (pedido #{{ d.orderId }})</p>
          <p><strong>Reembolso previsto:</strong> {{ d.refundAmount | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</p>
          <p><strong>Motivo:</strong> {{ d.reasonLabel }}</p>
          <p><strong>Tu descripción:</strong> {{ d.description }}</p>
          @if (d.origin === 'CLAIM') {
            <p class="nota">Esta devolución se abrió a partir de una reclamación.</p>
          }
          @if (d.decision) {
            <p><strong>Decisión del vendedor</strong> ({{ d.decision.decidedAt | date: 'medium' }}):
              {{ d.decision.note ?? 'sin comentarios' }}</p>
          }
          @if (d.sellerDecisionOverdue) {
            <p class="atraso">El vendedor superó su plazo para decidir. Puedes reclamar desde «Mis reclamaciones».</p>
          }
          @if (d.pickupBlocked) {
            <p class="atraso">La recogida no se pudo completar. Contacta a soporte o elige otro método si te lo ofrecen.</p>
          }
          @if (d.status === 'IN_INSPECTION' && d.inspectionDueAt) {
            <p class="nota">El vendedor inspecciona el producto hasta el {{ d.inspectionDueAt | date: 'medium' }}.</p>
          }
          @if (d.problemReported) {
            <p class="atraso">El vendedor reportó un problema en la inspección: {{ d.problemDescription }}. El reembolso queda
              detenido mientras se revisa.</p>
          }
        </section>

        @if (d.evidences.length > 0) {
          <section class="tarjeta">
            <h3>Tus imágenes</h3>
            <ul class="imagenes">
              @for (imagen of d.evidences; track imagen.ordinal) {
                <li>
                  <a [href]="urlEvidencia(d.id, imagen.ordinal)" target="_blank" rel="noopener">
                    <img [src]="urlEvidencia(d.id, imagen.ordinal)" [alt]="imagen.fileName">
                  </a>
                </li>
              }
            </ul>
          </section>
        }

        @for (solicitud of d.informationRequests; track solicitud.id) {
          <section class="tarjeta" [class.advertencia]="solicitud.status === 'OPEN' && !solicitud.expired">
            <p><strong>El vendedor pide información:</strong> {{ solicitud.message }}</p>
            <p class="nota">Solicitada el {{ solicitud.requestedAt | date: 'medium' }}.</p>

            @if (solicitud.status === 'OPEN' && !solicitud.expired) {
              <p class="plazo" role="status">Tienes {{ horasRestantes(solicitud) }} h para responder: el plazo es de
                {{ horasPlazo }} horas y vence el {{ solicitud.dueAt | date: 'medium' }}.</p>
              <label [for]="'respuesta-' + solicitud.id">Tu respuesta</label>
              <textarea [id]="'respuesta-' + solicitud.id" rows="4" maxlength="4000" [(ngModel)]="respuesta"
                [attr.aria-invalid]="errorRespuesta ? 'true' : null"
                [attr.aria-describedby]="errorRespuesta ? 'error-respuesta' : null"></textarea>
              @if (errorRespuesta) {
                <p class="error" id="error-respuesta" role="alert">{{ errorRespuesta }}</p>
              }
              <ion-button (click)="responder(d)" [disabled]="enviando">
                {{ enviando ? 'Enviando…' : 'Enviar respuesta' }}
              </ion-button>
            } @else if (solicitud.status === 'ANSWERED') {
              <p><strong>Tu respuesta</strong> ({{ solicitud.respondedAt | date: 'medium' }}): {{ solicitud.responseText }}</p>
            } @else {
              <p class="nota">El plazo de {{ horasPlazo }} horas para responder venció el {{ solicitud.dueAt | date: 'medium' }}.</p>
            }
          </section>
        }

        @if (d.status === 'APPROVED' && !d.returnMethodCode) {
          <section class="tarjeta advertencia">
            <h3>Elige cómo enviar el producto</h3>
            @if (d.methodSelectionOverdue) {
              <p class="atraso">Llevas demasiado tiempo sin elegir el método de retorno.</p>
            }
            @if (cargandoMetodos) {
              <p class="nota">Consultando los métodos disponibles…</p>
            }
            @if (errorMetodo) {
              <p class="error" id="error-metodo" role="alert">{{ errorMetodo }}</p>
            }
            @if (metodos.length > 0) {
              <fieldset [attr.aria-describedby]="errorMetodo ? 'error-metodo' : null">
                <legend>Método de retorno</legend>
                @for (metodo of metodos; track metodo.code) {
                  <label class="opcion">
                    <input type="radio" name="metodo" [value]="metodo.code" [(ngModel)]="metodoElegido">
                    {{ metodo.label }}
                  </label>
                }
              </fieldset>
              <ion-button (click)="elegirMetodo(d)" [disabled]="enviando">
                {{ enviando ? 'Enviando…' : 'Confirmar método' }}
              </ion-button>
            } @else if (!cargandoMetodos) {
              <ion-button fill="outline" (click)="cargarMetodos(d.id)">Reintentar</ion-button>
            }
          </section>
        }

        @if (d.returnMethodCode) {
          <section class="tarjeta">
            <h3>Seguimiento del envío de retorno</h3>
            <p class="nota">Método elegido: {{ d.returnMethodCode }}</p>
            <app-seguimiento-logistico tipo="devolucion" rol="comprador" [id]="d.id"></app-seguimiento-logistico>
          </section>
        }

        @if (aviso) {
          <ion-text color="success"><p class="mensaje" role="status">{{ aviso }}</p></ion-text>
        }

        <section class="tarjeta">
          <h3>Historial</h3>
          <app-linea-tiempo-devolucion [eventos]="d.timeline"></app-linea-tiempo-devolucion>
        </section>
      }
    </ion-content>
  `,
    styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .mensaje { margin: 0 0 0.75rem; font-weight: 600; }
    .nota { color: #666; font-size: 0.9rem; }
    .atraso { color: #b3261e; font-size: 0.9rem; font-weight: 600; margin: 0.25rem 0; }
    .tarjeta { margin: 0 0 1rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .tarjeta h2, .tarjeta h3 { margin: 0 0 0.5rem; }
    .tarjeta p { margin: 0.25rem 0; }
    .tarjeta.advertencia { border-color: #e0a800; background: #fff8e1; }
    .tarjeta label { display: block; margin-top: 0.5rem; font-weight: 600; font-size: 0.9rem; }
    .tarjeta label.opcion { font-weight: 400; }
    .tarjeta fieldset { border: 0; margin: 0; padding: 0; }
    .tarjeta textarea { width: 100%; box-sizing: border-box; padding: 0.5rem; border: 1px solid #b7c0c8;
      border-radius: 0.5rem; font: inherit; }
    .tarjeta [aria-invalid='true'] { border-color: #b3261e; }
    .error { margin: 0.15rem 0; color: #b3261e; font-size: 0.85rem; font-weight: 600; }
    .plazo { color: #7a5b00; font-weight: 600; }
    .imagenes { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0; padding: 0; list-style: none; }
    .imagenes img { width: 6rem; height: 6rem; object-fit: cover; border: 1px solid #d8dee4; border-radius: 0.4rem; }
  `]
})
export class MisDevolucionesComponent implements OnInit {
    private readonly servicio = inject(DevolucionesService);

    @Output() cerrar = new EventEmitter<void>();

    readonly horasPlazo = HORAS_PLAZO;

    devoluciones: ResumenDevolucion[] = [];
    detalle: DetalleDevolucion | null = null;
    cargando = false;
    cargandoDetalle = false;
    cargandoMetodos = false;
    enviando = false;
    error: string | null = null;
    aviso: string | null = null;
    respuesta = '';
    errorRespuesta: string | null = null;
    metodos: MetodoRetorno[] = [];
    metodoElegido = '';
    errorMetodo: string | null = null;

    ngOnInit(): void {
        this.cargarLista();
        const seleccionada = this.servicio.devolucionSeleccionada();
        if (seleccionada !== null) {
            this.verDetalle(seleccionada);
        }
    }

    etiqueta(estado: EstadoDevolucion): string {
        return ETIQUETA_ESTADO[estado];
    }

    color(estado: EstadoDevolucion): string {
        return COLOR_ESTADO[estado];
    }

    urlEvidencia(id: number, ordinal: number): string {
        return this.servicio.urlEvidenciaComprador(id, ordinal);
    }

    /** Horas que faltan para el plazo de la solicitud, sin bajar de 0. */
    horasRestantes(solicitud: SolicitudInformacionDevolucion): number {
        const ms = new Date(solicitud.dueAt).getTime() - Date.now();
        return Math.max(0, Math.ceil(ms / 3_600_000));
    }

    verDetalle(id: number): void {
        this.error = null;
        this.aviso = null;
        this.detalle = null;
        this.cargandoDetalle = true;
        this.servicio.detalle(id).pipe(finalize(() => (this.cargandoDetalle = false))).subscribe({
            next: (detalle) => this.mostrar(detalle),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorDevolucion(e, 'No se pudo cargar la devolución.').general
                    ?? 'No se pudo cargar la devolución.';
            }
        });
    }

    volver(): void {
        this.detalle = null;
        this.error = null;
        this.aviso = null;
        this.metodos = [];
        this.cargarLista();
    }

    responder(detalle: DetalleDevolucion): void {
        const texto = this.respuesta.trim();
        this.aviso = null;
        this.errorRespuesta = null;
        if (!texto) {
            this.errorRespuesta = 'Escribe tu respuesta.';
            return;
        }
        this.enviando = true;
        this.servicio.responder(detalle.id, texto).pipe(finalize(() => (this.enviando = false))).subscribe({
            next: (actualizado) => {
                this.respuesta = '';
                this.aviso = 'Tu respuesta se envió al vendedor.';
                this.mostrar(actualizado);
            },
            error: (e: HttpErrorResponse) => {
                const interpretado = interpretarErrorDevolucion(e, 'No se pudo enviar la respuesta.', 'respuesta');
                this.errorRespuesta = interpretado.campo?.mensaje ?? interpretado.general
                    ?? 'No se pudo enviar la respuesta.';
                // Un plazo vencido o una solicitud ya respondida cambian lo que debe mostrarse.
                if (e.status === 409 || e.status === 410) {
                    this.verDetalleSinBorrar(detalle.id);
                }
            }
        });
    }

    cargarMetodos(id: number): void {
        this.errorMetodo = null;
        this.cargandoMetodos = true;
        this.servicio.metodosDeRetorno(id).pipe(finalize(() => (this.cargandoMetodos = false))).subscribe({
            next: (metodos) => {
                this.metodos = metodos;
                if (metodos.length === 0) {
                    this.errorMetodo = 'Por ahora no hay métodos de retorno disponibles. Inténtalo más tarde.';
                }
            },
            error: (e: HttpErrorResponse) => {
                this.metodos = [];
                this.errorMetodo = interpretarErrorDevolucion(e, 'No se pudieron consultar los métodos de retorno.')
                    .general ?? 'No se pudieron consultar los métodos de retorno.';
            }
        });
    }

    elegirMetodo(detalle: DetalleDevolucion): void {
        this.errorMetodo = null;
        this.aviso = null;
        if (!this.metodoElegido) {
            this.errorMetodo = 'Elige un método de retorno.';
            return;
        }
        this.enviando = true;
        this.servicio.elegirMetodo(detalle.id, this.metodoElegido).pipe(finalize(() => (this.enviando = false)))
            .subscribe({
                next: (actualizado) => {
                    this.aviso = 'Elegiste el método de retorno. Sigue el envío más abajo.';
                    this.mostrar(actualizado);
                },
                error: (e: HttpErrorResponse) => {
                    const interpretado = interpretarErrorDevolucion(e, 'No se pudo elegir el método de retorno.', 'metodo');
                    // Logística caída (503): la devolución sigue aprobada y se puede reintentar sin duplicar nada.
                    this.errorMetodo = e.status === 503
                        ? 'El servicio de logística no está disponible por ahora. Tu devolución sigue aprobada: inténtalo de nuevo en unos minutos.'
                        : interpretado.campo?.mensaje ?? interpretado.general ?? 'No se pudo elegir el método de retorno.';
                    // Un método que ya no está disponible obliga a mirar la lista otra vez.
                    if (e.status === 409 || e.status === 422) {
                        this.metodoElegido = '';
                        this.cargarMetodos(detalle.id);
                    }
                }
            });
    }

    private mostrar(detalle: DetalleDevolucion): void {
        this.detalle = detalle;
        this.errorRespuesta = null;
        this.errorMetodo = null;
        this.metodos = [];
        this.metodoElegido = '';
        if (detalle.status === 'APPROVED' && !detalle.returnMethodCode) {
            this.cargarMetodos(detalle.id);
        }
    }

    private verDetalleSinBorrar(id: number): void {
        this.servicio.detalle(id).subscribe({ next: (detalle) => (this.detalle = detalle) });
    }

    private cargarLista(): void {
        this.cargando = true;
        this.servicio.mias().pipe(finalize(() => (this.cargando = false))).subscribe({
            next: (devoluciones) => (this.devoluciones = devoluciones),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorDevolucion(e, 'No se pudieron cargar tus devoluciones.').general
                    ?? 'No se pudieron cargar tus devoluciones.';
            }
        });
    }
}
