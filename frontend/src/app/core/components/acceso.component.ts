import { Component, EventEmitter, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { IonButton, IonInput, IonItem, IonSelect, IonSelectOption, IonText } from '@ionic/angular/standalone';
import { AuthService, Rol } from '../services/auth.service';

/** Panel de acceso: inicio de sesión y, con sesión abierta, elección de rol activo y cierre de sesión. */
@Component({
  selector: 'app-acceso',
  standalone: true,
  imports: [FormsModule, IonButton, IonInput, IonItem, IonSelect, IonSelectOption, IonText],
  template: `
    <div class="fondo" (click)="cerrar.emit()"></div>
    <section class="panel" role="dialog" aria-modal="true" aria-label="Cuenta">
      <button type="button" class="cerrar" aria-label="Cerrar" (click)="cerrar.emit()">×</button>

      @if (auth.cuenta(); as cuenta) {
        <h2>Hola, {{ auth.obtenerNombreVisible() }}</h2>
        <p>{{ cuenta.email }}</p>

        @if (cuenta.roles.length > 1) {
          <ion-item>
            <ion-select label="Rol activo" placeholder="Elige un rol" [ngModel]="cuenta.activeRole"
              (ionChange)="cambiarRol($event.detail.value)" [disabled]="enviando">
              @for (rol of cuenta.roles; track rol) {
                <ion-select-option [value]="rol">{{ rol }}</ion-select-option>
              }
            </ion-select>
          </ion-item>
          @if (!cuenta.activeRole) {
            <p class="ayuda">Tu cuenta tiene varios roles: elige con cuál quieres trabajar.</p>
          }
        } @else {
          <p>Rol: <strong>{{ cuenta.activeRole }}</strong></p>
        }

        @if (error) { <ion-text color="danger"><p>{{ error }}</p></ion-text> }
        <ion-button expand="block" color="medium" [disabled]="enviando" (click)="salir()">Cerrar sesión</ion-button>
      } @else {
        <h2>Iniciar sesión</h2>

        <form (ngSubmit)="enviar()" novalidate>
          <ion-item>
            <ion-input label="Correo" labelPlacement="stacked" type="email" name="correo" [(ngModel)]="correo"
              required autocomplete="email"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Contraseña" labelPlacement="stacked" type="password" name="clave"
              [(ngModel)]="clave" required autocomplete="current-password"></ion-input>
          </ion-item>

          @if (error) { <ion-text color="danger"><p>{{ error }}</p></ion-text> }

          <ion-button type="submit" expand="block" [disabled]="enviando || !datosValidos()">
            {{ enviando ? 'Entrando…' : 'Entrar' }}
          </ion-button>
        </form>
      }
    </section>
  `,
  styles: [`
    .fondo { position: fixed; inset: 0; background: rgba(7, 59, 76, 0.45); z-index: 1000; }
    .panel {
      position: fixed; z-index: 1001; top: 50%; left: 50%; transform: translate(-50%, -50%);
      width: min(92vw, 26rem); max-height: 90vh; overflow: auto; padding: 1.25rem;
      background: #fff; border-radius: 1rem; box-shadow: 0 1rem 3rem rgba(0, 0, 0, 0.25);
    }
    h2 { margin: 0 0 0.75rem; }
    .cerrar { position: absolute; top: 0.5rem; right: 0.75rem; border: 0; background: none; font-size: 1.5rem; cursor: pointer; }
    .ayuda { font-size: 0.8rem; color: #666; }
  `]
})
export class AccesoComponent {
  readonly auth = inject(AuthService);
  @Output() cerrar = new EventEmitter<void>();

  correo = '';
  clave = '';
  enviando = false;
  error = '';

  datosValidos(): boolean {
    return this.correo.trim().length > 0 && this.clave.length > 0;
  }

  enviar() {
    if (this.enviando || !this.datosValidos()) {
      return;
    }
    this.enviando = true;
    this.error = '';
    this.auth.iniciarSesion(this.correo.trim(), this.clave).subscribe({
      next: cuenta => {
        this.enviando = false;
        this.clave = '';
        // Con varios roles hace falta elegir uno antes de cerrar el panel.
        if (cuenta && (cuenta.roles.length === 1 || cuenta.activeRole)) {
          this.cerrar.emit();
        }
      },
      error: (e: HttpErrorResponse) => this.fallar(e, 'Correo o contraseña incorrectos.')
    });
  }

  cambiarRol(rol: Rol) {
    if (this.enviando || !rol || rol === this.auth.cuenta()?.activeRole) {
      return;
    }
    this.enviando = true;
    this.error = '';
    this.auth.cambiarRol(rol).subscribe({
      next: () => (this.enviando = false),
      error: (e: HttpErrorResponse) => this.fallar(e, 'No se pudo cambiar de rol.')
    });
  }

  salir() {
    this.enviando = true;
    this.auth.cerrarSesion().subscribe(() => {
      this.enviando = false;
      this.cerrar.emit();
    });
  }

  private fallar(e: HttpErrorResponse, porDefecto: string) {
    this.enviando = false;
    this.error = e.status === 0 ? 'No se pudo conectar con el servidor.' : (e.status === 401 ? porDefecto : (e.error?.message || porDefecto));
  }
}
