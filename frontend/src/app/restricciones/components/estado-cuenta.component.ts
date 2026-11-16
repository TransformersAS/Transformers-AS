import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import { EstadoCuenta, SolicitudInformacion } from '../models/restricciones.model';
import { EstadoCuentaService } from '../services/restricciones.service';
import { alcance, errorDeRespuesta, ESTADO_INFORMACION, ESTADO_RESTRICCION, fechaHora } from '../restricciones-texto';

/**
 * Estado de la propia cuenta (CU-22, postcondiciones 5 y 8): sus restricciones vigentes, sin la identidad del
 * administrador que las aplicó, y las solicitudes de información que puede responder dentro de las 72 horas.
 * Cualquier cuenta autenticada puede abrir esta pantalla, sin importar su rol.
 */
@Component({
  selector: 'app-estado-cuenta',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Mi cuenta</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) { <p class="aviso error" role="alert">{{ error }}</p> }
      @if (aviso) { <p class="aviso exito" role="status">{{ aviso }}</p> }

      @if (estado; as datos) {
        @if (datos.activeRestrictions.length > 0) {
          <h2>Restricciones vigentes</h2>
          @for (restriccion of datos.activeRestrictions; track restriccion.id) {
            <section class="restriccion" aria-label="Restricción vigente">
              <p><strong>{{ alcance(restriccion.role) }}</strong></p>
              @if (restriccion.permanent) {
                <p>Restricción permanente.</p>
              } @else {
                <p>Desde el {{ fechaHora(restriccion.startsAt) }} hasta el
                  <strong>{{ fechaHora(restriccion.endsAt) }}</strong>.</p>
              }
              <p><strong>Motivo:</strong> {{ restriccion.reason }}</p>
            </section>
          }
        } @else {
          <p class="estado-ok">Tu cuenta no tiene restricciones vigentes.</p>
        }

        <h3>Solicitudes de información de la administración</h3>
        @for (solicitud of datos.informationRequests; track solicitud.id) {
          <article class="solicitud">
            <p class="cabecera">{{ ESTADO_INFORMACION[solicitud.status] }} · pedida el {{ fechaHora(solicitud.requestedAt) }}
              @if (solicitud.status === 'OPEN') { · responde antes del <strong>{{ fechaHora(solicitud.dueAt) }}</strong> }</p>
            <p>{{ solicitud.message }}</p>
            @if (solicitud.response) {
              <p class="respuesta"><strong>Tu respuesta</strong> ({{ fechaHora(solicitud.respondedAt) }}): {{ solicitud.response }}</p>
            }
            @if (solicitud.status === 'OPEN') {
              <form (ngSubmit)="responder(solicitud)" novalidate>
                <label>Tu respuesta
                  <textarea name="respuesta-{{ solicitud.id }}" rows="3" maxlength="2000" [(ngModel)]="respuestas[solicitud.id]"
                    [attr.aria-invalid]="!!erroresRespuesta[solicitud.id]"
                    [attr.aria-describedby]="erroresRespuesta[solicitud.id] ? 'error-respuesta-' + solicitud.id : null"></textarea>
                </label>
                @if (erroresRespuesta[solicitud.id]) {
                  <span class="error-campo" id="error-respuesta-{{ solicitud.id }}">{{ erroresRespuesta[solicitud.id] }}</span>
                }
                <button type="submit" class="principal" [disabled]="enviando">Enviar respuesta</button>
              </form>
            }
          </article>
        } @empty {
          <p class="nota">No hay solicitudes de información pendientes ni anteriores.</p>
        }
      } @else if (!error) {
        <p class="nota" role="status">Cargando…</p>
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    /* Las utilidades CSS de Ionic (ion-padding) no se cargan globalmente: el margen se da con las variables. */
    ion-content { flex: 1; --padding-start: 1rem; --padding-end: 1rem; --padding-top: 1rem; --padding-bottom: 1rem; }
    .restriccion { border: 1px solid #ffcdd2; background: #fff5f5; border-radius: 0.5rem; padding: 0.6rem 0.8rem;
      margin-bottom: 0.75rem; }
    .estado-ok { color: #2e7d32; }
    .solicitud { border: 1px solid #ddd; border-radius: 0.5rem; padding: 0.6rem 0.8rem; margin-bottom: 0.75rem; }
    .cabecera { font-size: 0.85rem; color: #555; margin: 0; }
    .respuesta { background: #f5f5f5; padding: 0.4rem 0.6rem; border-radius: 0.3rem; }
    label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.9rem; }
    textarea { font: inherit; padding: 0.4rem; border: 1px solid #aaa; border-radius: 0.3rem; }
    textarea[aria-invalid=true] { border-color: var(--ion-color-danger); }
    .error-campo { color: var(--ion-color-danger); font-size: 0.8rem; display: block; }
    button { font: inherit; border-radius: 0.3rem; padding: 0.45rem 0.9rem; margin-top: 0.4rem; cursor: pointer; }
    button.principal { background: var(--ion-color-primary); color: #fff; border: none; }
    button:disabled { opacity: 0.5; }
    .aviso { padding: 0.5rem 0.75rem; border-radius: 0.3rem; }
    .aviso.error { background: #fdecea; } .aviso.exito { background: #e8f5e9; }
    .nota { color: #666; font-size: 0.9rem; }
  `]
})
export class EstadoCuentaComponent implements OnInit {
  private readonly servicio = inject(EstadoCuentaService);

  @Output() cerrar = new EventEmitter<void>();

  readonly ESTADO_RESTRICCION = ESTADO_RESTRICCION;
  readonly ESTADO_INFORMACION = ESTADO_INFORMACION;
  readonly alcance = alcance;
  readonly fechaHora = fechaHora;

  estado: EstadoCuenta | null = null;
  respuestas: Record<number, string> = {};
  erroresRespuesta: Record<number, string> = {};
  error = '';
  aviso = '';
  enviando = false;

  ngOnInit(): void {
    this.cargar();
  }

  cargar(): void {
    this.servicio.consultar().subscribe({
      next: estado => (this.estado = estado),
      error: (respuesta: HttpErrorResponse) => (this.error = errorDeRespuesta(respuesta).mensaje)
    });
  }

  responder(solicitud: SolicitudInformacion): void {
    const texto = (this.respuestas[solicitud.id] ?? '').trim();
    this.aviso = '';
    this.error = '';
    if (!texto) {
      this.erroresRespuesta = { ...this.erroresRespuesta, [solicitud.id]: 'Escribe tu respuesta antes de enviarla.' };
      return;
    }
    this.erroresRespuesta = {};
    this.enviando = true;
    this.servicio.responder(solicitud.id, texto).subscribe({
      next: () => {
        this.enviando = false;
        this.aviso = 'Tu respuesta quedó registrada para la administración.';
        this.cargar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        const { mensaje, campo } = errorDeRespuesta(respuesta);
        if (campo === 'response') {
          this.erroresRespuesta = { [solicitud.id]: mensaje };
        } else {
          this.error = mensaje;
          this.cargar();
        }
      }
    });
  }
}
