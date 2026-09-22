import { Component, DestroyRef, EventEmitter, Output, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { IonButton, IonCheckbox, IonInput, IonItem, IonSelect, IonSelectOption, IonText } from '@ionic/angular/standalone';
import { Observable, catchError, finalize, of, switchMap, tap } from 'rxjs';
import { AuthService, Rol } from '../services/auth.service';
import { SesionActiva } from '../models/auth.model';
import { PerfilResumenComponent } from '../../cuenta/components/perfil-resumen.component';

type Vista = 'cuenta' | 'sesiones' | 'password' | 'solicitar' | 'confirmar' | 'verificar';

@Component({
  selector: 'app-acceso',
  standalone: true,
  imports: [FormsModule, DatePipe, PerfilResumenComponent, IonButton, IonCheckbox, IonInput, IonItem, IonSelect, IonSelectOption, IonText],
  templateUrl: './acceso.component.html',
  styleUrl: './acceso.component.scss'
})
export class AccesoComponent {
  readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  @Output() cerrar = new EventEmitter<void>();

  vista: Vista = 'cuenta';
  correo = '';
  clave = '';
  mantenerSesion = false;
  actual = '';
  nueva = '';
  confirmacion = '';
  token = '';
  correoSinVerificar = false;
  enviando = false;
  error = '';
  exito = '';
  sesiones: SesionActiva[] = [];
  sesionesCargadas = false;
  pendiente: SesionActiva | null = null;

  cerrarPanel(): void {
    if (!this.enviando) this.cerrar.emit();
  }

  cambiarVista(vista: Vista): void {
    if (this.enviando) return;
    this.vista = vista;
    this.error = '';
    this.exito = '';
    this.pendiente = null;
    this.limpiarClaves();
    this.token = '';
    if (vista === 'sesiones') this.cargarSesiones();
  }

  correoValido(): boolean {
    return this.correo.trim().length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.correo.trim());
  }

  datosValidos(): boolean {
    return this.correo.trim().length > 0 && this.clave.length > 0;
  }

  errorContrasena(cambio: boolean): string {
    if (cambio && !this.actual.trim()) return 'Escribe tu contraseña actual.';
    if (cambio && new TextEncoder().encode(this.actual).length > 72) return 'La contraseña actual supera 72 bytes UTF-8.';
    if (!this.nueva.trim() || Array.from(this.nueva).length < 12) return 'La nueva contraseña debe tener al menos 12 caracteres.';
    if (new TextEncoder().encode(this.nueva).length > 72) return 'La nueva contraseña supera 72 bytes UTF-8; algunos caracteres ocupan varios bytes.';
    if (!this.confirmacion || this.nueva !== this.confirmacion) return 'Las contraseñas nuevas deben coincidir.';
    if (cambio && this.nueva === this.actual) return 'La nueva contraseña debe ser diferente de la actual.';
    return '';
  }

  enviar(): void {
    if (!this.datosValidos()) return;
    this.correoSinVerificar = false;
    this.ejecutar(this.auth.iniciarSesion(this.correo.trim(), this.clave, this.mantenerSesion), cuenta => {
      this.limpiarClaves();
      this.mantenerSesion = false;
      this.sesiones = [];
      this.sesionesCargadas = false;
      this.pendiente = null;
      this.vista = 'cuenta';
      this.exito = cuenta.activeRole ? 'Sesión iniciada.' : 'Sesión iniciada. Selecciona tu rol activo.';
    }, 'No se pudo iniciar sesión. Comprueba correo y contraseña.');
  }

  reenviarVerificacion(): void {
    if (!this.datosValidos()) return;
    this.ejecutar(this.auth.reenviarVerificacion(this.correo.trim(), this.clave), () => {
      this.exito = 'Si tu correo sigue pendiente, enviamos un nuevo código. Usa el último recibido; vence en 30 minutos.';
    }, 'No se pudo enviar el correo de verificación. Inténtalo de nuevo más tarde.');
  }

  verificarCorreo(): void {
    if (!this.token.trim()) return;
    this.ejecutar(this.auth.confirmarCorreo(this.token.trim()), () => {
      this.token = '';
      this.correoSinVerificar = false;
      this.limpiarClaves();
      this.vista = 'cuenta';
      this.exito = 'Correo verificado. Ya puedes iniciar sesión.';
    }, 'Código de verificación inválido, vencido o ya utilizado. Solicita otro desde el inicio de sesión.');
  }

  cambiarRol(rol: Rol): void {
    if (!rol || rol === this.auth.cuenta()?.activeRole) return;
    this.ejecutar(this.auth.cambiarRol(rol), () => {
      this.exito = 'Rol activo actualizado.';
    }, 'No se pudo cambiar el rol activo.');
  }

  salir(): void {
    this.ejecutar(this.auth.cerrarSesion(), () => {
      this.limpiarClaves();
      this.sesiones = [];
      this.vista = 'cuenta';
      this.cerrar.emit();
    }, 'No se pudo confirmar el cierre de sesión. Inténtalo de nuevo.');
  }

  cargarSesiones(): void {
    this.ejecutar(this.auth.listarSesiones(), sesiones => {
      this.sesiones = sesiones;
      this.sesionesCargadas = true;
    }, 'No se pudieron cargar las sesiones. Intenta actualizar.');
  }

  pedirRevocacion(sesion: SesionActiva): void {
    if (this.enviando) return;
    this.pendiente = sesion;
    this.error = '';
    this.exito = '';
  }

  revocar(): void {
    const sesion = this.pendiente;
    if (!sesion || this.enviando) return;
    const operacion = this.auth.revocarSesion(sesion).pipe(
      tap(() => {
        this.pendiente = null;
        this.sesiones = this.sesiones.filter(item => item.id !== sesion.id);
        this.exito = sesion.current ? 'Sesión actual revocada. Vuelve a iniciar sesión para continuar.' : 'Sesión revocada.';
        if (sesion.current) {
          this.vista = 'cuenta';
          this.limpiarClaves();
        }
      }),
      switchMap(() => sesion.current ? of([]) : this.auth.listarSesiones().pipe(
        catchError(() => {
          this.error = 'La sesión fue revocada, pero no se pudo actualizar la lista. Pulsa Actualizar.';
          return of(this.sesiones);
        })
      ))
    );
    this.ejecutar(operacion, sesiones => { this.sesiones = sesiones; }, 'No se pudo revocar la sesión. Actualiza la lista e inténtalo de nuevo.');
  }

  cambiarContrasena(): void {
    if (this.enviando) return;
    const validacion = this.errorContrasena(true);
    if (validacion) { this.error = validacion; return; }
    this.ejecutar(this.auth.cambiarContrasena({ currentPassword: this.actual, newPassword: this.nueva }), () => {
      this.limpiarClaves();
      this.sesiones = [];
      this.sesionesCargadas = false;
      this.exito = 'Contraseña actualizada. Esta sesión continúa activa; las demás sesiones de tu cuenta se cerraron.';
    }, 'No se pudo cambiar la contraseña. Comprueba la contraseña actual y los requisitos de la nueva.');
  }

  solicitarRecuperacion(): void {
    if (!this.correoValido()) return;
    this.ejecutar(this.auth.solicitarRecuperacion({ email: this.correo.trim() }), () => {
      this.exito = 'Si la cuenta está activa, recibirás instrucciones para recuperar la contraseña.';
    }, 'No se pudo procesar la solicitud de recuperación. Inténtalo de nuevo más tarde.');
  }

  confirmarRecuperacion(): void {
    if (this.enviando) return;
    const validacion = this.errorContrasena(false);
    if (!this.token.trim() || validacion) {
      this.error = !this.token.trim() ? 'Escribe el token de recuperación recibido.' : validacion;
      return;
    }
    this.ejecutar(this.auth.confirmarRecuperacion({ token: this.token.trim(), newPassword: this.nueva }), () => {
      this.token = '';
      this.limpiarClaves();
      this.vista = 'cuenta';
      this.exito = 'Contraseña recuperada. Puedes iniciar sesión con tu nueva contraseña.';
    }, 'No se pudo recuperar la contraseña. Comprueba que el token sea válido, no haya expirado ni se haya utilizado y que la contraseña cumpla los requisitos.');
  }

  private ejecutar<T>(operacion: Observable<T>, alCompletar: (valor: T) => void, mensajeError: string): void {
    if (this.enviando) return;
    this.enviando = true;
    this.error = '';
    this.exito = '';
    operacion.pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => { this.enviando = false; })
    ).subscribe({
      next: alCompletar,
      error: (e: HttpErrorResponse) => {
        if (e.status === 403 && e.error?.code === 'EMAIL_NOT_VERIFIED') {
          this.correoSinVerificar = true;
          this.error = 'Debes verificar tu correo antes de iniciar sesión. Puedes reenviar el código o ingresar el recibido.';
          return;
        }
        this.error = e.status === 0 ? 'No se pudo conectar con el servidor. Inténtalo de nuevo.'
          : e.status === 401 && !this.auth.autenticada() ? 'La sesión no está disponible o las credenciales son incorrectas. Inicia sesión para continuar.'
          : mensajeError;
      }
    });
  }

  private limpiarClaves(): void {
    this.clave = '';
    this.actual = '';
    this.nueva = '';
    this.confirmacion = '';
  }
}
