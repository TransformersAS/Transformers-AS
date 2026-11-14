import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import { Aclaracion, EstadoMiTienda } from '../models/suspensiones.model';
import { EstadoTiendaService } from '../services/suspensiones.service';
import { CAMBIO, ESTADO_ACLARACION, ESTADO_SUSPENSION, ESTADO_TIENDA, errorDeRespuesta, fechaHora } from '../suspensiones-texto';

/**
 * Estado de la tienda del vendedor (CU-27): si está suspendida, hasta cuándo y por qué; qué puede y qué no puede hacer
 * mientras tanto; y las aclaraciones que pide la administración, que responde aquí dentro de las 72 horas.
 */
@Component({
  selector: 'app-estado-tienda',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Estado de mi tienda</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) { <p class="aviso error" role="alert">{{ error }}</p> }
      @if (aviso) { <p class="aviso exito" role="status">{{ aviso }}</p> }

      @if (estado; as datos) {
        <section class="estado" [class.suspendida]="datos.status !== 'ACTIVE'" aria-label="Estado de la tienda">
          <h2>{{ ESTADO_TIENDA[datos.status] }}</h2>
          @if (datos.activeSuspension; as suspension) {
            <p>Suspendida desde el {{ fechaHora(suspension.startsAt) }} hasta el
              <strong>{{ fechaHora(suspension.endsAt) }}</strong>.</p>
            <p><strong>Motivo:</strong> {{ suspension.reason }}</p>
          } @else if (datos.status === 'RESTRICTED') {
            <p>{{ datos.statusReason }}</p>
          } @else {
            <p>Tu tienda opera con normalidad.</p>
          }
          @if (datos.status !== 'ACTIVE') {
            <div class="efectos">
              <div>
                <h3>Mientras tanto no puedes</h3>
                <ul>@for (efecto of datos.blocked; track efecto) { <li>{{ efecto }}</li> }</ul>
              </div>
              <div>
                <h3>Sí puedes</h3>
                <ul>@for (efecto of datos.allowed; track efecto) { <li>{{ efecto }}</li> }</ul>
              </div>
            </div>
          }
        </section>

        <h3>Aclaraciones de la administración</h3>
        @for (aclaracion of datos.clarifications; track aclaracion.id) {
          <article class="aclaracion">
            <p class="cabecera">{{ ESTADO_ACLARACION[aclaracion.status] }} · pedida el {{ fechaHora(aclaracion.requestedAt) }}
              @if (aclaracion.status === 'OPEN') { · responde antes del <strong>{{ fechaHora(aclaracion.dueAt) }}</strong> }</p>
            <p>{{ aclaracion.message }}</p>
            @if (aclaracion.response) {
              <p class="respuesta"><strong>Tu respuesta</strong> ({{ fechaHora(aclaracion.respondedAt) }}): {{ aclaracion.response }}</p>
            }
            @if (aclaracion.status === 'OPEN') {
              <form (ngSubmit)="responder(aclaracion)" novalidate>
                <label>Tu respuesta
                  <textarea name="respuesta-{{ aclaracion.id }}" rows="3" maxlength="2000" [(ngModel)]="respuestas[aclaracion.id]"
                    [attr.aria-invalid]="!!erroresRespuesta[aclaracion.id]"
                    [attr.aria-describedby]="erroresRespuesta[aclaracion.id] ? 'error-respuesta-' + aclaracion.id : null"></textarea>
                </label>
                @if (erroresRespuesta[aclaracion.id]) {
                  <span class="error-campo" id="error-respuesta-{{ aclaracion.id }}">{{ erroresRespuesta[aclaracion.id] }}</span>
                }
                <button type="submit" class="principal" [disabled]="enviando">Enviar respuesta</button>
              </form>
            }
          </article>
        } @empty {
          <p class="nota">No hay aclaraciones pendientes ni anteriores.</p>
        }

        @if (datos.suspensions.length > 0) {
          <h3>Historial de suspensiones</h3>
          <ul class="historial">
            @for (suspension of datos.suspensions; track suspension.id) {
              <li>
                <strong>{{ ESTADO_SUSPENSION[suspension.status] }}</strong>: {{ fechaHora(suspension.startsAt) }} a
                {{ fechaHora(suspension.closedAt ?? suspension.endsAt) }} — {{ suspension.reason }}
                <ul>
                  @for (cambio of suspension.changes; track $index) {
                    <li>{{ CAMBIO[cambio.type] }} el {{ fechaHora(cambio.createdAt) }}@if (cambio.justification && cambio.type !== 'APPLIED') { : {{ cambio.justification }} }</li>
                  }
                </ul>
              </li>
            }
          </ul>
        }
      } @else if (!error) {
        <p class="nota" role="status">Cargando…</p>
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .estado { border: 1px solid #c8e6c9; background: #f1f8e9; border-radius: 0.5rem; padding: 0.75rem 1rem; margin-bottom: 1rem; }
    .estado.suspendida { border-color: #ffcdd2; background: #fff5f5; }
    .estado h2 { margin: 0 0 0.25rem; font-size: 1.15rem; }
    .efectos { display: grid; gap: 0.75rem; grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr)); }
    .efectos h3 { font-size: 0.95rem; margin: 0.5rem 0 0.25rem; }
    .aclaracion { border: 1px solid #ddd; border-radius: 0.5rem; padding: 0.6rem 0.8rem; margin-bottom: 0.75rem; }
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
    .historial li { margin-bottom: 0.4rem; }
  `]
})
export class EstadoTiendaComponent implements OnInit {
  private readonly servicio = inject(EstadoTiendaService);

  @Output() cerrar = new EventEmitter<void>();

  readonly ESTADO_TIENDA = ESTADO_TIENDA;
  readonly ESTADO_SUSPENSION = ESTADO_SUSPENSION;
  readonly ESTADO_ACLARACION = ESTADO_ACLARACION;
  readonly CAMBIO = CAMBIO;
  readonly fechaHora = fechaHora;

  estado: EstadoMiTienda | null = null;
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

  responder(aclaracion: Aclaracion): void {
    const texto = (this.respuestas[aclaracion.id] ?? '').trim();
    this.aviso = '';
    this.error = '';
    if (!texto) {
      this.erroresRespuesta = { ...this.erroresRespuesta, [aclaracion.id]: 'Escribe tu respuesta antes de enviarla.' };
      return;
    }
    this.erroresRespuesta = {};
    this.enviando = true;
    this.servicio.responder(aclaracion.id, texto).subscribe({
      next: () => {
        this.enviando = false;
        this.aviso = 'Tu respuesta quedó registrada para la administración.';
        this.cargar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        const { mensaje, campo } = errorDeRespuesta(respuesta);
        if (campo === 'response') {
          this.erroresRespuesta = { [aclaracion.id]: mensaje };
        } else {
          this.error = mensaje;
          this.cargar();
        }
      }
    });
  }
}
