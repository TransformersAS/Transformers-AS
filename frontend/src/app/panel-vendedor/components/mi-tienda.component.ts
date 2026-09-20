import { Component, DestroyRef, EventEmitter, OnInit, Output, ViewChild, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { finalize, switchMap } from 'rxjs';
import {
    IonBadge,
    IonButton,
    IonButtons,
    IonCheckbox,
    IonContent,
    IonHeader,
    IonInput,
    IonItem,
    IonTextarea,
    IonTitle,
    IonToolbar
} from '@ionic/angular/standalone';

import { VendedorService } from '../services/vendedor.service';
import {
    ConfiguracionTienda,
    ETIQUETA_ESTADO_TIENDA,
    EstadoTienda,
    LIMITES_TIENDA,
    VistaTienda,
    etiquetaMetodoEnvio,
    vistaDeConfiguracion
} from '../models/mi-tienda.model';
import {
    BorradorTienda,
    CambioTienda,
    ErroresTienda,
    aSolicitud,
    borradorDe,
    cambiosEntre,
    validarBorrador
} from '../models/mi-tienda-formulario';
import { ErrorInterpretado, interpretarError } from '../models/mi-tienda-errores';
import { TarjetaTiendaComponent } from './tarjeta-tienda.component';

/**
 * Qué se muestra: la tienda cargada, o el motivo por el que no se puede mostrar (sin tienda, sin el rol activo, sin
 * autorización o un fallo de conexión). A7: nunca se muestra un formulario a quien no puede usarlo.
 */
type Pantalla = 'cargando' | 'lista' | 'sin-tienda' | 'sin-rol' | 'no-autorizada' | 'error';

/** Lectura de lo guardado, edición del borrador local, o vista previa de ese borrador antes de confirmar. */
type Modo = 'lectura' | 'edicion' | 'vista-previa';

/** "Mi tienda" (CU-18): configuración de la tienda del vendedor y una vista previa de cómo la ven los compradores. */
@Component({
    selector: 'app-mi-tienda',
    standalone: true,
    imports: [
        FormsModule, IonBadge, IonButton, IonButtons, IonCheckbox, IonContent, IonHeader, IonInput, IonItem, IonTextarea,
        IonTitle, IonToolbar, TarjetaTiendaComponent
    ],
    template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Mi tienda</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @switch (pantalla) {
        @case ('cargando') {
          <p class="nota" role="status">Cargando tu tienda…</p>
        }
        @case ('sin-tienda') {
          <section class="tarjeta advertencia" role="alert">
            <h2>Aún no tienes una tienda</h2>
            <p>Tu cuenta de vendedor todavía no tiene una tienda asociada, así que no hay nada que configurar.
              Cuando se te asigne una aparecerá aquí.</p>
          </section>
        }
        @case ('sin-rol') {
          <section class="tarjeta advertencia" role="alert">
            <h2>Necesitas el rol activo Vendedor</h2>
            <p>Para configurar la tienda tu rol activo debe ser Vendedor. Cámbialo en «Acceso» y vuelve a abrir
              «Mi tienda».</p>
          </section>
        }
        @case ('no-autorizada') {
          <section class="tarjeta advertencia" role="alert">
            <h2>No tienes autorización para esta tienda</h2>
            <p>Tu cuenta no está autorizada para configurar esta tienda. Solo su cuenta dueña puede hacerlo; si crees
              que es un error, contacta con soporte.</p>
          </section>
        }
        @case ('error') {
          <p class="mensaje" role="alert">{{ mensaje }}</p>
          <ion-button fill="outline" (click)="cargar()">Reintentar</ion-button>
        }
        @case ('lista') {
          @if (tienda) {
            @if (aviso) {
              <p class="mensaje exito" role="status">{{ aviso }}</p>
            }
            @if (errorGeneral) {
              <p class="mensaje" role="alert">{{ errorGeneral }}</p>
            }
            @if (conflicto) {
              <section class="tarjeta advertencia" role="alert">
                <h2>Tu tienda cambió mientras la editabas</h2>
                <p>Recarga para ver la versión más reciente. Los cambios que hiciste aquí se descartarán.</p>
                <ion-button (click)="recargar()">Recargar</ion-button>
              </section>
            }

            @if (!tienda.canModify) {
              <section class="tarjeta advertencia" role="status">
                <h2>Tu tienda está {{ estadoEnMinuscula(tienda.status) }}</h2>
                <p>Por ahora solo puedes consultar su configuración: no se puede editar ni cambiar sus imágenes.</p>
                @if (tienda.statusReason) {
                  <p><strong>Motivo:</strong> {{ tienda.statusReason }}</p>
                }
              </section>
            }

            @switch (modo) {
              @case ('lectura') {
                <section class="tarjeta">
                  <h2>Configuración actual
                    <ion-badge [color]="colorEstado(tienda.status)">{{ etiquetaEstado(tienda.status) }}</ion-badge>
                  </h2>
                  <dl class="datos">
                    <dt>Nombre</dt><dd>{{ tienda.name }}</dd>
                    <dt>Descripción</dt><dd class="texto">{{ tienda.description || 'Sin descripción' }}</dd>
                    <dt>Correo de contacto</dt><dd>{{ tienda.contactEmail || 'No indicado' }}</dd>
                    <dt>Teléfono de contacto</dt><dd>{{ tienda.contactPhone || 'No indicado' }}</dd>
                    <dt>Horarios</dt><dd class="texto">{{ tienda.businessHours || 'No indicados' }}</dd>
                    <dt>Plazo de devolución</dt><dd>{{ tienda.returnWindowDays }} días</dd>
                    <dt>Política</dt><dd class="texto">{{ tienda.policyText || 'Sin texto adicional' }}</dd>
                    <dt>Métodos de envío</dt>
                    <dd>
                      @for (metodo of tienda.shippingMethods.enabled; track metodo) {
                        <span class="etiqueta">{{ etiquetaMetodo(metodo) }}</span>
                      } @empty {
                        Ninguno
                      }
                    </dd>
                  </dl>
                  <p class="nota">Las políticas de tu tienda no pueden contradecir las reglas obligatorias del
                    marketplace. Hoy el plazo de devolución mínimo es de {{ tienda.minReturnWindowDays }} días.</p>
                  @if (tienda.canModify) {
                    <div class="acciones">
                      <ion-button (click)="editar()">Editar configuración</ion-button>
                    </div>
                  }
                </section>

                @if (vista) {
                  <section class="tarjeta">
                    <h2>Así ven tu tienda los compradores</h2>
                    <app-tarjeta-tienda [tienda]="vista"></app-tarjeta-tienda>
                  </section>
                }
              }

              @case ('edicion') {
                <form class="tarjeta" (ngSubmit)="verVistaPrevia()" novalidate>
                  <h2>Editar tu tienda</h2>
                  <p class="nota">Tus cambios no se guardan hasta que veas la vista previa y confirmes.</p>

                  <ion-item>
                    <ion-input label="Nombre de la tienda" labelPlacement="stacked" name="nombre"
                      [(ngModel)]="borrador.name" [counter]="true" [maxlength]="limites.nombre"
                      helperText="Obligatorio. Debe ser distinto al de otras tiendas."
                      [class.ion-invalid]="!!errores.name" [class.ion-touched]="!!errores.name"
                      [errorText]="errores.name"></ion-input>
                  </ion-item>

                  <ion-item>
                    <ion-textarea label="Descripción" labelPlacement="stacked" name="descripcion" [rows]="3"
                      [(ngModel)]="borrador.description" [counter]="true" [maxlength]="limites.descripcion"
                      helperText="Opcional. Cuéntales a tus compradores qué vendes."
                      [class.ion-invalid]="!!errores.description" [class.ion-touched]="!!errores.description"
                      [errorText]="errores.description"></ion-textarea>
                  </ion-item>

                  <ion-item>
                    <ion-input label="Correo de contacto" labelPlacement="stacked" name="correo" type="email"
                      inputmode="email" [(ngModel)]="borrador.contactEmail"
                      helperText="Opcional. Ejemplo: ventas@mitienda.com"
                      [class.ion-invalid]="!!errores.contactEmail" [class.ion-touched]="!!errores.contactEmail"
                      [errorText]="errores.contactEmail"></ion-input>
                  </ion-item>

                  <ion-item>
                    <ion-input label="Teléfono de contacto" labelPlacement="stacked" name="telefono" type="tel"
                      inputmode="tel" [(ngModel)]="borrador.contactPhone"
                      helperText="Opcional. De 7 a 15 dígitos. Ejemplo: +57 300 123 4567"
                      [class.ion-invalid]="!!errores.contactPhone" [class.ion-touched]="!!errores.contactPhone"
                      [errorText]="errores.contactPhone"></ion-input>
                  </ion-item>

                  <ion-item>
                    <ion-textarea label="Horarios de atención" labelPlacement="stacked" name="horarios" [rows]="3"
                      [(ngModel)]="borrador.businessHours" [counter]="true" [maxlength]="limites.horarios"
                      helperText="Opcional. Ejemplo: Lunes a viernes de 8:00 a 18:00"
                      [class.ion-invalid]="!!errores.businessHours" [class.ion-touched]="!!errores.businessHours"
                      [errorText]="errores.businessHours"></ion-textarea>
                  </ion-item>

                  <h3>Política de devoluciones</h3>
                  <p class="nota">Las políticas de tu tienda no pueden contradecir las reglas obligatorias del
                    marketplace. Por ejemplo, el plazo de devolución no puede ser menor a
                    {{ tienda.minReturnWindowDays }} días.</p>

                  <ion-item>
                    <ion-input label="Plazo de devolución (días)" labelPlacement="stacked" name="plazo" type="number"
                      inputmode="numeric" step="1" [min]="tienda.minReturnWindowDays" [max]="limites.plazoMaximo"
                      [value]="borrador.returnWindowDays" (ionInput)="cambiarPlazo($event)"
                      [helperText]="'Mínimo ' + tienda.minReturnWindowDays + ' días, máximo ' + limites.plazoMaximo + '.'"
                      [class.ion-invalid]="!!errores.returnWindowDays" [class.ion-touched]="!!errores.returnWindowDays"
                      [errorText]="errores.returnWindowDays"></ion-input>
                  </ion-item>

                  <ion-item>
                    <ion-textarea label="Texto de la política" labelPlacement="stacked" name="politica" [rows]="4"
                      [(ngModel)]="borrador.policyText" [counter]="true" [maxlength]="limites.politica"
                      helperText="Opcional. Condiciones adicionales de cambios y devoluciones."
                      [class.ion-invalid]="!!errores.policyText" [class.ion-touched]="!!errores.policyText"
                      [errorText]="errores.policyText"></ion-textarea>
                  </ion-item>

                  <fieldset class="envios" [class.con-error]="!!errores.shippingMethods">
                    <legend>Métodos de envío</legend>
                    <p class="nota">Elige entre los métodos que ofrece el marketplace. Tu tienda debe ofrecer al menos uno.</p>
                    @for (metodo of metodosMostrados(); track metodo) {
                      <ion-item lines="none">
                        <ion-checkbox labelPlacement="end" [checked]="metodoElegido(metodo)"
                          (ionChange)="alternarMetodo(metodo, $event.detail.checked)">
                          {{ etiquetaMetodo(metodo) }}
                          @if (!estaDisponible(metodo)) {
                            <span class="tenue"> (ya no está disponible: desmárcalo para poder guardar)</span>
                          }
                        </ion-checkbox>
                      </ion-item>
                    }
                    @if (errores.shippingMethods) {
                      <p class="error-campo" role="alert">{{ errores.shippingMethods }}</p>
                    }
                  </fieldset>

                  <div class="acciones">
                    <ion-button type="submit" [disabled]="ocupado">
                      {{ ocupado ? 'Comprobando…' : 'Ver vista previa' }}
                    </ion-button>
                    <ion-button type="button" fill="outline" [disabled]="ocupado" (click)="cancelar()">Cancelar</ion-button>
                  </div>
                </form>
              }

              @case ('vista-previa') {
                @if (vistaPrevia) {
                  <section class="tarjeta">
                    <h2>Vista previa de los cambios</h2>
                    <p class="nota">Así quedaría tu tienda. Todavía no se ha guardado nada.</p>
                    @if (cambios.length === 0) {
                      <p>No hay cambios respecto a lo que ya tienes guardado.</p>
                    } @else {
                      <ul class="cambios">
                        @for (cambio of cambios; track cambio.campo) {
                          <li>
                            <strong>{{ cambio.campo }}:</strong>
                            <span class="antes">{{ cambio.antes }}</span> →
                            <span class="despues">{{ cambio.despues }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </section>
                  <section class="tarjeta">
                    <h2>Así la verían tus compradores</h2>
                    <app-tarjeta-tienda [tienda]="vistaPrevia"></app-tarjeta-tienda>
                  </section>
                  <div class="acciones">
                    <ion-button fill="outline" [disabled]="ocupado" (click)="volverAEditar()">Volver a editar</ion-button>
                    <ion-button [disabled]="ocupado || conflicto || cambios.length === 0" (click)="confirmar()">
                      {{ ocupado ? 'Guardando…' : 'Confirmar y guardar' }}
                    </ion-button>
                    <ion-button fill="clear" [disabled]="ocupado" (click)="cancelar()">Cancelar</ion-button>
                  </div>
                }
              }
            }
          }
        }
      }
    </ion-content>
  `,
    styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .mensaje { margin: 0 0 0.75rem; font-weight: 600; color: #b3261e; }
    .mensaje.exito { color: #17692f; }
    .nota { color: #666; font-size: 0.9rem; }
    .tarjeta { display: block; margin: 0 0 1rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .tarjeta h2 { margin: 0 0 0.5rem; font-size: 1.1rem; display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .tarjeta p { margin: 0.25rem 0; }
    .tarjeta.advertencia { border-color: #e0a800; background: #fff8e1; }
    .datos { display: grid; grid-template-columns: max-content 1fr; gap: 0.35rem 1rem; margin: 0; }
    .datos dt { color: #667b80; font-size: 0.85rem; }
    .datos dd { margin: 0; overflow-wrap: anywhere; }
    .texto { white-space: pre-line; }
    .etiqueta { display: inline-block; margin: 0 0.35rem 0.25rem 0; padding: 0.1rem 0.6rem; border-radius: 999px;
      background: #eef6e0; font-size: 0.85rem; }
    .acciones { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; margin: 1rem 0; }
    .cambios { margin: 0.5rem 0 0; padding-left: 1.1rem; }
    .cambios li { margin: 0.35rem 0; overflow-wrap: anywhere; white-space: pre-line; }
    .antes { color: #8a5a00; text-decoration: line-through; }
    .despues { color: #17692f; font-weight: 600; }
    h3 { margin: 1.25rem 0 0.25rem; font-size: 1rem; }
    .envios { margin: 1rem 0; padding: 0.5rem 0.75rem; border: 1px solid #d8dee4; border-radius: 0.5rem; }
    .envios.con-error { border-color: #b3261e; }
    .envios legend { padding: 0 0.25rem; font-weight: 600; }
    .error-campo { margin: 0.25rem 0 0; color: #b3261e; font-size: 0.85rem; }
    .tenue { color: #667b80; font-size: 0.85rem; }
  `]
})
export class MiTiendaComponent implements OnInit {
    private readonly servicio = inject(VendedorService);
    private readonly destruido = inject(DestroyRef);

    @Output() cerrar = new EventEmitter<void>();
    @ViewChild(IonContent) private contenido?: IonContent;

    readonly limites = LIMITES_TIENDA;

    pantalla: Pantalla = 'cargando';
    mensaje = '';
    modo: Modo = 'lectura';

    /** Lo último que el servidor confirmó: no se toca hasta que un guardado tiene éxito (A10). */
    tienda: ConfiguracionTienda | null = null;
    /** Cómo ven la tienda los compradores; se calcula al cargar para no crear un objeto nuevo en cada ciclo. */
    vista: VistaTienda | null = null;

    /** Copia local editable. Cancelar la descarta y la recrea desde `tienda` (A9). */
    borrador!: BorradorTienda;
    errores: ErroresTienda = {};
    errorGeneral = '';
    aviso = '';
    /** Hay una petición en curso (vista previa o guardado). */
    ocupado = false;
    /** Otra sesión guardó antes: hasta recargar no se puede confirmar (STORE_CONCURRENT_UPDATE). */
    conflicto = false;

    /** Métodos que el marketplace permite habilitar; se refresca si el backend informa que cambiaron (A6). */
    disponibles: string[] = [];

    /** Respuesta de POST /preview: cómo quedaría la tienda ya normalizada por el backend. */
    vistaPrevia: VistaTienda | null = null;
    cambios: CambioTienda[] = [];

    ngOnInit(): void {
        this.cargar();
    }

    cargar(): void {
        this.pantalla = 'cargando';
        this.mensaje = '';
        this.servicio.obtener().pipe(takeUntilDestroyed(this.destruido)).subscribe({
            next: tienda => {
                this.aplicarGuardada(tienda);
                this.pantalla = 'lista';
            },
            error: (e: HttpErrorResponse) => this.alFallarLaCarga(interpretarError(e))
        });
    }

    // ---------- Flujo: editar → vista previa → confirmar ----------

    editar(): void {
        if (!this.tienda?.canModify) {
            return;
        }
        this.limpiarMensajes();
        this.borrador = borradorDe(this.tienda);
        this.modo = 'edicion';
    }

    /** POST /preview: valida como el guardado y devuelve cómo quedaría, sin guardar nada. */
    verVistaPrevia(): void {
        if (!this.tienda || this.ocupado) {
            return;
        }
        this.limpiarMensajes();
        const locales = validarBorrador(this.borrador, this.tienda.minReturnWindowDays, this.disponibles);
        if (Object.keys(locales).length > 0) {
            this.mostrarErrores(locales);
            return;
        }
        this.ocupado = true;
        this.servicio.previsualizar(this.solicitud()).pipe(
            finalize(() => (this.ocupado = false)),
            takeUntilDestroyed(this.destruido)
        ).subscribe({
            next: previa => {
                this.vistaPrevia = vistaDeConfiguracion(previa);
                this.cambios = cambiosEntre(this.tienda as ConfiguracionTienda, previa);
                this.modo = 'vista-previa';
            },
            error: (e: HttpErrorResponse) => this.alFallar(interpretarError(e))
        });
    }

    volverAEditar(): void {
        this.limpiarMensajes();
        this.modo = 'edicion';
    }

    /**
     * Confirma: primero POST /preview (valida todo sin efectos) y solo si pasa, PUT con la versión recibida. Si algo
     * falla no se guarda nada y `tienda` sigue siendo lo último confirmado (A10).
     */
    confirmar(): void {
        if (!this.tienda || this.ocupado || this.conflicto) {
            return;
        }
        this.limpiarMensajes();
        this.ocupado = true;
        const solicitud = this.solicitud();
        const version = this.tienda.version;
        this.servicio.previsualizar(solicitud).pipe(
            switchMap(() => this.servicio.guardar(solicitud, version)),
            finalize(() => (this.ocupado = false)),
            takeUntilDestroyed(this.destruido)
        ).subscribe({
            next: guardada => {
                this.aplicarGuardada(guardada);
                this.aviso = 'Cambios guardados. Tus compradores ya ven la tienda actualizada.';
                this.desplazarArriba();
            },
            error: (e: HttpErrorResponse) => this.alFallar(interpretarError(e))
        });
    }

    /** A9: descarta el borrador y lo que se estaba viendo; no llama al backend. */
    cancelar(): void {
        if (!this.tienda) {
            return;
        }
        this.limpiarMensajes();
        this.borrador = borradorDe(this.tienda);
        this.descartarVistaPrevia();
        this.modo = 'lectura';
    }

    /** Tras un conflicto de versión: vuelve a leer la tienda y descarta lo editado. */
    recargar(): void {
        this.cargar();
    }

    // ---------- Política y métodos de envío ----------

    /** Los disponibles, más los que la tienda tiene habilitados aunque el marketplace ya no los ofrezca (A6). */
    metodosMostrados(): string[] {
        const retirados = this.borrador.shippingMethods.filter(metodo => !this.disponibles.includes(metodo));
        return [...this.disponibles, ...retirados];
    }

    metodoElegido(metodo: string): boolean {
        return this.borrador.shippingMethods.includes(metodo);
    }

    estaDisponible(metodo: string): boolean {
        return this.disponibles.includes(metodo);
    }

    alternarMetodo(metodo: string, marcado: boolean): void {
        const elegidos = this.borrador.shippingMethods.filter(elegido => elegido !== metodo);
        this.borrador.shippingMethods = marcado ? [...elegidos, metodo] : elegidos;
        delete this.errores.shippingMethods;
    }

    /** El plazo se lee del evento (no de ngModel) para no depender de cómo convierta Ionic los campos numéricos. */
    cambiarPlazo(evento: Event): void {
        const valor = (evento as CustomEvent<{ value?: string | number | null }>).detail.value;
        this.borrador.returnWindowDays =
            valor === undefined || valor === null || `${valor}`.trim() === '' ? null : Number(valor);
        delete this.errores.returnWindowDays;
    }

    // ---------- Presentación ----------

    etiquetaEstado(estado: EstadoTienda): string {
        return ETIQUETA_ESTADO_TIENDA[estado];
    }

    estadoEnMinuscula(estado: EstadoTienda): string {
        return ETIQUETA_ESTADO_TIENDA[estado].toLowerCase();
    }

    colorEstado(estado: EstadoTienda): string {
        return estado === 'ACTIVE' ? 'success' : estado === 'RESTRICTED' ? 'warning' : 'danger';
    }

    etiquetaMetodo(metodo: string): string {
        return etiquetaMetodoEnvio(metodo);
    }

    // ---------- Internos ----------

    private solicitud() {
        return aSolicitud(this.borrador, (this.tienda as ConfiguracionTienda).returnWindowDays);
    }

    /** Adopta lo que el servidor confirmó y deja la pantalla en lectura, sin edición pendiente. */
    private aplicarGuardada(tienda: ConfiguracionTienda): void {
        this.tienda = tienda;
        this.vista = vistaDeConfiguracion(tienda);
        this.borrador = borradorDe(tienda);
        this.disponibles = [...tienda.shippingMethods.available];
        this.limpiarMensajes();
        this.conflicto = false;
        this.descartarVistaPrevia();
        this.modo = 'lectura';
    }

    private descartarVistaPrevia(): void {
        this.vistaPrevia = null;
        this.cambios = [];
    }

    private limpiarMensajes(): void {
        this.errores = {};
        this.errorGeneral = '';
        this.aviso = '';
    }

    private mostrarErrores(errores: ErroresTienda): void {
        this.errores = errores;
        this.errorGeneral = 'Revisa los campos marcados: hay datos que hay que corregir antes de continuar.';
        this.modo = 'edicion';
        this.desplazarArriba();
    }

    private desplazarArriba(): void {
        void this.contenido?.scrollToTop(200);
    }

    /** Cada tipo de error tiene su tratamiento; ninguno toca lo guardado (A10). */
    private alFallar(error: ErrorInterpretado): void {
        switch (error.tipo) {
            case 'campo':
                if (error.disponibles) {
                    this.disponibles = [...error.disponibles];
                }
                this.mostrarErrores({ [error.campo]: error.mensaje });
                break;
            case 'concurrencia':
                this.conflicto = true;
                this.errorGeneral = error.mensaje;
                break;
            case 'bloqueada':
                this.recargarTrasBloqueo(error);
                break;
            case 'sin-tienda':
                this.pantalla = 'sin-tienda';
                break;
            case 'sin-rol':
                this.pantalla = 'sin-rol';
                break;
            case 'no-autorizada':
                this.pantalla = 'no-autorizada';
                break;
            default:
                this.errorGeneral = error.mensaje;
                this.desplazarArriba();
        }
    }

    /** A8: el estado cambió mientras se editaba. Se relee la tienda, que queda en solo lectura con su motivo. */
    private recargarTrasBloqueo(error: ErrorInterpretado): void {
        this.servicio.obtener().pipe(takeUntilDestroyed(this.destruido)).subscribe({
            next: tienda => {
                this.aplicarGuardada(tienda);
                this.errorGeneral = `${error.mensaje} Tus cambios no se guardaron.`;
            },
            error: () => (this.errorGeneral = error.mensaje)
        });
    }

    private alFallarLaCarga(error: ErrorInterpretado): void {
        switch (error.tipo) {
            case 'sin-tienda':
                this.pantalla = 'sin-tienda';
                break;
            case 'sin-rol':
                this.pantalla = 'sin-rol';
                break;
            case 'no-autorizada':
                this.pantalla = 'no-autorizada';
                break;
            default:
                this.pantalla = 'error';
                this.mensaje = error.mensaje;
        }
    }
}
