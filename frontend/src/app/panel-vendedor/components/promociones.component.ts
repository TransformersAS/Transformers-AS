import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import {
  IonBadge,
  IonButton,
  IonButtons,
  IonCheckbox,
  IonContent,
  IonHeader,
  IonInput,
  IonItem,
  IonLabel,
  IonList,
  IonSelect,
  IonSelectOption,
  IonText,
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { EstadoPromocion, Promocion, SolicitudPromocion, TipoDescuento } from '../models/promocion-vendedor.model';
import { ProductoVendedor } from '../models/producto-vendedor.model';
import { ProductosVendedorService } from '../services/productos-vendedor.service';
import { PromocionesVendedorService } from '../services/promociones-vendedor.service';

const ESTADOS: Record<EstadoPromocion, { texto: string; color: string }> = {
  INACTIVE: { texto: 'Inactivo', color: 'medium' },
  SCHEDULED: { texto: 'Programado', color: 'tertiary' },
  RUNNING: { texto: 'Vigente', color: 'success' },
  EXPIRED: { texto: 'Vencido', color: 'warning' }
};

/**
 * Promociones y cupones del vendedor (CU-16): crear un cupón con su código, descuento, compra mínima, vigencia y
 * productos; revisar los conflictos con otros cupones activos; y activarlo, modificarlo, desactivarlo o eliminarlo.
 * El comprador lo escribe en el checkout. Las reglas las decide el backend; aquí solo se guía y se muestran sus mensajes.
 */
@Component({
  selector: 'app-promociones',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonCheckbox, IonContent, IonHeader,
    IonInput, IonItem, IonLabel, IonList, IonSelect, IonSelectOption, IonText, IonTitle, IonToolbar
  ],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Promociones y cupones</ion-title>
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

      @if (formularioAbierto) {
        <form class="formulario" (ngSubmit)="guardar()" novalidate>
          <h2>{{ editandoId ? 'Modificar cupón' : 'Nuevo cupón' }}</h2>
          <ion-item>
            <ion-input label="Nombre" labelPlacement="stacked" name="nombre" maxlength="120" [(ngModel)]="datos.name"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Código que escribe el comprador" labelPlacement="stacked" name="codigo" maxlength="30"
              helperText="De 3 a 30 letras, números, guiones o guiones bajos" [(ngModel)]="datos.code"></ion-input>
          </ion-item>
          <ion-item>
            <ion-select label="Tipo de descuento" labelPlacement="stacked" name="tipo" [(ngModel)]="datos.discountType">
              <ion-select-option value="PERCENT">Porcentaje</ion-select-option>
              <ion-select-option value="AMOUNT">Monto fijo</ion-select-option>
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-input [label]="datos.discountType === 'PERCENT' ? 'Porcentaje (1 a 100)' : 'Monto a descontar (COP)'"
              labelPlacement="stacked" type="number" min="1" name="valor" [(ngModel)]="datos.discountValue"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Compra mínima (opcional, COP)" labelPlacement="stacked" type="number" min="1" name="minimo"
              [(ngModel)]="datos.minPurchase"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Empieza" labelPlacement="stacked" type="datetime-local" name="inicio" [(ngModel)]="datos.startsAt"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Termina" labelPlacement="stacked" type="datetime-local" name="fin" [(ngModel)]="datos.endsAt"></ion-input>
          </ion-item>

          <h3>Productos</h3>
          <p class="nota">Si no eliges ninguno, el cupón aplica a todos los productos de tu tienda.</p>
          <ion-list>
            @for (producto of productos; track producto.id) {
              <ion-item>
                <ion-checkbox [name]="'producto-' + producto.id" [ngModel]="elegidos.has(producto.id)"
                  (ngModelChange)="alternarProducto(producto.id, $event)">{{ producto.name }} · {{ producto.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</ion-checkbox>
              </ion-item>
            } @empty {
              <ion-item><ion-label>Todavía no tienes productos.</ion-label></ion-item>
            }
          </ion-list>

          <ion-button type="submit" [disabled]="enviando">Guardar</ion-button>
          <ion-button fill="outline" type="button" (click)="cancelar()">Cancelar</ion-button>
        </form>
      } @else {
        <ion-button (click)="nuevo()">Nuevo cupón</ion-button>
      }

      <ion-list>
        @for (promocion of promociones; track promocion.id) {
          <ion-item>
            <ion-label>
              <h2>{{ promocion.name }} · <strong>{{ promocion.code }}</strong>
                <ion-badge [color]="estado(promocion).color">{{ estado(promocion).texto }}</ion-badge></h2>
              <p>{{ descripcionDescuento(promocion) }}@if (promocion.minPurchase) { · compra mínima {{ promocion.minPurchase | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} }</p>
              <p>Del {{ promocion.startsAt | date: 'dd/MM/yyyy HH:mm' }} al {{ promocion.endsAt | date: 'dd/MM/yyyy HH:mm' }} ·
                {{ promocion.productIds.length === 0 ? 'Toda la tienda' : promocion.productIds.length + ' producto(s)' }}</p>
              @if (promocion.conflicts.length > 0) {
                <ion-text color="warning">
                  <p><strong>Se cruza con:</strong> {{ promocion.conflicts.join(', ') }}. Si aplican varios, el comprador solo usa el que escribe.</p>
                </ion-text>
              }
              <p>
                <ion-button size="small" fill="clear" (click)="editar(promocion)">Modificar</ion-button>
                @if (promocion.active) {
                  <ion-button size="small" fill="clear" (click)="ejecutar(servicio.desactivar(promocion.id), 'Cupón desactivado.')">Desactivar</ion-button>
                } @else {
                  <ion-button size="small" fill="clear" (click)="ejecutar(servicio.activar(promocion.id), 'Cupón activado.')">Activar</ion-button>
                }
                <ion-button size="small" fill="clear" color="danger" (click)="eliminar(promocion)">Eliminar</ion-button>
              </p>
            </ion-label>
          </ion-item>
        } @empty {
          <ion-item><ion-label>Aún no has creado cupones.</ion-label></ion-item>
        }
      </ion-list>
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .formulario { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 1rem; margin-bottom: 1.5rem; }
    .nota { color: #666; font-size: 0.9rem; }
    ion-badge { margin-left: 0.5rem; vertical-align: middle; }
  `]
})
export class PromocionesComponent implements OnInit {
  readonly servicio = inject(PromocionesVendedorService);
  private readonly productosServicio = inject(ProductosVendedorService);

  @Output() cerrar = new EventEmitter<void>();

  error = '';
  aviso = '';
  enviando = false;

  promociones: Promocion[] = [];
  productos: ProductoVendedor[] = [];

  formularioAbierto = false;
  editandoId: number | null = null;
  datos: SolicitudPromocion = this.vacio();
  elegidos = new Set<number>();

  ngOnInit(): void {
    this.listar();
    this.productosServicio.buscar('', '').subscribe({
      next: (productos) => (this.productos = productos.filter((producto) => producto.status !== 'RETIRED')),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  estado(promocion: Promocion): { texto: string; color: string } {
    return ESTADOS[promocion.status];
  }

  descripcionDescuento(promocion: Promocion): string {
    return promocion.discountType === 'PERCENT'
      ? `${promocion.discountValue} % de descuento`
      : `${new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP', maximumFractionDigits: 0 })
          .format(promocion.discountValue)} de descuento`;
  }

  nuevo(): void {
    this.limpiarMensajes();
    this.editandoId = null;
    this.datos = this.vacio();
    this.elegidos = new Set();
    this.formularioAbierto = true;
  }

  editar(promocion: Promocion): void {
    this.limpiarMensajes();
    this.editandoId = promocion.id;
    this.datos = {
      name: promocion.name,
      code: promocion.code,
      discountType: promocion.discountType,
      discountValue: promocion.discountValue,
      minPurchase: promocion.minPurchase,
      startsAt: promocion.startsAt.slice(0, 16),
      endsAt: promocion.endsAt.slice(0, 16),
      productIds: []
    };
    this.elegidos = new Set(promocion.productIds);
    this.formularioAbierto = true;
  }

  cancelar(): void {
    this.formularioAbierto = false;
    this.editandoId = null;
  }

  alternarProducto(id: number, elegido: boolean): void {
    if (elegido) {
      this.elegidos.add(id);
    } else {
      this.elegidos.delete(id);
    }
  }

  guardar(): void {
    const datos: SolicitudPromocion = {
      ...this.datos,
      minPurchase: this.datos.minPurchase ? this.datos.minPurchase : null,
      productIds: [...this.elegidos]
    };
    const peticion = this.editandoId
      ? this.servicio.actualizar(this.editandoId, datos)
      : this.servicio.crear(datos);
    this.ejecutar(peticion, this.editandoId ? 'Cupón modificado.' : 'Cupón creado. Actívalo cuando quieras que se pueda usar.',
      () => (this.formularioAbierto = false));
  }

  eliminar(promocion: Promocion): void {
    if (!confirm(`¿Eliminar el cupón ${promocion.code}? Esta acción no se puede deshacer.`)) {
      return;
    }
    this.ejecutar(this.servicio.eliminar(promocion.id), 'Cupón eliminado.');
  }

  ejecutar(peticion: Observable<unknown>, mensaje: string, despues?: () => void): void {
    this.limpiarMensajes();
    this.enviando = true;
    peticion.subscribe({
      next: () => {
        this.enviando = false;
        this.aviso = mensaje;
        despues?.();
        this.listar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        this.mostrarError(respuesta);
      }
    });
  }

  private listar(): void {
    this.servicio.listar().subscribe({
      next: (promociones) => (this.promociones = promociones),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  private vacio(): SolicitudPromocion {
    const ahora = new Date();
    const enUnMes = new Date(ahora.getTime() + 30 * 24 * 60 * 60 * 1000);
    return {
      name: '', code: '', discountType: 'PERCENT' as TipoDescuento, discountValue: 10, minPurchase: null,
      startsAt: this.local(ahora), endsAt: this.local(enUnMes), productIds: []
    };
  }

  /** Fecha en el formato de datetime-local (hora local, sin zona): 2026-11-14T09:30. */
  private local(fecha: Date): string {
    const dos = (n: number) => String(n).padStart(2, '0');
    return `${fecha.getFullYear()}-${dos(fecha.getMonth() + 1)}-${dos(fecha.getDate())}T${dos(fecha.getHours())}:${dos(fecha.getMinutes())}`;
  }

  private limpiarMensajes(): void {
    this.error = '';
    this.aviso = '';
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
  }
}
