import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
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

import { interpretarErrorReporte } from '../models/reporte-errores';
import {
    ETIQUETA_ESTADO,
    ETIQUETA_RESULTADO,
    ETIQUETA_TIPO,
    EstadoReporte,
    HORAS_PLAZO_RESPUESTA,
    ReporteDetalle,
    ReporteResumen,
    SolicitudInformacion
} from '../models/reporte.model';
import { ReportesService } from '../services/reportes.service';

const COLOR_ESTADO: Record<EstadoReporte, string> = {
    PENDIENTE: 'medium',
    EN_REVISION: 'primary',
    ESPERANDO_INFORMACION: 'warning',
    RESUELTO: 'success'
};

/**
 * "Mis reportes" (CU-20): los reportes que el usuario radicó, su estado, las solicitudes de información del agente y,
 * al resolverse, solo el resultado sobre el contenido. Nunca se muestra quién atendió el caso ni su justificación.
 */
@Component({
    selector: 'app-mis-reportes',
    standalone: true,
    imports: [DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonItem, IonLabel,
        IonList, IonText, IonTitle, IonToolbar],
    template: `
    <ion-header>
      <ion-toolbar color="primary">
        @if (detalle || cargandoDetalle) {
          <ion-buttons slot="start">
            <ion-button (click)="volver()">Volver</ion-button>
          </ion-buttons>
        }
        <ion-title>Mis reportes</ion-title>
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
          <p class="nota">Cargando tus reportes…</p>
        } @else if (reportes.length === 0 && !error) {
          <p class="nota">Todavía no has reportado ningún contenido. Puedes hacerlo con el botón «Reportar» de una
            publicación.</p>
        }
        <ion-list>
          @for (reporte of reportes; track reporte.id) {
            <ion-item button detail (click)="verDetalle(reporte.id)">
              <ion-label>
                <h2>Reporte #{{ reporte.id }} · {{ etiquetaTipo(reporte.contentType) }} {{ reporte.contentId }}</h2>
                <p>{{ reporte.reasonLabel }} · {{ reporte.createdAt | date: 'medium' }}</p>
                @if (reporte.medidaProvisional) {
                  <p class="nota">El contenido está oculto por precaución mientras se revisa.</p>
                }
              </ion-label>
              @if (reporte.awaitingYourResponse) {
                <ion-badge color="danger" slot="end">Requiere tu respuesta</ion-badge>
              }
              <ion-badge [color]="color(reporte.status)" slot="end">{{ etiqueta(reporte.status) }}</ion-badge>
            </ion-item>
          }
        </ion-list>
      }

      @if (cargandoDetalle) {
        <p class="nota">Cargando el reporte…</p>
      }

      @if (detalle; as d) {
        <section class="tarjeta">
          <h2>Reporte #{{ d.id }}</h2>
          <p><ion-badge [color]="color(d.status)">{{ etiqueta(d.status) }}</ion-badge></p>
          <p><strong>Contenido:</strong> {{ etiquetaTipo(d.contentType) }} {{ d.contentId }}</p>
          <p><strong>Motivo:</strong> {{ d.reasonLabel }}</p>
          <p><strong>Radicado:</strong> {{ d.createdAt | date: 'medium' }}</p>
          <p><strong>Tu descripción:</strong> {{ d.description }}</p>
          @if (d.medidaProvisional) {
            <p class="nota">El contenido está oculto por precaución mientras se revisa el caso.</p>
          }
          @if (d.result) {
            <p class="resultado" role="status"><strong>Resultado:</strong> {{ etiquetaResultado(d.result) }}</p>
            <p class="nota">Resuelto el {{ d.resolvedAt | date: 'medium' }}.</p>
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

        @if (d.informationRequests.length > 0) {
          <h3>Solicitudes de información</h3>
        }
        @for (solicitud of d.informationRequests; track solicitud.id) {
          <section class="tarjeta" [class.advertencia]="solicitud.status === 'ABIERTA'">
            <p><strong>Solicitud del equipo de soporte:</strong> {{ solicitud.message }}</p>
            <p class="nota">Solicitada el {{ solicitud.requestedAt | date: 'medium' }}.</p>

            @if (solicitud.status === 'ABIERTA') {
              <p class="plazo" role="status">Tienes {{ horasRestantes(solicitud) }} h para responder: el plazo es de
                {{ horasPlazo }} horas y vence el {{ solicitud.dueAt | date: 'medium' }}.</p>
              <label [for]="'respuesta-' + solicitud.id">Tu respuesta</label>
              <textarea [id]="'respuesta-' + solicitud.id" rows="4" maxlength="4000"
                [(ngModel)]="respuestas[solicitud.id]" [attr.aria-invalid]="erroresRespuesta[solicitud.id] ? 'true' : null"
                [attr.aria-describedby]="erroresRespuesta[solicitud.id] ? 'error-respuesta-' + solicitud.id : null"></textarea>
              @if (erroresRespuesta[solicitud.id]) {
                <p class="error" [id]="'error-respuesta-' + solicitud.id" role="alert">
                  {{ erroresRespuesta[solicitud.id] }}</p>
              }
              <ion-button (click)="responder(d, solicitud)" [disabled]="enviando">
                {{ enviando ? 'Enviando…' : 'Enviar respuesta' }}
              </ion-button>
            } @else if (solicitud.status === 'RESPONDIDA') {
              <p><strong>Tu respuesta</strong> ({{ solicitud.respondedAt | date: 'medium' }}): {{ solicitud.responseText }}</p>
            } @else {
              <p class="nota">El plazo de {{ horasPlazo }} horas para responder venció el
                {{ solicitud.dueAt | date: 'medium' }}.</p>
            }
          </section>
        }
        @if (aviso) {
          <ion-text color="success"><p class="mensaje" role="status">{{ aviso }}</p></ion-text>
        }
      }
    </ion-content>
  `,
    styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .mensaje { margin: 0 0 0.75rem; font-weight: 600; }
    .nota { color: #666; font-size: 0.9rem; }
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
    .resultado { font-weight: 600; }
    .imagenes { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0; padding: 0; list-style: none; }
    .imagenes img { width: 6rem; height: 6rem; object-fit: cover; border: 1px solid #d8dee4; border-radius: 0.4rem; }
  `]
})
export class MisReportesComponent implements OnInit {
    private readonly servicio = inject(ReportesService);

    @Output() cerrar = new EventEmitter<void>();

    readonly horasPlazo = HORAS_PLAZO_RESPUESTA;

    reportes: ReporteResumen[] = [];
    detalle: ReporteDetalle | null = null;
    cargando = false;
    cargandoDetalle = false;
    enviando = false;
    error: string | null = null;
    aviso: string | null = null;
    respuestas: Record<number, string> = {};
    erroresRespuesta: Record<number, string> = {};

    ngOnInit(): void {
        this.cargarLista();
        const seleccionado = this.servicio.reporteSeleccionado();
        if (seleccionado !== null) {
            this.verDetalle(seleccionado);
        }
    }

    etiqueta(estado: EstadoReporte): string {
        return ETIQUETA_ESTADO[estado];
    }

    color(estado: EstadoReporte): string {
        return COLOR_ESTADO[estado];
    }

    etiquetaResultado(resultado: keyof typeof ETIQUETA_RESULTADO): string {
        return ETIQUETA_RESULTADO[resultado];
    }

    etiquetaTipo(tipo: string): string {
        return ETIQUETA_TIPO[tipo] ?? tipo;
    }

    urlEvidencia(reporteId: number, ordinal: number): string {
        return this.servicio.urlEvidencia(reporteId, ordinal);
    }

    /** Horas que faltan para el plazo de la solicitud, sin bajar de 0. */
    horasRestantes(solicitud: SolicitudInformacion): number {
        const ms = new Date(solicitud.dueAt).getTime() - Date.now();
        return Math.max(0, Math.ceil(ms / 3_600_000));
    }

    verDetalle(id: number): void {
        this.error = null;
        this.aviso = null;
        this.detalle = null;
        this.cargandoDetalle = true;
        this.servicio.detalle(id).pipe(finalize(() => (this.cargandoDetalle = false))).subscribe({
            next: (detalle) => (this.detalle = detalle),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorReporte(e, 'No se pudo cargar el reporte.').general
                    ?? 'No se pudo cargar el reporte.';
            }
        });
    }

    volver(): void {
        this.detalle = null;
        this.error = null;
        this.aviso = null;
        this.cargarLista();
    }

    responder(detalle: ReporteDetalle, solicitud: SolicitudInformacion): void {
        const texto = (this.respuestas[solicitud.id] ?? '').trim();
        this.aviso = null;
        delete this.erroresRespuesta[solicitud.id];
        if (!texto) {
            this.erroresRespuesta[solicitud.id] = 'Escribe tu respuesta.';
            return;
        }
        this.enviando = true;
        this.servicio.responder(detalle.id, solicitud.id, texto).pipe(finalize(() => (this.enviando = false)))
            .subscribe({
                next: () => {
                    this.aviso = 'Tu respuesta se envió al equipo de soporte.';
                    delete this.respuestas[solicitud.id];
                    this.recargarDetalle(detalle.id);
                },
                error: (e: HttpErrorResponse) => {
                    const interpretado = interpretarErrorReporte(e, 'No se pudo enviar la respuesta.');
                    this.erroresRespuesta[solicitud.id] = interpretado.campo?.mensaje ?? interpretado.general
                        ?? 'No se pudo enviar la respuesta.';
                    // Un plazo vencido o una solicitud ya respondida cambian lo que debe mostrarse.
                    if (e.status === 410 || e.status === 409) {
                        this.error = this.erroresRespuesta[solicitud.id];
                        this.recargarDetalle(detalle.id);
                    }
                }
            });
    }

    private recargarDetalle(id: number): void {
        this.servicio.detalle(id).subscribe({ next: (detalle) => (this.detalle = detalle) });
    }

    private cargarLista(): void {
        this.cargando = true;
        this.servicio.mios().pipe(finalize(() => (this.cargando = false))).subscribe({
            next: (reportes) => (this.reportes = reportes),
            error: (e: HttpErrorResponse) => {
                this.error = interpretarErrorReporte(e, 'No se pudieron cargar tus reportes.').general
                    ?? 'No se pudieron cargar tus reportes.';
            }
        });
    }
}
