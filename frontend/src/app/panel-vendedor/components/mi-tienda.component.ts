import { Component, DestroyRef, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import { VendedorService } from '../services/vendedor.service';
import {
    ConfiguracionTienda,
    ETIQUETA_ESTADO_TIENDA,
    ErrorTienda,
    EstadoTienda,
    VistaTienda,
    etiquetaMetodoEnvio,
    vistaDeConfiguracion
} from '../models/mi-tienda.model';
import { TarjetaTiendaComponent } from './tarjeta-tienda.component';

/**
 * Qué se muestra: la tienda cargada, o el motivo por el que no se puede mostrar (sin tienda, sin el rol activo, sin
 * autorización o un fallo de conexión). A7: nunca se muestra un formulario a quien no puede usarlo.
 */
type Pantalla = 'cargando' | 'lista' | 'sin-tienda' | 'sin-rol' | 'no-autorizada' | 'error';

/** "Mi tienda" (CU-18): configuración de la tienda del vendedor y una vista previa de cómo la ven los compradores. */
@Component({
    selector: 'app-mi-tienda',
    standalone: true,
    imports: [IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar, TarjetaTiendaComponent],
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
            @if (!tienda.canModify) {
              <section class="tarjeta advertencia" role="status">
                <h2>Tu tienda está {{ estadoEnMinuscula(tienda.status) }}</h2>
                <p>Por ahora solo puedes consultar su configuración: no se puede editar ni cambiar sus imágenes.</p>
                @if (tienda.statusReason) {
                  <p><strong>Motivo:</strong> {{ tienda.statusReason }}</p>
                }
              </section>
            }

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
              <p class="nota">Las políticas de tu tienda no pueden contradecir las reglas obligatorias del marketplace.
                Hoy el plazo de devolución mínimo es de {{ tienda.minReturnWindowDays }} días.</p>
            </section>

            @if (vista) {
              <section class="tarjeta">
                <h2>Así ven tu tienda los compradores</h2>
                <app-tarjeta-tienda [tienda]="vista"></app-tarjeta-tienda>
              </section>
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
    .nota { color: #666; font-size: 0.9rem; }
    .tarjeta { margin: 0 0 1rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .tarjeta h2 { margin: 0 0 0.5rem; font-size: 1.1rem; display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .tarjeta p { margin: 0.25rem 0; }
    .tarjeta.advertencia { border-color: #e0a800; background: #fff8e1; }
    .datos { display: grid; grid-template-columns: max-content 1fr; gap: 0.35rem 1rem; margin: 0; }
    .datos dt { color: #667b80; font-size: 0.85rem; }
    .datos dd { margin: 0; overflow-wrap: anywhere; }
    .texto { white-space: pre-line; }
    .etiqueta { display: inline-block; margin: 0 0.35rem 0.25rem 0; padding: 0.1rem 0.6rem; border-radius: 999px;
      background: #eef6e0; font-size: 0.85rem; }
  `]
})
export class MiTiendaComponent implements OnInit {
    private readonly servicio = inject(VendedorService);
    private readonly destruido = inject(DestroyRef);

    @Output() cerrar = new EventEmitter<void>();

    pantalla: Pantalla = 'cargando';
    mensaje = '';
    /** Lo último que el servidor confirmó. */
    tienda: ConfiguracionTienda | null = null;
    /** Cómo ven la tienda los compradores; se calcula al cargar para no crear un objeto nuevo en cada ciclo. */
    vista: VistaTienda | null = null;

    ngOnInit(): void {
        this.cargar();
    }

    cargar(): void {
        this.pantalla = 'cargando';
        this.mensaje = '';
        this.servicio.obtener().pipe(takeUntilDestroyed(this.destruido)).subscribe({
            next: tienda => {
                this.tienda = tienda;
                this.vista = vistaDeConfiguracion(tienda);
                this.pantalla = 'lista';
            },
            error: (e: HttpErrorResponse) => this.alFallarLaCarga(e)
        });
    }

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

    /** Cada causa tiene su propio aviso, con lo que puede hacer el vendedor (A7). */
    private alFallarLaCarga(e: HttpErrorResponse): void {
        const codigo = (e.error as ErrorTienda | null)?.code;
        if (e.status === 401 && codigo === 'STORE_IDENTITY_MISSING') {
            this.pantalla = 'sin-tienda';
        } else if (e.status === 403 && codigo === 'SELLER_ROLE_REQUIRED') {
            this.pantalla = 'sin-rol';
        } else if (e.status === 403) {
            this.pantalla = 'no-autorizada';
        } else {
            this.pantalla = 'error';
            this.mensaje = e.status === 0
                ? 'No se pudo conectar con el servidor. Comprueba tu conexión y vuelve a intentarlo.'
                : e.status === 401
                    ? 'Tu sesión ya no es válida. Inicia sesión de nuevo con una cuenta de vendedor.'
                    : (e.error as ErrorTienda | null)?.message || 'No se pudo cargar tu tienda. Inténtalo de nuevo.';
        }
    }
}
