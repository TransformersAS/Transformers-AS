import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import { EvaluacionTienda, ResumenSuspension, TiendaConAntecedentes } from '../models/suspensiones.model';
import { EvaluacionTiendasService } from '../services/suspensiones.service';
import {
  CAMBIO, DECISION, ESTADO_ACLARACION, ESTADO_SUSPENSION, ESTADO_TIENDA, errorDeRespuesta, fechaHora
} from '../suspensiones-texto';

/** Acción abierta sobre la tienda elegida; cada una muestra su formulario. */
type Accion = 'aclaracion' | 'sinMedida' | 'suspender' | 'duracion' | 'finalizar';

/**
 * Evaluación de tiendas por el administrador (CU-27): lista de tiendas con antecedentes confirmados, sus antecedentes y
 * reincidencia, aclaraciones al vendedor, cierre sin medida y suspensiones temporales con resumen previo, cambio de
 * duración y finalización anticipada. Las reglas las aplica el backend; los errores se muestran junto al campo.
 */
@Component({
  selector: 'app-evaluacion-tiendas',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Evaluar tiendas</ion-title>
        <ion-buttons slot="end">
          @if (evaluacion) { <ion-button (click)="volver()">Volver a la lista</ion-button> }
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) { <p class="aviso error" role="alert">{{ error }}</p> }
      @if (aviso) { <p class="aviso exito" role="status">{{ aviso }}</p> }

      @if (!evaluacion) {
        <p class="nota">Tiendas con antecedentes confirmados: reclamaciones resueltas con responsabilidad del vendedor,
          decisiones de moderación que confirmaron un incumplimiento o suspensiones anteriores.</p>
        <ul class="tiendas" aria-label="Tiendas con antecedentes">
          @for (tienda of tiendas; track tienda.storeId) {
            <li>
              <button type="button" class="tienda" (click)="abrir(tienda.storeId)">
                <strong>{{ tienda.storeName }}</strong>
                <span>{{ ESTADO_TIENDA[tienda.storeStatus] }}</span>
                <span>{{ tienda.claims }} reclamación(es) · {{ tienda.moderations }} moderación(es) · {{ tienda.suspensions }} suspensión(es)</span>
                <span>{{ tienda.recentRecords }} reciente(s)@if (tienda.lastRecordAt) { · último {{ fechaHora(tienda.lastRecordAt) }} }</span>
                <span class="marcas">
                  @if (tienda.recidivist) { <em class="marca roja">Reincidente</em> }
                  @if (tienda.activeSuspension) { <em class="marca">Suspendida</em> }
                  @if (tienda.openClarification) { <em class="marca">Aclaración pendiente</em> }
                </span>
              </button>
            </li>
          } @empty {
            <li class="nota">Ninguna tienda tiene antecedentes confirmados.</li>
          }
        </ul>
      }

      @if (evaluacion; as datos) {
        <section aria-label="Situación de la tienda">
          <h2>{{ datos.storeName }}</h2>
          <p><strong>{{ ESTADO_TIENDA[datos.storeStatus] }}</strong>@if (datos.statusReason) { — {{ datos.statusReason }} }</p>
          <p class="nota">En curso: {{ datos.pending.ordersInProgress }} pedido(s), {{ datos.pending.openClaims }} reclamación(es)
            y {{ datos.pending.openReturns }} devolución(es). Seguirá atendiéndolos aunque se suspenda.</p>
          <p>{{ datos.recentRecords }} antecedente(s) en los últimos 180 días ·
            @if (datos.recidivist) { <strong class="roja">Reincidente</strong> } @else { Sin reincidencia }</p>
        </section>

        <h3>Antecedentes confirmados</h3>
        <ul class="lista">
          @for (registro of datos.records; track registro.type + registro.referenceId + registro.decision) {
            <li>
              <strong>{{ DECISION[registro.decision] }}</strong> · {{ fechaHora(registro.decidedAt) }}
              <br>{{ registro.subject }}@if (registro.amount) { · reembolso de {{ registro.amount }} }
              @if (registro.detail) { <br><span class="nota">{{ registro.detail }}</span> }
            </li>
          } @empty {
            <li class="nota">Sin reclamaciones ni decisiones de moderación confirmadas.</li>
          }
        </ul>

        @if (datos.activeSuspension; as vigente) {
          <section class="vigente" aria-label="Suspensión vigente">
            <h3>Suspensión vigente</h3>
            <p>Del {{ fechaHora(vigente.startsAt) }} al <strong>{{ fechaHora(vigente.endsAt) }}</strong>. Motivo: {{ vigente.reason }}</p>
            <button type="button" class="secundario" (click)="elegir('duracion')">Modificar duración</button>
            <button type="button" class="secundario" (click)="elegir('finalizar')">Finalizar anticipadamente</button>
          </section>
        } @else {
          <div class="acciones">
            <button type="button" class="secundario" (click)="elegir('aclaracion')">Pedir aclaración al vendedor</button>
            <button type="button" class="secundario" (click)="elegir('sinMedida')">Cerrar sin suspensión</button>
            <button type="button" class="principal" (click)="elegir('suspender')">Suspender temporalmente</button>
          </div>
        }

        @if (accion) {
          <form class="formulario" (ngSubmit)="enviar()" novalidate [attr.aria-label]="tituloAccion">
            <h3>{{ tituloAccion }}</h3>
            @if (accion === 'aclaracion') {
              <p class="nota">El vendedor tendrá 72 horas para responder; recibirá una notificación.</p>
              <label>Pregunta para el vendedor
                <textarea name="mensaje" rows="3" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['message']"
                  [attr.aria-describedby]="errores['message'] ? 'error-message' : null"></textarea>
              </label>
              @if (errores['message']) { <span class="error-campo" id="error-message">{{ errores['message'] }}</span> }
            }
            @if (accion === 'sinMedida') {
              <label>Conclusión de la revisión
                <textarea name="nota" rows="3" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['note']"
                  [attr.aria-describedby]="errores['note'] ? 'error-note' : null"></textarea>
              </label>
              @if (errores['note']) { <span class="error-campo" id="error-note">{{ errores['note'] }}</span> }
            }
            @if (accion === 'suspender' || accion === 'duracion') {
              @if (accion === 'suspender') {
                <label>Motivo de la suspensión
                  <textarea name="motivo" rows="3" maxlength="1000" [(ngModel)]="texto" (ngModelChange)="alEditar()"
                  [attr.aria-invalid]="!!errores['reason']"
                    [attr.aria-describedby]="errores['reason'] ? 'error-reason' : null"></textarea>
                </label>
                @if (errores['reason']) { <span class="error-campo" id="error-reason">{{ errores['reason'] }}</span> }
              }
              <label>{{ accion === 'duracion' ? 'Nueva duración total (días, desde el inicio)' : 'Duración (días)' }}
                <input type="number" name="dias" min="1" max="365" [(ngModel)]="dias" (ngModelChange)="alEditar()"
                  [attr.aria-invalid]="!!errores['durationDays']"
                  [attr.aria-describedby]="errores['durationDays'] ? 'error-durationDays' : null" />
              </label>
              @if (errores['durationDays']) { <span class="error-campo" id="error-durationDays">{{ errores['durationDays'] }}</span> }
              @if (accion === 'duracion') {
                <label>Justificación del cambio
                  <textarea name="justificacion" rows="2" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['justification']"
                    [attr.aria-describedby]="errores['justification'] ? 'error-justification' : null"></textarea>
                </label>
                @if (errores['justification']) { <span class="error-campo" id="error-justification">{{ errores['justification'] }}</span> }
              }
            }
            @if (accion === 'finalizar') {
              <label>Motivo para levantarla antes
                <textarea name="motivoFin" rows="2" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['reason']"
                  [attr.aria-describedby]="errores['reason'] ? 'error-reason-fin' : null"></textarea>
              </label>
              @if (errores['reason']) { <span class="error-campo" id="error-reason-fin">{{ errores['reason'] }}</span> }
            }

            @if (resumen; as r) {
              <div class="resumen" role="region" aria-label="Resumen de la suspensión">
                <p><strong>{{ r.storeName }}</strong> quedará suspendida {{ r.durationDays }} día(s): del
                  {{ fechaHora(r.startsAt) }} al {{ fechaHora(r.endsAt) }}.</p>
                @if (r.warning) { <p class="aviso">{{ r.warning }}</p> }
                <div class="efectos">
                  <div><h4>Se bloquea</h4><ul>@for (e of r.blocked; track e) { <li>{{ e }}</li> }</ul></div>
                  <div><h4>Sigue permitido</h4><ul>@for (e of r.allowed; track e) { <li>{{ e }}</li> }</ul></div>
                </div>
                <p class="nota">Deberá seguir atendiendo {{ r.pending.ordersInProgress }} pedido(s), {{ r.pending.openClaims }}
                  reclamación(es) y {{ r.pending.openReturns }} devolución(es) en curso.</p>
              </div>
            }

            <button type="submit" class="principal" [disabled]="enviando">{{ textoEnviar }}</button>
            <button type="button" class="secundario" (click)="cancelar()">Cancelar</button>
          </form>
        }

        @if (datos.clarifications.length > 0) {
          <h3>Aclaraciones</h3>
          <ul class="lista">
            @for (aclaracion of datos.clarifications; track aclaracion.id) {
              <li>
                <strong>{{ ESTADO_ACLARACION[aclaracion.status] }}</strong> · pedida el {{ fechaHora(aclaracion.requestedAt) }},
                vence el {{ fechaHora(aclaracion.dueAt) }}
                <br>{{ aclaracion.message }}
                @if (aclaracion.response) { <br><em>Respuesta:</em> {{ aclaracion.response }} }
                @if (aclaracion.status === 'EXPIRED') { <br><span class="nota">Puedes continuar la evaluación con la información disponible.</span> }
              </li>
            }
          </ul>
        }

        @if (datos.suspensions.length > 0) {
          <h3>Suspensiones</h3>
          <ul class="lista">
            @for (suspension of datos.suspensions; track suspension.id) {
              <li>
                <strong>{{ ESTADO_SUSPENSION[suspension.status] }}</strong> · {{ fechaHora(suspension.startsAt) }} a
                {{ fechaHora(suspension.closedAt ?? suspension.endsAt) }} — {{ suspension.reason }}
                <ul>
                  @for (cambio of suspension.changes; track $index) {
                    <li>{{ CAMBIO[cambio.type] }} el {{ fechaHora(cambio.createdAt) }}
                      @if (cambio.actorId) { por la cuenta #{{ cambio.actorId }} } @else { por el sistema }
                      @if (cambio.justification && cambio.type !== 'APPLIED') { : {{ cambio.justification }} }
                      @if (cambio.reactivationBlocked) { <strong> — no se reactivó: otra restricción vigente</strong> }
                    </li>
                  }
                </ul>
              </li>
            }
          </ul>
        }

        @if (datos.reviews.length > 0) {
          <h3>Revisiones</h3>
          <ul class="lista">
            @for (revision of datos.reviews; track revision.id) {
              <li>{{ fechaHora(revision.createdAt) }} · {{ revision.decision === 'NO_ACTION' ? 'Sin medida' : 'Suspensión' }}
                · {{ revision.note }}</li>
            }
          </ul>
        }
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .tiendas { list-style: none; padding: 0; display: grid; gap: 0.6rem; }
    button.tienda { width: 100%; text-align: left; display: flex; flex-direction: column; gap: 0.15rem; background: #fff;
      border: 1px solid #ccc; border-radius: 0.5rem; padding: 0.6rem 0.8rem; font: inherit; cursor: pointer; }
    button.tienda:hover, button.tienda:focus-visible { border-color: var(--ion-color-primary); }
    .marcas { display: flex; gap: 0.4rem; flex-wrap: wrap; }
    .marca { font-style: normal; font-size: 0.75rem; background: #eceff1; border-radius: 1rem; padding: 0.1rem 0.5rem; }
    .marca.roja, .roja { color: #b3261e; }
    .lista li { margin-bottom: 0.5rem; }
    .vigente { border: 1px solid #ffcdd2; background: #fff5f5; border-radius: 0.5rem; padding: 0.6rem 0.8rem; }
    .acciones { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0.75rem 0; }
    .formulario { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 0.75rem 1rem; margin: 0.75rem 0;
      display: flex; flex-direction: column; gap: 0.4rem; }
    label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.9rem; }
    textarea, input { font: inherit; padding: 0.4rem; border: 1px solid #aaa; border-radius: 0.3rem; }
    input[type=number] { max-width: 8rem; }
    [aria-invalid=true] { border-color: var(--ion-color-danger); }
    .error-campo { color: var(--ion-color-danger); font-size: 0.8rem; }
    .resumen { background: #f5f7fa; border-radius: 0.4rem; padding: 0.5rem 0.75rem; }
    .efectos { display: grid; gap: 0.5rem; grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr)); }
    h4 { margin: 0.4rem 0 0.2rem; font-size: 0.9rem; }
    button.principal, button.secundario { font: inherit; border-radius: 0.3rem; padding: 0.45rem 0.9rem; cursor: pointer;
      align-self: flex-start; }
    button.principal { background: var(--ion-color-primary); color: #fff; border: none; }
    button.secundario { background: transparent; border: 1px solid var(--ion-color-primary); color: var(--ion-color-primary); }
    button:disabled { opacity: 0.5; }
    .aviso { padding: 0.5rem 0.75rem; border-radius: 0.3rem; background: #fff8e1; }
    .aviso.error { background: #fdecea; } .aviso.exito { background: #e8f5e9; }
    .nota { color: #666; font-size: 0.9rem; }
  `]
})
export class EvaluacionTiendasComponent implements OnInit {
  private readonly servicio = inject(EvaluacionTiendasService);

  @Output() cerrar = new EventEmitter<void>();

  readonly ESTADO_TIENDA = ESTADO_TIENDA;
  readonly ESTADO_SUSPENSION = ESTADO_SUSPENSION;
  readonly ESTADO_ACLARACION = ESTADO_ACLARACION;
  readonly CAMBIO = CAMBIO;
  readonly DECISION = DECISION;
  readonly fechaHora = fechaHora;

  tiendas: TiendaConAntecedentes[] = [];
  evaluacion: EvaluacionTienda | null = null;
  accion: Accion | null = null;
  texto = '';
  dias: number | null = 7;
  /** Resumen de la suspensión: se muestra antes de confirmarla (paso 12). */
  resumen: ResumenSuspension | null = null;
  errores: Record<string, string> = {};
  error = '';
  aviso = '';
  enviando = false;

  ngOnInit(): void {
    this.listar();
  }

  listar(): void {
    this.servicio.listar().subscribe({
      next: tiendas => (this.tiendas = tiendas),
      error: (respuesta: HttpErrorResponse) => (this.error = errorDeRespuesta(respuesta).mensaje)
    });
  }

  abrir(tiendaId: number): void {
    this.limpiar();
    this.servicio.detalle(tiendaId).subscribe({
      next: evaluacion => (this.evaluacion = evaluacion),
      error: (respuesta: HttpErrorResponse) => (this.error = errorDeRespuesta(respuesta).mensaje)
    });
  }

  volver(): void {
    this.evaluacion = null;
    this.limpiar();
    this.listar();
  }

  elegir(accion: Accion): void {
    this.limpiar();
    this.accion = accion;
    this.dias = accion === 'duracion' ? null : 7;
  }

  cancelar(): void {
    this.accion = null;
    this.resumen = null;
    this.errores = {};
  }

  get tituloAccion(): string {
    switch (this.accion) {
      case 'aclaracion': return 'Pedir aclaración al vendedor';
      case 'sinMedida': return 'Cerrar la revisión sin suspensión';
      case 'suspender': return 'Suspender temporalmente';
      case 'duracion': return 'Modificar la duración';
      default: return 'Finalizar anticipadamente';
    }
  }

  get textoEnviar(): string {
    if (this.accion === 'suspender') {
      return this.resumen ? 'Confirmar suspensión' : 'Ver resumen';
    }
    return this.accion === 'aclaracion' ? 'Enviar solicitud' : 'Guardar';
  }

  enviar(): void {
    const tienda = this.evaluacion;
    if (!tienda || !this.accion) {
      return;
    }
    const dias = this.dias === null || String(this.dias) === '' ? null : Number(this.dias);
    const vigente = tienda.activeSuspension;
    switch (this.accion) {
      case 'aclaracion':
        this.ejecutar(this.servicio.pedirAclaracion(tienda.storeId, this.texto), 'Se pidió la aclaración al vendedor.');
        break;
      case 'sinMedida':
        this.ejecutar(this.servicio.registrarSinMedida(tienda.storeId, this.texto),
          'La revisión quedó registrada y la tienda sigue operando.');
        break;
      case 'suspender':
        if (this.resumen) {
          this.ejecutar(this.servicio.suspender(tienda.storeId, this.texto, dias), 'La tienda quedó suspendida.');
        } else {
          this.pedirResumen(tienda.storeId, dias);
        }
        break;
      case 'duracion':
        if (vigente) {
          this.ejecutar(this.servicio.cambiarDuracion(vigente.id, dias, this.texto), 'Se cambió la duración.');
        }
        break;
      case 'finalizar':
        if (vigente) {
          this.ejecutar(this.servicio.finalizar(vigente.id, this.texto), '', resultado => resultado.reactivated
            ? 'La suspensión terminó y la tienda volvió a operar.'
            : 'La suspensión terminó, pero la tienda no se reactivó porque tiene otra restricción vigente.');
        }
        break;
    }
  }

  /** Cualquier cambio en el motivo o la duración invalida el resumen ya mostrado: hay que volver a verlo. */
  alEditar(): void {
    this.resumen = null;
  }

  private pedirResumen(tiendaId: number, dias: number | null): void {
    this.errores = {};
    this.enviando = true;
    this.servicio.resumen(tiendaId, this.texto, dias).subscribe({
      next: resumen => {
        this.enviando = false;
        this.resumen = resumen;
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        this.mostrarError(respuesta);
      }
    });
  }

  private ejecutar<T>(operacion: Observable<T>, mensaje: string, mensajeSegun?: (resultado: T) => string): void {
    this.errores = {};
    this.error = '';
    this.enviando = true;
    operacion.subscribe({
      next: resultado => {
        this.enviando = false;
        const tiendaId = this.evaluacion!.storeId;
        this.cancelar();
        this.texto = '';
        this.aviso = mensajeSegun ? mensajeSegun(resultado) : mensaje;
        this.servicio.detalle(tiendaId).subscribe(evaluacion => (this.evaluacion = evaluacion));
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        this.mostrarError(respuesta);
      }
    });
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    const { mensaje, campo } = errorDeRespuesta(respuesta);
    if (campo) {
      this.errores = { [campo]: mensaje };
    } else {
      this.error = mensaje;
    }
  }

  private limpiar(): void {
    this.accion = null;
    this.resumen = null;
    this.texto = '';
    this.errores = {};
    this.error = '';
    this.aviso = '';
  }
}
