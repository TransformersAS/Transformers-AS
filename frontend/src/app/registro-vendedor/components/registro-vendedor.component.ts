import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import {
  IonButton,
  IonButtons,
  IonCheckbox,
  IonContent,
  IonHeader,
  IonInput,
  IonItem,
  IonText,
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { AuthService } from '../../core/services/auth.service';
import { CondicionesVendedor, ResultadoRegistro } from '../models/registro-vendedor.model';
import { RegistroVendedorService } from '../services/registro-vendedor.service';

type Paso = 'formulario' | 'verificar' | 'listo';
type Modo = 'nueva' | 'existente';

/**
 * Registro de vendedores (CU-12) en tres pasos: el formulario (con las condiciones y el nombre único de la tienda),
 * la confirmación del registro cuando hace falta y el cierre. Quien ya tiene sesión usa su misma cuenta; quien no,
 * crea una nueva. Las reglas las decide el backend; aquí solo se guía el proceso y se muestran sus mensajes.
 */
@Component({
  selector: 'app-registro-vendedor',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonCheckbox, IonContent, IonHeader, IonInput, IonItem, IonText,
    IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Vende en el marketplace</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) {
        <ion-text color="danger"><p role="alert">{{ error }}</p></ion-text>
      }
      @if (aviso) {
        <ion-text color="success"><p role="status">{{ aviso }}</p></ion-text>
      }

      @if (yaEsVendedor && paso === 'formulario') {
        <p>Tu cuenta ya tiene el rol de vendedor. Cambia tu rol activo a VENDEDOR desde el panel de tu cuenta para usar tu tienda.</p>
      } @else if (paso === 'formulario') {
        <div class="modos">
          <ion-button [fill]="modo === 'nueva' ? 'solid' : 'outline'" (click)="cambiarModo('nueva')">Soy nuevo</ion-button>
          <ion-button [fill]="modo === 'existente' ? 'solid' : 'outline'" (click)="cambiarModo('existente')">Ya tengo cuenta</ion-button>
        </div>

        @if (modo === 'existente' && !auth.autenticada()) {
          <p>Inicia sesión con tu cuenta desde el icono de arriba a la derecha y vuelve aquí: usarás la misma cuenta y las mismas credenciales.</p>
        } @else {
          <form (ngSubmit)="enviar()" novalidate>
            @if (modo === 'nueva') {
              <p>Crea tu cuenta y tu tienda.</p>
              <ion-item>
                <ion-input label="Correo" labelPlacement="stacked" type="email" name="correo" [(ngModel)]="correo"
                  autocomplete="email" [disabled]="enviando"></ion-input>
              </ion-item>
              <ion-item>
                <ion-input label="Contraseña (mínimo 12 caracteres)" labelPlacement="stacked" type="password" name="clave"
                  [(ngModel)]="clave" autocomplete="new-password" [disabled]="enviando"></ion-input>
              </ion-item>
              <ion-item>
                <ion-input label="Repite la contraseña" labelPlacement="stacked" type="password" name="confirmacion"
                  [(ngModel)]="confirmacion" autocomplete="new-password" [disabled]="enviando"></ion-input>
              </ion-item>
            } @else {
              <p>Vas a habilitar el rol de vendedor en tu cuenta <strong>{{ auth.cuenta()?.email }}</strong>.</p>
            }

            <ion-item>
              <ion-input label="Nombre de tu tienda (único)" labelPlacement="stacked" name="tienda" [(ngModel)]="tienda"
                maxlength="100" [disabled]="enviando"></ion-input>
            </ion-item>

            @if (condiciones) {
              <h3>Condiciones para vender (versión {{ condiciones.version }})</h3>
              <pre class="condiciones">{{ condiciones.text }}</pre>
            }
            <ion-item>
              <ion-checkbox name="acepto" [(ngModel)]="acepto" [disabled]="enviando">Acepto las condiciones para vender</ion-checkbox>
            </ion-item>

            <ion-button type="submit" [disabled]="enviando || !formularioValido">
              {{ modo === 'nueva' ? 'Crear cuenta y tienda' : 'Habilitar mi tienda' }}
            </ion-button>
          </form>
        }
      } @else if (paso === 'verificar') {
        <h2>Confirma tu registro</h2>
        <p>Reservamos tu tienda a nombre de <strong>{{ correoPendiente }}</strong>. Para activar tu rol de vendedor,
          escribe el nombre de tu tienda para confirmar.</p>
        <form (ngSubmit)="confirmar()" novalidate>
          <ion-item>
            <ion-input label="Nombre de tu tienda" labelPlacement="stacked" name="confirmacion"
              [(ngModel)]="nombreConfirmacion" [disabled]="enviando"></ion-input>
          </ion-item>
          <ion-button type="submit" [disabled]="enviando || !nombreConfirmacion.trim()">Confirmar registro</ion-button>
        </form>
      } @else {
        <h2>¡Listo!</h2>
        <p>
          @if (resultado?.sellerRoleActive || verificado) {
            Tu cuenta ya tiene el rol de vendedor y tu tienda <strong>{{ resultado?.storeName }}</strong> está creada.
            Cierra sesión y vuelve a entrar; después elige el rol VENDEDOR en el panel de tu cuenta para empezar a publicar.
          }
        </p>
        <ion-button (click)="cerrar.emit()">Entendido</ion-button>
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .modos { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 1rem; }
    form { margin-bottom: 1.5rem; }
    .condiciones { white-space: pre-wrap; font: inherit; padding: 0.75rem 1rem; background: #f4ecdf; border-radius: 0.5rem; }
    .nota { color: #666; font-size: 0.9rem; }
  `]
})
export class RegistroVendedorComponent implements OnInit {
  private readonly servicio = inject(RegistroVendedorService);
  readonly auth = inject(AuthService);

  @Output() cerrar = new EventEmitter<void>();

  paso: Paso = 'formulario';
  modo: Modo = 'nueva';
  condiciones: CondicionesVendedor | null = null;
  resultado: ResultadoRegistro | null = null;

  correo = '';
  clave = '';
  confirmacion = '';
  tienda = '';
  acepto = false;
  nombreConfirmacion = '';

  correoPendiente = '';
  verificado = false;
  enviando = false;
  error = '';
  aviso = '';

  ngOnInit(): void {
    this.modo = this.auth.autenticada() ? 'existente' : 'nueva';
    this.servicio.obtenerCondiciones().subscribe({
      next: condiciones => (this.condiciones = condiciones),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  get yaEsVendedor(): boolean {
    return this.auth.cuenta()?.roles.includes('VENDEDOR') ?? false;
  }

  /** Comprobación previa para no molestar al servidor con datos evidentemente incompletos; el backend valida de nuevo. */
  get formularioValido(): boolean {
    const tiendaValida = this.tienda.trim() !== '' && this.acepto;
    if (this.modo === 'existente') {
      return tiendaValida;
    }
    return tiendaValida && this.correo.includes('@') && this.clave.length >= 12 && this.clave === this.confirmacion;
  }

  cambiarModo(modo: Modo): void {
    this.modo = modo;
    this.error = '';
  }

  enviar(): void {
    if (!this.formularioValido) {
      return;
    }
    const operacion = this.modo === 'nueva'
      ? this.servicio.registrar(this.correo, this.clave, this.tienda, this.acepto)
      : this.servicio.habilitar(this.tienda, this.acepto);
    this.tramitar(operacion, resultado => {
      this.resultado = resultado;
      this.correoPendiente = this.modo === 'nueva' ? this.correo : (this.auth.cuenta()?.email ?? '');
      this.clave = this.confirmacion = '';
      this.paso = resultado.emailVerificationRequired ? 'verificar' : 'listo';
    });
  }

  /** Primera entrega: se confirma escribiendo el nombre de la tienda registrada (no comprueba el buzón del correo). */
  confirmar(): void {
    this.tramitar(this.servicio.confirmarRegistro(this.correoPendiente, this.nombreConfirmacion.trim()), () => {
      this.verificado = true;
      this.paso = 'listo';
    });
  }

  /** Ejecuta una llamada, muestra el mensaje del backend si la rechaza y evita envíos dobles mientras tanto. */
  private tramitar<T>(operacion: Observable<T>, alTerminar: (resultado: T) => void): void {
    this.error = '';
    this.aviso = '';
    this.enviando = true;
    operacion.pipe(finalize(() => (this.enviando = false))).subscribe({
      next: alTerminar,
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
  }
}
