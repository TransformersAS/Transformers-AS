import { Component, EventEmitter, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import { EvaluacionCuenta, Restriccion, Rol } from '../models/restricciones.model';
import { AdminRestriccionesService } from '../services/restricciones.service';
import { alcance, CAMBIO_RESTRICCION, errorDeRespuesta, ESTADO_INFORMACION, ESTADO_RESTRICCION, fechaHora, ROL, TIPO_ANTECEDENTE } from '../restricciones-texto';

/** Acción abierta sobre la cuenta o sobre una restricción elegida; cada una muestra su formulario. */
type Accion = 'informacion' | 'restringir' | 'duracion' | 'permanente' | 'temporal' | 'retirar';

/**
 * Administrar cuentas y restricciones de acceso (CU-22): buscar una cuenta, revisar sus antecedentes confirmados y
 * sus restricciones, pedirle una aclaración y aplicar, modificar o retirar una restricción sobre toda la cuenta o
 * sobre un rol, temporal o permanente. Las reglas las aplica el backend; los errores se muestran junto al campo.
 */
@Component({
  selector: 'app-admin-restricciones',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Restricciones de cuentas</ion-title>
        <ion-buttons slot="end">
          @if (evaluacion) { <ion-button (click)="volver()">Nueva búsqueda</ion-button> }
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) { <p class="aviso error" role="alert">{{ error }}</p> }
      @if (aviso) { <p class="aviso exito" role="status">{{ aviso }}</p> }

      @if (!evaluacion) {
        <form class="formulario" (ngSubmit)="buscar()" novalidate>
          <label>Correo de la cuenta
            <input type="email" name="correo" [(ngModel)]="correo" required />
          </label>
          <button type="submit" class="principal" [disabled]="!correo.trim() || buscando">Buscar</button>
        </form>
      }

      @if (evaluacion; as datos) {
        <section aria-label="Situación de la cuenta">
          <h2>{{ datos.account.email }}</h2>
          <p>{{ datos.account.active ? 'Activa' : 'Inactiva' }} · roles: {{ rolesTexto(datos.account.roles) }}</p>
          <p>{{ datos.recentRecords }} antecedente(s) en los últimos 180 días ·
            @if (datos.recidivist) { <strong class="roja">Reincidente</strong> } @else { Sin reincidencia }</p>
        </section>

        <h3>Antecedentes</h3>
        <ul class="lista">
          @for (registro of datos.records; track registro.type + registro.referenceId + registro.decision) {
            <li>
              <strong>{{ TIPO_ANTECEDENTE[registro.type] }}</strong> · {{ fechaHora(registro.decidedAt) }}
              <br>{{ registro.subject }} — {{ registro.decision }}@if (registro.amount) { · reembolso de {{ registro.amount }} }
              @if (registro.detail) { <br><span class="nota">{{ registro.detail }}</span> }
            </li>
          } @empty {
            <li class="nota">Sin antecedentes confirmados.</li>
          }
        </ul>

        @if (datos.activeRestrictions.length > 0) {
          <h3>Restricciones vigentes</h3>
          @for (restriccion of datos.activeRestrictions; track restriccion.id) {
            <section class="vigente" aria-label="Restricción vigente">
              <p><strong>{{ alcance(restriccion.role) }}</strong>
                @if (restriccion.permanent) { · permanente } @else { · hasta {{ fechaHora(restriccion.endsAt) }} }</p>
              <p>Motivo: {{ restriccion.reason }}</p>
              <div class="acciones">
                @if (!restriccion.permanent) {
                  <button type="button" class="secundario" (click)="elegir('duracion', restriccion)">Cambiar duración</button>
                  <button type="button" class="secundario" (click)="elegir('permanente', restriccion)">Hacer permanente</button>
                } @else {
                  <button type="button" class="secundario" (click)="elegir('temporal', restriccion)">Hacer temporal</button>
                }
                <button type="button" class="secundario" (click)="elegir('retirar', restriccion)">Retirar anticipadamente</button>
              </div>
            </section>
          }
        }

        <div class="acciones">
          <button type="button" class="secundario" (click)="elegir('informacion')">Pedir aclaración</button>
          <button type="button" class="principal" (click)="elegir('restringir')">Aplicar restricción</button>
        </div>

        @if (accion) {
          <form class="formulario" (ngSubmit)="enviar()" novalidate [attr.aria-label]="tituloAccion">
            <h3>{{ tituloAccion }}</h3>

            @if (accion === 'informacion') {
              <p class="nota">La persona tendrá 72 horas para responder; recibirá una notificación.</p>
              <label>Pregunta para la cuenta
                <textarea name="mensaje" rows="3" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['message']"
                  [attr.aria-describedby]="errores['message'] ? 'error-message' : null"></textarea>
              </label>
              @if (errores['message']) { <span class="error-campo" id="error-message">{{ errores['message'] }}</span> }
            }

            @if (accion === 'restringir') {
              <label>Alcance
                <select name="alcance" [(ngModel)]="alcanceElegido">
                  <option value="ACCOUNT">Toda la cuenta</option>
                  <option value="ROLE">Un rol específico</option>
                </select>
              </label>
              @if (alcanceElegido === 'ROLE') {
                <label>Rol afectado
                  <select name="rol" [(ngModel)]="rolElegido">
                    @for (rol of datos.account.roles; track rol) {
                      <option [value]="rol">{{ ROL[rol] }}</option>
                    }
                  </select>
                </label>
                @if (errores['role']) { <span class="error-campo">{{ errores['role'] }}</span> }
              }
              <label>Motivo
                <textarea name="motivo" rows="3" maxlength="1000" [(ngModel)]="texto" [attr.aria-invalid]="!!errores['reason']"
                  [attr.aria-describedby]="errores['reason'] ? 'error-reason' : null"></textarea>
              </label>
              @if (errores['reason']) { <span class="error-campo" id="error-reason">{{ errores['reason'] }}</span> }
              <label class="checkbox"><input type="checkbox" name="permanente" [(ngModel)]="permanenteElegido" /> Permanente</label>
              @if (!permanenteElegido) {
                <label>Duración (días)
                  <input type="number" name="dias" min="1" [(ngModel)]="dias"
                    [attr.aria-invalid]="!!errores['durationDays']"
                    [attr.aria-describedby]="errores['durationDays'] ? 'error-durationDays' : null" />
                </label>
                @if (errores['durationDays']) { <span class="error-campo" id="error-durationDays">{{ errores['durationDays'] }}</span> }
              }
            }

            @if (accion === 'duracion') {
              <label>Nueva duración total (días, desde el inicio)
                <input type="number" name="dias" min="1" [(ngModel)]="dias"
                  [attr.aria-invalid]="!!errores['durationDays']"
                  [attr.aria-describedby]="errores['durationDays'] ? 'error-durationDays' : null" />
              </label>
              @if (errores['durationDays']) { <span class="error-campo" id="error-durationDays">{{ errores['durationDays'] }}</span> }
              <label>Justificación del cambio
                <textarea name="justificacion" rows="2" maxlength="1000" [(ngModel)]="texto"
                  [attr.aria-invalid]="!!errores['justification']"
                  [attr.aria-describedby]="errores['justification'] ? 'error-justification' : null"></textarea>
              </label>
              @if (errores['justification']) { <span class="error-campo" id="error-justification">{{ errores['justification'] }}</span> }
            }

            @if (accion === 'permanente') {
              <label>Justificación del cambio
                <textarea name="justificacionPermanente" rows="2" maxlength="1000" [(ngModel)]="texto"
                  [attr.aria-invalid]="!!errores['justification']"
                  [attr.aria-describedby]="errores['justification'] ? 'error-justification-p' : null"></textarea>
              </label>
              @if (errores['justification']) { <span class="error-campo" id="error-justification-p">{{ errores['justification'] }}</span> }
            }

            @if (accion === 'temporal') {
              <label>Duración desde ahora (días)
                <input type="number" name="diasTemporal" min="1" [(ngModel)]="dias"
                  [attr.aria-invalid]="!!errores['durationDays']"
                  [attr.aria-describedby]="errores['durationDays'] ? 'error-durationDays-t' : null" />
              </label>
              @if (errores['durationDays']) { <span class="error-campo" id="error-durationDays-t">{{ errores['durationDays'] }}</span> }
              <label>Justificación del cambio
                <textarea name="justificacionTemporal" rows="2" maxlength="1000" [(ngModel)]="texto"
                  [attr.aria-invalid]="!!errores['justification']"
                  [attr.aria-describedby]="errores['justification'] ? 'error-justification-t' : null"></textarea>
              </label>
              @if (errores['justification']) { <span class="error-campo" id="error-justification-t">{{ errores['justification'] }}</span> }
            }

            @if (accion === 'retirar') {
              <label>Motivo para retirarla antes
                <textarea name="motivoRetiro" rows="2" maxlength="1000" [(ngModel)]="texto"
                  [attr.aria-invalid]="!!errores['reason']"
                  [attr.aria-describedby]="errores['reason'] ? 'error-reason-retiro' : null"></textarea>
              </label>
              @if (errores['reason']) { <span class="error-campo" id="error-reason-retiro">{{ errores['reason'] }}</span> }
            }

            <button type="submit" class="principal" [disabled]="enviando">{{ textoEnviar }}</button>
            <button type="button" class="secundario" (click)="cancelar()">Cancelar</button>
          </form>
        }

        @if (datos.informationRequests.length > 0) {
          <h3>Solicitudes de información</h3>
          <ul class="lista">
            @for (solicitud of datos.informationRequests; track solicitud.id) {
              <li>
                <strong>{{ ESTADO_INFORMACION[solicitud.status] }}</strong> · pedida el {{ fechaHora(solicitud.requestedAt) }},
                vence el {{ fechaHora(solicitud.dueAt) }}
                <br>{{ solicitud.message }}
                @if (solicitud.response) { <br><em>Respuesta:</em> {{ solicitud.response }} }
              </li>
            }
          </ul>
        }

        @if (datos.restrictions.length > 0) {
          <h3>Historial de restricciones</h3>
          <ul class="lista">
            @for (restriccion of datos.restrictions; track restriccion.id) {
              <li>
                <strong>{{ ESTADO_RESTRICCION[restriccion.status] }}</strong> · {{ alcance(restriccion.role) }} ·
                {{ fechaHora(restriccion.startsAt) }} a
                {{ restriccion.closedAt ? fechaHora(restriccion.closedAt) : (restriccion.permanent ? 'sin fin' : fechaHora(restriccion.endsAt)) }}
                — {{ restriccion.reason }}
                <ul>
                  @for (cambio of restriccion.changes; track $index) {
                    <li>{{ CAMBIO_RESTRICCION[cambio.type] }} el {{ fechaHora(cambio.createdAt) }}
                      @if (cambio.actorId) { por la cuenta #{{ cambio.actorId }} } @else { por el sistema }
                      @if (cambio.justification && cambio.type !== 'APPLIED') { : {{ cambio.justification }} }
                    </li>
                  }
                </ul>
              </li>
            }
          </ul>
        }
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .roja { color: #b3261e; }
    .lista li { margin-bottom: 0.5rem; }
    .vigente { border: 1px solid #ffcdd2; background: #fff5f5; border-radius: 0.5rem; padding: 0.6rem 0.8rem;
      margin-bottom: 0.6rem; }
    .acciones { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0.5rem 0; }
    .formulario { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 0.75rem 1rem; margin: 0.75rem 0;
      display: flex; flex-direction: column; gap: 0.4rem; max-width: 28rem; }
    label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.9rem; }
    label.checkbox { flex-direction: row; align-items: center; gap: 0.4rem; }
    textarea, input, select { font: inherit; padding: 0.4rem; border: 1px solid #aaa; border-radius: 0.3rem; }
    input[type=number] { max-width: 8rem; }
    [aria-invalid=true] { border-color: var(--ion-color-danger); }
    .error-campo { color: var(--ion-color-danger); font-size: 0.8rem; }
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
export class AdminRestriccionesComponent {
  private readonly servicio = inject(AdminRestriccionesService);

  @Output() cerrar = new EventEmitter<void>();

  readonly ESTADO_RESTRICCION = ESTADO_RESTRICCION;
  readonly ESTADO_INFORMACION = ESTADO_INFORMACION;
  readonly CAMBIO_RESTRICCION = CAMBIO_RESTRICCION;
  readonly TIPO_ANTECEDENTE = TIPO_ANTECEDENTE;
  readonly ROL = ROL;
  readonly alcance = alcance;
  readonly fechaHora = fechaHora;

  correo = '';
  buscando = false;
  evaluacion: EvaluacionCuenta | null = null;

  accion: Accion | null = null;
  restriccionElegida: Restriccion | null = null;
  texto = '';
  dias: number | null = 30;
  alcanceElegido: 'ACCOUNT' | 'ROLE' = 'ACCOUNT';
  rolElegido: Rol | null = null;
  permanenteElegido = false;
  errores: Record<string, string> = {};
  error = '';
  aviso = '';
  enviando = false;

  rolesTexto(roles: Rol[]): string {
    return roles.length ? roles.map(rol => this.ROL[rol]).join(', ') : 'ninguno';
  }

  buscar(): void {
    const correo = this.correo.trim();
    if (!correo) {
      return;
    }
    this.error = '';
    this.buscando = true;
    this.servicio.buscar(correo).subscribe({
      next: cuenta => {
        this.buscando = false;
        this.cargar(cuenta.id);
      },
      error: (respuesta: HttpErrorResponse) => {
        this.buscando = false;
        this.error = errorDeRespuesta(respuesta).mensaje;
      }
    });
  }

  volver(): void {
    this.evaluacion = null;
    this.correo = '';
    this.limpiar();
  }

  elegir(accion: Accion, restriccion: Restriccion | null = null): void {
    this.limpiar();
    this.accion = accion;
    this.restriccionElegida = restriccion;
    this.alcanceElegido = 'ACCOUNT';
    this.rolElegido = this.evaluacion?.account.roles[0] ?? null;
    this.permanenteElegido = false;
    this.dias = 30;
  }

  cancelar(): void {
    this.accion = null;
    this.restriccionElegida = null;
    this.errores = {};
  }

  get tituloAccion(): string {
    switch (this.accion) {
      case 'informacion': return 'Pedir una aclaración';
      case 'restringir': return 'Aplicar una restricción';
      case 'duracion': return 'Cambiar la duración';
      case 'permanente': return 'Hacer la restricción permanente';
      case 'temporal': return 'Hacer la restricción temporal';
      default: return 'Retirar anticipadamente';
    }
  }

  get textoEnviar(): string {
    return this.accion === 'informacion' ? 'Enviar solicitud' : 'Guardar';
  }

  enviar(): void {
    const cuenta = this.evaluacion?.account;
    if (!cuenta || !this.accion) {
      return;
    }
    const dias = this.dias === null || String(this.dias) === '' ? null : Number(this.dias);
    switch (this.accion) {
      case 'informacion':
        this.ejecutar(this.servicio.pedirInformacion(cuenta.id, this.texto), 'Se pidió la aclaración a la cuenta.');
        break;
      case 'restringir':
        if (this.alcanceElegido === 'ROLE' && !this.rolElegido) {
          this.errores = { role: 'Selecciona el rol que se restringe.' };
          return;
        }
        this.ejecutar(this.servicio.restringir(cuenta.id, this.alcanceElegido,
          this.alcanceElegido === 'ROLE' ? this.rolElegido : null, this.texto, this.permanenteElegido,
          this.permanenteElegido ? null : dias), 'La restricción quedó aplicada.');
        break;
      case 'duracion':
        if (this.restriccionElegida) {
          this.ejecutar(this.servicio.cambiarDuracion(this.restriccionElegida.id, dias, this.texto),
            'Se cambió la duración.');
        }
        break;
      case 'permanente':
        if (this.restriccionElegida) {
          this.ejecutar(this.servicio.hacerPermanente(this.restriccionElegida.id, this.texto),
            'La restricción ahora es permanente.');
        }
        break;
      case 'temporal':
        if (this.restriccionElegida) {
          this.ejecutar(this.servicio.hacerTemporal(this.restriccionElegida.id, dias, this.texto),
            'La restricción ahora es temporal.');
        }
        break;
      case 'retirar':
        if (this.restriccionElegida) {
          this.ejecutar(this.servicio.retirar(this.restriccionElegida.id, this.texto), 'La restricción fue retirada.');
        }
        break;
    }
  }

  private cargar(accountId: number): void {
    this.servicio.evaluacion(accountId).subscribe({
      next: evaluacion => (this.evaluacion = evaluacion),
      error: (respuesta: HttpErrorResponse) => (this.error = errorDeRespuesta(respuesta).mensaje)
    });
  }

  private ejecutar<T>(operacion: Observable<T>, mensaje: string): void {
    const accountId = this.evaluacion?.account.id;
    if (!accountId) {
      return;
    }
    this.errores = {};
    this.error = '';
    this.enviando = true;
    operacion.subscribe({
      next: () => {
        this.enviando = false;
        this.cancelar();
        this.aviso = mensaje;
        this.cargar(accountId);
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        const { mensaje: texto, campo } = errorDeRespuesta(respuesta);
        if (campo) {
          this.errores = { [campo]: texto };
        } else {
          this.error = texto;
        }
      }
    });
  }

  private limpiar(): void {
    this.accion = null;
    this.restriccionElegida = null;
    this.texto = '';
    this.errores = {};
    this.error = '';
    this.aviso = '';
  }
}
