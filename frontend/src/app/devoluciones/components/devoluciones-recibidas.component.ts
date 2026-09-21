import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
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
    IonItem,
    IonLabel,
    IonList,
    IonText,
    IonTitle,
    IonToolbar
} from '@ionic/angular/standalone';

import { SeguimientoLogisticoComponent } from '../../seguimiento/components/seguimiento-logistico.component';
import { CampoDevolucion, interpretarErrorDevolucion } from '../models/devolucion-errores';
import {
    COLOR_ESTADO,
    DetalleDevolucion,
    ETIQUETA_ESTADO,
    EstadoDevolucion,
    HORAS_PLAZO,
    ResumenDevolucion
} from '../models/devolucion.model';
import { DevolucionesService } from '../services/devoluciones.service';
import { LineaTiempoDevolucionComponent } from './linea-tiempo-devolucion.component';

const ESTADOS_FILTRO: EstadoDevolucion[] = ['REQUESTED', 'IN_REVIEW', 'INFO_REQUIRED', 'APPROVED', 'IN_INSPECTION',
    'REFUND_PENDING', 'REJECTED', 'FINISHED'];

type Accion = 'revisar' | 'informacion' | 'aprobar' | 'rechazar' | 'problema';

/**
 * «Devoluciones recibidas» (CU-19, vendedor): solo las de la tienda de la sesión. Revisar, pedir información (24 h),
 * aprobar o rechazar con justificación y, en la inspección, reportar un problema con su cuenta regresiva de 24 h.
 */
@Component({
    selector: 'app-devoluciones-recibidas',
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
        <ion-title>Devoluciones recibidas</ion-title>
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
        <label for="filtro-estado" class="filtro">Estado</label>
        <select id="filtro-estado" [ngModel]="filtro" (ngModelChange)="filtrar($event)">
          <option [ngValue]="null">Todos</option>
          @for (estado of estadosFiltro; track estado) {
            <option [ngValue]="estado">{{ etiqueta(estado) }}</option>
          }
        </select>

        @if (cargando) {
          <p class="nota">Cargando las devoluciones de tu tienda…</p>
        } @else if (devoluciones.length === 0 && !error) {
          <p class="nota">No hay devoluciones {{ filtro ? 'en ese estado' : 'para tu tienda' }}.</p>
        }
        <ion-list>
          @for (devolucion of devoluciones; track devolucion.id) {
            <ion-item button detail (click)="verDetalle(devolucion.id)">
              <ion-label>
                <h2>Devolución #{{ devolucion.id }} · {{ devolucion.productName }} × {{ devolucion.quantity }}</h2>
                <p>Pedido #{{ devolucion.orderId }} · {{ devolucion.reasonLabel }} · {{ devolucion.createdAt | date: 'medium' }}</p>
                @if (devolucion.sellerDecisionOverdue) {
                  <p class="atraso">Tu plazo para decidir venció.</p>
                }
                @if (devolucion.pickupBlocked) {
                  <p class="atraso">La recogida está bloqueada.</p>
                }
              </ion-label>
              @if (devolucion.sellerDecisionOverdue) {
                <ion-badge color="danger" slot="end">Atrasada</ion-badge>
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
          <p><strong>Reembolso:</strong> {{ d.refundAmount | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</p>
          <p><strong>Motivo:</strong> {{ d.reasonLabel }}</p>
          <p><strong>Descripción del comprador:</strong> {{ d.description }}</p>
          <p><strong>Solicitada:</strong> {{ d.createdAt | date: 'medium' }}</p>
          @if (d.origin === 'CLAIM') {
            <p class="nota">Esta devolución nació de una reclamación: el reembolso acordado ya está definido.</p>
          }
          @if (d.sellerDecisionOverdue) {
            <p class="atraso">Superaste el plazo para decidir esta devolución.</p>
          }
          @if (d.pickupBlocked) {
            <p class="atraso">La recogida está bloqueada: revisa el seguimiento.</p>
          }
          @if (d.decision) {
            <p><strong>Tu decisión</strong> ({{ d.decision.decidedAt | date: 'medium' }}): {{ d.decision.note ?? 'sin comentarios' }}</p>
          }
          @if (d.problemReported) {
            <p class="atraso">Reportaste un problema en la inspección: {{ d.problemDescription }}. El reembolso está detenido.</p>
          }
        </section>

        @if (d.evidences.length > 0) {
          <section class="tarjeta">
            <h3>Imágenes del comprador</h3>
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
          <section class="tarjeta">
            <p><strong>Información que pediste:</strong> {{ solicitud.message }}</p>
            @if (solicitud.status === 'ANSWERED') {
              <p><strong>Respuesta del comprador</strong> ({{ solicitud.respondedAt | date: 'medium' }}): {{ solicitud.responseText }}</p>
            } @else if (solicitud.expired) {
              <p class="nota">El comprador no respondió antes del {{ solicitud.dueAt | date: 'medium' }}.</p>
            } @else {
              <p class="plazo" role="status">Esperando al comprador: le quedan {{ horasRestantes(solicitud.dueAt) }} h de las
                {{ horasPlazo }} que tiene.</p>
            }
          </section>
        }

        @if (d.status === 'REQUESTED') {
          <section class="tarjeta">
            <h3>Empezar la revisión</h3>
            <ion-button (click)="ejecutar('revisar', d)" [disabled]="enviando">Iniciar revisión</ion-button>
          </section>
        }

        @if (d.status === 'IN_REVIEW') {
          <section class="tarjeta">
            <h3>Pedir más información al comprador</h3>
            <p class="nota">El comprador tendrá {{ horasPlazo }} horas para responder.</p>
            <label for="mensaje">Mensaje</label>
            <textarea id="mensaje" rows="3" maxlength="2000" [(ngModel)]="mensaje"
              [attr.aria-invalid]="errores['mensaje'] ? 'true' : null"
              [attr.aria-describedby]="errores['mensaje'] ? 'error-mensaje' : null"></textarea>
            @if (errores['mensaje']) {
              <p class="error" id="error-mensaje" role="alert">{{ errores['mensaje'] }}</p>
            }
            <ion-button fill="outline" (click)="ejecutar('informacion', d)" [disabled]="enviando">Pedir información</ion-button>
          </section>

          <section class="tarjeta">
            <h3>Decidir</h3>
            <label for="nota">Justificación (obligatoria al rechazar)</label>
            <textarea id="nota" rows="3" maxlength="2000" [(ngModel)]="nota"
              [attr.aria-invalid]="errores['nota'] ? 'true' : null"
              [attr.aria-describedby]="errores['nota'] ? 'error-nota' : null"></textarea>
            @if (errores['nota']) {
              <p class="error" id="error-nota" role="alert">{{ errores['nota'] }}</p>
            }
            <div class="acciones">
              <ion-button color="success" (click)="ejecutar('aprobar', d)" [disabled]="enviando">Aprobar</ion-button>
              <ion-button color="danger" (click)="ejecutar('rechazar', d)" [disabled]="enviando">Rechazar</ion-button>
            </div>
          </section>
        }

        @if (d.status === 'IN_INSPECTION' && !d.problemReported) {
          <section class="tarjeta advertencia">
            <h3>Inspección del producto</h3>
            @if (d.inspectionDueAt) {
              <p class="plazo" role="status">Te quedan {{ horasRestantes(d.inspectionDueAt) }} h de las {{ horasPlazo }} de
                inspección (vence el {{ d.inspectionDueAt | date: 'medium' }}). Si no reportas nada, el reembolso sigue su curso.</p>
            }
            <label for="problema">Describe el problema encontrado</label>
            <textarea id="problema" rows="3" maxlength="2000" [(ngModel)]="problema"
              [attr.aria-invalid]="errores['problema'] ? 'true' : null"
              [attr.aria-describedby]="errores['problema'] ? 'error-problema' : null"></textarea>
            @if (errores['problema']) {
              <p class="error" id="error-problema" role="alert">{{ errores['problema'] }}</p>
            }
            <ion-button color="warning" (click)="ejecutar('problema', d)" [disabled]="enviando">Reportar problema</ion-button>
          </section>
        }

        @if (d.returnMethodCode) {
          <section class="tarjeta">
            <h3>Seguimiento del envío de retorno</h3>
            <app-seguimiento-logistico tipo="devolucion" rol="vendedor" [id]="d.id"></app-seguimiento-logistico>
          </section>
        }

        @if (errorGeneral) {
          <ion-text color="danger"><p class="mensaje" role="alert">{{ errorGeneral }}</p></ion-text>
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
    .filtro { display: block; font-weight: 600; font-size: 0.9rem; margin-bottom: 0.25rem; }
    #filtro-estado { padding: 0.5rem; border: 1px solid #b7c0c8; border-radius: 0.5rem; font: inherit; margin-bottom: 0.75rem; }
    .tarjeta { margin: 0 0 1rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .tarjeta h2, .tarjeta h3 { margin: 0 0 0.5rem; }
    .tarjeta p { margin: 0.25rem 0; }
    .tarjeta.advertencia { border-color: #e0a800; background: #fff8e1; }
    .tarjeta label { display: block; margin-top: 0.5rem; font-weight: 600; font-size: 0.9rem; }
    .tarjeta textarea { width: 100%; box-sizing: border-box; padding: 0.5rem; border: 1px solid #b7c0c8;
      border-radius: 0.5rem; font: inherit; }
    .tarjeta [aria-invalid='true'] { border-color: #b3261e; }
    .error { margin: 0.15rem 0; color: #b3261e; font-size: 0.85rem; font-weight: 600; }
    .plazo { color: #7a5b00; font-weight: 600; }
    .acciones { display: flex; gap: 0.5rem; }
    .imagenes { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0; padding: 0; list-style: none; }
    .imagenes img { width: 6rem; height: 6rem; object-fit: cover; border: 1px solid #d8dee4; border-radius: 0.4rem; }
  `]
})
export class DevolucionesRecibidasComponent implements OnInit {
    private readonly servicio = inject(DevolucionesService);

    @Output() cerrar = new EventEmitter<void>();

    readonly horasPlazo = HORAS_PLAZO;
    readonly estadosFiltro = ESTADOS_FILTRO;

    devoluciones: ResumenDevolucion[] = [];
    detalle: DetalleDevolucion | null = null;
    filtro: EstadoDevolucion | null = null;
    cargando = false;
    cargandoDetalle = false;
    enviando = false;
    error: string | null = null;
    errorGeneral: string | null = null;
    aviso: string | null = null;
    mensaje = '';
    nota = '';
    problema = '';
    errores: Partial<Record<CampoDevolucion, string>> = {};

    ngOnInit(): void {
        this.cargarLista();
    }

    etiqueta(estado: EstadoDevolucion): string {
        return ETIQUETA_ESTADO[estado];
    }

    color(estado: EstadoDevolucion): string {
        return COLOR_ESTADO[estado];
    }

    urlEvidencia(id: number, ordinal: number): string {
        return this.servicio.urlEvidenciaVendedor(id, ordinal);
    }

    /** Horas que faltan para una fecha límite, sin bajar de 0. */
    horasRestantes(limite: string): number {
        return Math.max(0, Math.ceil((new Date(limite).getTime() - Date.now()) / 3_600_000));
    }

    filtrar(estado: EstadoDevolucion | null): void {
        this.filtro = estado;
        this.cargarLista();
    }

    verDetalle(id: number): void {
        this.limpiar();
        this.detalle = null;
        this.cargandoDetalle = true;
        this.servicio.detalleVendedor(id).pipe(finalize(() => (this.cargandoDetalle = false))).subscribe({
            next: (detalle) => (this.detalle = detalle),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorDevolucion(e, 'No se pudo cargar la devolución.').general
                    ?? 'No se pudo cargar la devolución.';
            }
        });
    }

    volver(): void {
        this.detalle = null;
        this.limpiar();
        this.cargarLista();
    }

    /** Valida lo que corresponde a cada acción antes de llamar a la API y deja el error junto a su campo. */
    ejecutar(accion: Accion, detalle: DetalleDevolucion): void {
        this.errores = {};
        this.errorGeneral = null;
        this.aviso = null;
        const id = detalle.id;
        let llamada: Observable<DetalleDevolucion>;
        let campoPorDefecto: CampoDevolucion | null = null;
        let exito: string;
        switch (accion) {
            case 'revisar':
                llamada = this.servicio.revisar(id);
                exito = 'Empezaste la revisión.';
                break;
            case 'informacion': {
                const texto = this.mensaje.trim();
                if (!texto) {
                    this.errores['mensaje'] = 'Escribe qué información necesitas.';
                    return;
                }
                llamada = this.servicio.pedirInformacion(id, texto);
                campoPorDefecto = 'mensaje';
                exito = `Se pidió la información. El comprador tiene ${HORAS_PLAZO} horas para responder.`;
                break;
            }
            case 'aprobar':
                llamada = this.servicio.aprobar(id, this.nota.trim() || null);
                campoPorDefecto = 'nota';
                exito = 'Aprobaste la devolución. El comprador ya puede elegir cómo enviarla.';
                break;
            case 'rechazar': {
                const justificacion = this.nota.trim();
                if (!justificacion) {
                    this.errores['nota'] = 'Explica por qué rechazas la devolución.';
                    return;
                }
                llamada = this.servicio.rechazar(id, justificacion);
                campoPorDefecto = 'nota';
                exito = 'Rechazaste la devolución.';
                break;
            }
            case 'problema': {
                const texto = this.problema.trim();
                if (!texto) {
                    this.errores['problema'] = 'Describe el problema que encontraste.';
                    return;
                }
                llamada = this.servicio.reportarProblema(id, texto);
                campoPorDefecto = 'problema';
                exito = 'Reportaste el problema. El reembolso queda detenido.';
                break;
            }
        }
        this.enviando = true;
        llamada.pipe(finalize(() => (this.enviando = false))).subscribe({
            next: (actualizado) => {
                this.detalle = actualizado;
                this.aviso = exito;
                this.mensaje = this.nota = this.problema = '';
            },
            error: (e: HttpErrorResponse) => {
                const interpretado = interpretarErrorDevolucion(e, 'No se pudo completar la acción.', campoPorDefecto);
                if (interpretado.campo) {
                    this.errores[interpretado.campo.nombre] = interpretado.campo.mensaje;
                } else {
                    this.errorGeneral = interpretado.general;
                }
                // Otro actor pudo cambiar el estado (plazo vencido, inspección cerrada): se muestra el estado real.
                if (e.status === 409 || e.status === 410) {
                    this.servicio.detalleVendedor(id).subscribe({ next: (d) => (this.detalle = d) });
                }
            }
        });
    }

    private limpiar(): void {
        this.error = null;
        this.errorGeneral = null;
        this.aviso = null;
        this.errores = {};
        this.mensaje = this.nota = this.problema = '';
    }

    private cargarLista(): void {
        this.error = null;
        this.cargando = true;
        this.servicio.recibidas(this.filtro).pipe(finalize(() => (this.cargando = false))).subscribe({
            next: (devoluciones) => (this.devoluciones = devoluciones),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorDevolucion(e, 'No se pudieron cargar las devoluciones.').general
                    ?? 'No se pudieron cargar las devoluciones.';
            }
        });
    }
}
