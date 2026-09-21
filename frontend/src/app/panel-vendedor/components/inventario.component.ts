import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
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
  IonText,
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { ErrorFila, ItemInventario, MovimientoInventario, TipoCarga } from '../models/inventario-vendedor.model';
import { InventarioVendedorService } from '../services/inventario-vendedor.service';

/** Lo que el vendedor está haciendo con un producto: cada opción muestra su propio formulario. */
type Accion = 'entrada' | 'ajuste' | 'minimo' | 'historial';

/**
 * Inventario del vendedor (CU-15): existencias (físico, reservado y disponible), entradas de mercancía, ajustes por
 * conteo real, nivel mínimo con marca de stock bajo e historial de movimientos. Las reglas las decide el backend; aquí
 * solo se guía el proceso y se muestran sus mensajes.
 */
@Component({
  selector: 'app-inventario',
  standalone: true,
  imports: [
    DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonCheckbox, IonContent, IonHeader, IonInput, IonItem,
    IonLabel, IonList, IonText, IonTitle, IonToolbar
  ],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Inventario</ion-title>
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
      @if (erroresFilas.length > 0) {
        <ion-text color="danger">
          <ul role="alert">
            @for (fila of erroresFilas; track fila.row) {
              <li>Fila {{ fila.row }}: {{ fila.message }}</li>
            }
          </ul>
        </ion-text>
      }

      @if (cantidadBajos > 0 && !soloBajos) {
        <ion-text color="warning">
          <p role="status">
            <strong>Atención:</strong> {{ cantidadBajos }} producto(s) están en su nivel mínimo o por debajo.
            <a href="#" (click)="verSoloBajos($event)">Verlos</a>
          </p>
        </ion-text>
      }

      <!-- ===== Formulario de la acción elegida ===== -->
      @if (seleccionado && accion) {
        <div class="accion">
          <h2>{{ tituloAccion }}: {{ seleccionado.name }}</h2>
          <p class="nota">Físico {{ seleccionado.stock }} · Reservado {{ seleccionado.reserved }} · Disponible {{ seleccionado.available }}</p>

          @if (accion === 'historial') {
            <ion-list>
              @for (movimiento of movimientos; track movimiento.id) {
                <ion-item>
                  <ion-label>
                    <h3>{{ movimiento.type === 'ENTRY' ? 'Entrada' : 'Ajuste' }}: {{ movimiento.quantity > 0 ? '+' : '' }}{{ movimiento.quantity }} → {{ movimiento.stockAfter }} unidades</h3>
                    <p>{{ movimiento.createdAt | date: 'dd/MM/yyyy HH:mm' }}@if (movimiento.reason) { · {{ movimiento.reason }} }</p>
                  </ion-label>
                </ion-item>
              } @empty {
                <ion-item><ion-label>Este producto todavía no tiene movimientos.</ion-label></ion-item>
              }
            </ion-list>
            <ion-button fill="outline" (click)="cancelar()">Cerrar historial</ion-button>
          } @else {
            <form (ngSubmit)="guardar()" novalidate>
              @if (accion === 'entrada') {
                <ion-item>
                  <ion-input label="Unidades que llegaron" labelPlacement="stacked" type="number" min="1" name="cantidad" [(ngModel)]="cantidad"></ion-input>
                </ion-item>
                <ion-item>
                  <ion-input label="Motivo (opcional)" labelPlacement="stacked" name="motivo" [(ngModel)]="motivo" maxlength="255"></ion-input>
                </ion-item>
              }
              @if (accion === 'ajuste') {
                <p class="nota">Escribe el conteo real que tienes en bodega. No puede ser menor que lo reservado.</p>
                <ion-item>
                  <ion-input label="Conteo real (unidades)" labelPlacement="stacked" type="number" min="0" name="conteo" [(ngModel)]="cantidad"></ion-input>
                </ion-item>
                <ion-item>
                  <ion-input label="Motivo (obligatorio)" labelPlacement="stacked" name="motivo" [(ngModel)]="motivo" maxlength="255"></ion-input>
                </ion-item>
              }
              @if (accion === 'minimo') {
                <p class="nota">Recibirás un aviso cuando el stock llegue a este nivel o baje de él. Con 0 no se avisa.</p>
                <ion-item>
                  <ion-input label="Nivel mínimo (unidades)" labelPlacement="stacked" type="number" min="0" name="minimo" [(ngModel)]="cantidad"></ion-input>
                </ion-item>
              }
              <ion-button type="submit" [disabled]="enviando || cantidad === null">Guardar</ion-button>
              <ion-button fill="outline" type="button" (click)="cancelar()">Cancelar</ion-button>
            </form>
          }
        </div>
      }

      <!-- ===== Cargas masivas con Excel ===== -->
      <div class="excel">
        <h2>Cargas masivas con Excel</h2>
        <p class="nota">Descarga la plantilla, llénala y súbela. Si alguna fila tiene errores no se guarda nada y verás qué corregir.</p>
        <ion-button size="small" fill="outline" (click)="descargar('products')">Plantilla de productos nuevos</ion-button>
        <ion-button size="small" fill="outline" [disabled]="enviando" (click)="entradaProductos.click()">Cargar productos</ion-button>
        <input #entradaProductos type="file" hidden accept=".xlsx" (change)="cargar('products', $event)" />
        <ion-button size="small" fill="outline" (click)="descargar('stock')">Plantilla de inventario</ion-button>
        <ion-button size="small" fill="outline" [disabled]="enviando" (click)="entradaInventario.click()">Cargar inventario</ion-button>
        <input #entradaInventario type="file" hidden accept=".xlsx" (change)="cargar('stock', $event)" />
      </div>

      <!-- ===== Existencias ===== -->
      <form class="filtros" (ngSubmit)="listar()">
        <ion-item>
          <ion-input label="Buscar por nombre o categoría" labelPlacement="stacked" name="texto" [(ngModel)]="texto"></ion-input>
        </ion-item>
        <ion-item>
          <ion-checkbox name="soloBajos" [ngModel]="soloBajos" (ngModelChange)="cambiarSoloBajos($event)">Solo bajo el mínimo</ion-checkbox>
        </ion-item>
        <ion-button type="submit" fill="outline">Buscar</ion-button>
      </form>

      <ion-list>
        @for (item of items; track item.productId) {
          <ion-item>
            <ion-label>
              <h2>{{ item.name }} @if (item.lowStock) { <ion-badge color="danger">Stock bajo</ion-badge> }</h2>
              <p>Físico {{ item.stock }} · Reservado {{ item.reserved }} · <strong>Disponible {{ item.available }}</strong> ·
                Mínimo {{ item.minStock === 0 ? 'sin aviso' : item.minStock }} · {{ item.category }}</p>
              <p>
                <ion-button size="small" fill="clear" (click)="elegir(item, 'entrada')">Registrar entrada</ion-button>
                <ion-button size="small" fill="clear" (click)="elegir(item, 'ajuste')">Ajustar conteo</ion-button>
                <ion-button size="small" fill="clear" (click)="elegir(item, 'minimo')">Mínimo</ion-button>
                <ion-button size="small" fill="clear" (click)="elegir(item, 'historial')">Historial</ion-button>
              </p>
            </ion-label>
          </ion-item>
        } @empty {
          <ion-item><ion-label>No hay productos que mostrar.</ion-label></ion-item>
        }
      </ion-list>
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .filtros { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 0.75rem; margin-bottom: 1rem; }
    .accion { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 1rem; margin-bottom: 1.5rem; }
    .excel { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 1rem; margin-bottom: 1.5rem; }
    .nota { color: #666; font-size: 0.9rem; }
  `]
})
export class InventarioComponent implements OnInit {
  private readonly servicio = inject(InventarioVendedorService);

  @Output() cerrar = new EventEmitter<void>();

  error = '';
  aviso = '';
  /** Errores por fila de la última carga de Excel rechazada. */
  erroresFilas: ErrorFila[] = [];
  enviando = false;

  items: ItemInventario[] = [];
  /** Cuántos de los productos mostrados están bajo el mínimo (alimenta el aviso de arriba). */
  cantidadBajos = 0;
  texto = '';
  soloBajos = false;

  seleccionado: ItemInventario | null = null;
  accion: Accion | null = null;
  /** Unidades de la acción elegida: las que llegaron, el conteo real o el nuevo mínimo, según el caso. */
  cantidad: number | null = null;
  motivo = '';
  movimientos: MovimientoInventario[] = [];

  ngOnInit(): void {
    this.listar();
  }

  get tituloAccion(): string {
    switch (this.accion) {
      case 'entrada': return 'Registrar entrada';
      case 'ajuste': return 'Ajustar conteo';
      case 'minimo': return 'Nivel mínimo';
      default: return 'Historial';
    }
  }

  // ---------- Lista ----------

  listar(): void {
    this.servicio.listar(this.texto, this.soloBajos).subscribe({
      next: items => {
        this.items = items;
        this.cantidadBajos = items.filter(item => item.lowStock).length;
      },
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  cambiarSoloBajos(valor: boolean): void {
    this.soloBajos = valor;
    this.listar();
  }

  verSoloBajos(evento: Event): void {
    evento.preventDefault();
    this.cambiarSoloBajos(true);
  }

  // ---------- Acciones sobre un producto ----------

  elegir(item: ItemInventario, accion: Accion): void {
    this.limpiarMensajes();
    this.seleccionado = item;
    this.accion = accion;
    this.motivo = '';
    // El mínimo parte de su valor actual; las demás acciones, de un campo vacío.
    this.cantidad = accion === 'minimo' ? item.minStock : null;
    if (accion === 'historial') {
      this.servicio.historial(item.productId).subscribe({
        next: movimientos => (this.movimientos = movimientos),
        error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
      });
    }
  }

  cancelar(): void {
    this.seleccionado = null;
    this.accion = null;
  }

  guardar(): void {
    if (!this.seleccionado || this.cantidad === null) {
      return;
    }
    const id = this.seleccionado.productId;
    const cantidad = Number(this.cantidad);
    if (this.accion === 'entrada') {
      this.tramitar(this.servicio.registrarEntrada(id, cantidad, this.motivo), 'Entrada registrada.');
    } else if (this.accion === 'ajuste') {
      this.tramitar(this.servicio.registrarAjuste(id, cantidad, this.motivo), 'Ajuste registrado.');
    } else if (this.accion === 'minimo') {
      this.tramitar(this.servicio.configurarMinimo(id, cantidad), 'Nivel mínimo guardado.');
    }
  }

  /** Ejecuta la llamada, muestra el mensaje del backend si la rechaza y recarga la lista al terminar bien. */
  private tramitar(operacion: Observable<ItemInventario>, mensaje: string): void {
    this.limpiarMensajes();
    this.enviando = true;
    operacion.subscribe({
      next: () => {
        this.enviando = false;
        this.aviso = mensaje;
        this.cancelar();
        this.listar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        this.mostrarError(respuesta);
      }
    });
  }

  // ---------- Cargas masivas con Excel ----------

  descargar(tipo: TipoCarga): void {
    this.limpiarMensajes();
    const nombre = tipo === 'products' ? 'plantilla-productos.xlsx' : 'plantilla-inventario.xlsx';
    this.servicio.descargarPlantilla(tipo).subscribe({
      next: contenido => this.guardarArchivo(contenido, nombre),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  cargar(tipo: TipoCarga, evento: Event): void {
    const entrada = evento.target as HTMLInputElement;
    const archivo = entrada.files?.[0];
    entrada.value = ''; // permite volver a elegir el mismo archivo después de corregirlo
    if (!archivo) {
      return;
    }
    this.limpiarMensajes();
    this.enviando = true;
    this.servicio.cargar(tipo, archivo).subscribe({
      next: resultado => {
        this.enviando = false;
        this.aviso = tipo === 'products'
          ? `Se crearon ${resultado.created} producto(s) y ${resultado.published} quedaron publicados.`
          : `Se aplicaron ${resultado.applied} movimiento(s) de inventario.`;
        this.listar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.enviando = false;
        this.mostrarError(respuesta);
      }
    });
  }

  /** Entrega al navegador el archivo descargado, como si se hubiera pulsado un enlace de descarga. */
  private guardarArchivo(contenido: Blob, nombre: string): void {
    const direccion = URL.createObjectURL(contenido);
    const enlace = document.createElement('a');
    enlace.href = direccion;
    enlace.download = nombre;
    enlace.click();
    URL.revokeObjectURL(direccion);
  }

  private limpiarMensajes(): void {
    this.error = '';
    this.aviso = '';
    this.erroresFilas = [];
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
    // Una carga de Excel rechazada trae la lista de filas con su error en "details".
    this.erroresFilas = Array.isArray(respuesta.error?.details) ? respuesta.error.details : [];
  }
}
