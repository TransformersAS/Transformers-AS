import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import {
  IonBadge,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonInput,
  IonItem,
  IonLabel,
  IonList,
  IonListHeader,
  IonSelect,
  IonSelectOption,
  IonText,
  IonTextarea,
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { Marca, NodoCategoria } from '../../panel-admin-catalogo/models/catalogo-admin.model';
import {
  AccionProducto,
  EstadoProducto,
  ProductoVendedor,
  SolicitudProducto,
  VarianteProducto
} from '../models/producto-vendedor.model';
import { ProductosVendedorService } from '../services/productos-vendedor.service';

const ETIQUETA_ESTADO: Record<EstadoProducto, string> = {
  DRAFT: 'Borrador',
  ACTIVE: 'Activo',
  PAUSED: 'Pausado',
  RETIRED: 'Retirado'
};

const COLOR_ESTADO: Record<EstadoProducto, string> = {
  DRAFT: 'medium',
  ACTIVE: 'success',
  PAUSED: 'warning',
  RETIRED: 'danger'
};

/** Lo que se escribe en el formulario; se convierte en SolicitudProducto al guardar. */
interface Formulario {
  nombre: string;
  descripcion: string;
  precio: number | null;
  inventario: number | null;
  categoria: string;
  marcaId: number | null;
  imagenes: string; // una dirección por línea
  atributos: number[];
  variantes: { nombre: string; precio: number | null; inventario: number | null }[];
}

/**
 * Productos del vendedor (CU-14): lista con búsqueda, formulario para crear y editar, vista previa y las acciones
 * publicar, pausar, reactivar, retirar y duplicar. Las reglas las decide el backend; aquí solo se ocultan las
 * acciones que el estado del producto no permite y se muestran sus mensajes.
 */
@Component({
  selector: 'app-mis-productos',
  standalone: true,
  imports: [
    CurrencyPipe, FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonInput, IonItem, IonLabel,
    IonList, IonListHeader, IonSelect, IonSelectOption, IonText, IonTextarea, IonTitle, IonToolbar
  ],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Mis productos</ion-title>
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

      <!-- ===== Formulario de creación y edición ===== -->
      @if (mostrarFormulario) {
        <form (ngSubmit)="guardar()">
          <h2>{{ editandoId === null ? 'Nuevo producto (quedará como borrador)' : 'Editar producto' }}</h2>
          <ion-item>
            <ion-input label="Nombre" labelPlacement="stacked" name="nombre" [(ngModel)]="formulario.nombre" maxlength="255"></ion-input>
          </ion-item>
          <ion-item>
            <ion-textarea label="Descripción" labelPlacement="stacked" name="descripcion" [(ngModel)]="formulario.descripcion" maxlength="255"></ion-textarea>
          </ion-item>
          <ion-item>
            <ion-input label="Precio (COP)" labelPlacement="stacked" type="number" min="0" name="precio" [(ngModel)]="formulario.precio"></ion-input>
          </ion-item>
          <ion-item>
            <ion-input label="Inventario (unidades)" labelPlacement="stacked" type="number" min="0" name="inventario" [(ngModel)]="formulario.inventario"></ion-input>
          </ion-item>
          <ion-item>
            <ion-select label="Categoría" labelPlacement="stacked" name="categoria" [(ngModel)]="formulario.categoria">
              @for (categoria of categorias; track categoria) {
                <ion-select-option [value]="categoria">{{ categoria }}</ion-select-option>
              }
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-select label="Marca (opcional)" labelPlacement="stacked" name="marca" [(ngModel)]="formulario.marcaId">
              <ion-select-option [value]="null">Sin marca</ion-select-option>
              @for (marca of marcas; track marca.id) {
                <ion-select-option [value]="marca.id">{{ marca.name }}</ion-select-option>
              }
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-textarea label="Imágenes (una dirección por línea; la primera es la principal)" labelPlacement="stacked"
              name="imagenes" [(ngModel)]="formulario.imagenes" [autoGrow]="true"></ion-textarea>
          </ion-item>
          <ion-item>
            <ion-select label="Atributos (opcional)" labelPlacement="stacked" name="atributos" [multiple]="true"
              [(ngModel)]="formulario.atributos">
              @for (opcion of opcionesAtributo; track opcion.id) {
                <ion-select-option [value]="opcion.id">{{ opcion.etiqueta }}</ion-select-option>
              }
            </ion-select>
          </ion-item>

          <ion-list>
            <ion-list-header>Variantes (opcional)</ion-list-header>
            @for (variante of formulario.variantes; track $index) {
              <ion-item>
                <ion-input label="Nombre" labelPlacement="stacked" [name]="'vn' + $index" [(ngModel)]="variante.nombre"></ion-input>
                <ion-input label="Precio" labelPlacement="stacked" type="number" min="0" [name]="'vp' + $index" [(ngModel)]="variante.precio"></ion-input>
                <ion-input label="Inventario" labelPlacement="stacked" type="number" min="0" [name]="'vi' + $index" [(ngModel)]="variante.inventario"></ion-input>
                <ion-button slot="end" size="small" fill="clear" color="danger" (click)="quitarVariante($index)">Quitar</ion-button>
              </ion-item>
            }
            <ion-item>
              <ion-button size="small" fill="outline" (click)="agregarVariante()">Agregar variante</ion-button>
            </ion-item>
          </ion-list>

          <ion-button type="submit">Guardar</ion-button>
          <ion-button fill="outline" (click)="cancelar()">Cancelar</ion-button>
        </form>
      }

      <!-- ===== Vista previa ===== -->
      @if (vistaPrevia) {
        <div class="previa">
          <h2>Vista previa</h2>
          @if (vistaPrevia.imageUrls.length > 0) {
            <img [src]="vistaPrevia.imageUrls[0]" alt="Imagen principal del producto">
          } @else {
            <p>Sin imágenes todavía: agrega al menos una para poder publicar.</p>
          }
          <h3>{{ vistaPrevia.name }}</h3>
          <p>{{ vistaPrevia.description }}</p>
          <p><strong>{{ vistaPrevia.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</strong>
            · {{ vistaPrevia.stock }} unidades · Categoría: {{ vistaPrevia.category }}
            @if (vistaPrevia.brandId !== null) { · Marca: {{ nombreMarca(vistaPrevia.brandId) }} }</p>
          @if (vistaPrevia.attributeValueIds.length > 0) {
            <p>Atributos: {{ etiquetasAtributos(vistaPrevia.attributeValueIds) }}</p>
          }
          @for (variante of vistaPrevia.variants; track $index) {
            <p>Variante {{ variante.name }}: {{ variante.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} · {{ variante.stock }} unidades</p>
          }
          <p>Estado: <ion-badge [color]="color(vistaPrevia.status)">{{ etiqueta(vistaPrevia.status) }}</ion-badge></p>
          <ion-button fill="outline" (click)="vistaPrevia = null">Cerrar vista previa</ion-button>
        </div>
      }

      <!-- ===== Lista y búsqueda ===== -->
      @if (!mostrarFormulario) {
        <div class="filtros">
          <ion-button (click)="nuevo()">Nuevo producto</ion-button>
          <form class="buscar" (ngSubmit)="buscar()">
            <ion-item>
              <ion-input label="Buscar por nombre o categoría" labelPlacement="stacked" name="texto" [(ngModel)]="texto"></ion-input>
            </ion-item>
            <ion-item>
              <ion-select label="Estado" labelPlacement="stacked" name="estadoFiltro" [(ngModel)]="estadoFiltro" (ionChange)="buscar()">
                <ion-select-option value="">Todos</ion-select-option>
                <ion-select-option value="DRAFT">Borrador</ion-select-option>
                <ion-select-option value="ACTIVE">Activo</ion-select-option>
                <ion-select-option value="PAUSED">Pausado</ion-select-option>
                <ion-select-option value="RETIRED">Retirado</ion-select-option>
              </ion-select>
            </ion-item>
            <ion-button type="submit" fill="outline">Buscar</ion-button>
          </form>
        </div>

        <ion-list>
          @for (producto of productos; track producto.id) {
            <ion-item>
              <ion-label>
                <h2>{{ producto.name }}</h2>
                <p>{{ producto.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} · {{ producto.stock }} unidades · {{ producto.category }}</p>
                <p>
                  <ion-badge [color]="color(producto.status)">{{ etiqueta(producto.status) }}</ion-badge>
                  <ion-button size="small" fill="clear" (click)="verPrevia(producto)">Vista previa</ion-button>
                  @if (producto.status !== 'RETIRED') {
                    <ion-button size="small" fill="clear" (click)="editar(producto)">Editar</ion-button>
                  }
                  @if (producto.status === 'DRAFT') {
                    <ion-button size="small" fill="clear" (click)="ejecutar(producto, 'publish', 'Producto publicado.')">Publicar</ion-button>
                  }
                  @if (producto.status === 'ACTIVE') {
                    <ion-button size="small" fill="clear" (click)="ejecutar(producto, 'pause', 'Producto pausado.')">Pausar</ion-button>
                  }
                  @if (producto.status === 'PAUSED') {
                    <ion-button size="small" fill="clear" (click)="ejecutar(producto, 'reactivate', 'Producto reactivado.')">Reactivar</ion-button>
                  }
                  <ion-button size="small" fill="clear" (click)="ejecutar(producto, 'duplicate', 'Copia creada como borrador.')">Duplicar</ion-button>
                  @if (producto.status !== 'RETIRED') {
                    <ion-button size="small" fill="clear" color="danger" (click)="ejecutar(producto, 'retire', 'Producto retirado.')">Retirar</ion-button>
                  }
                </p>
              </ion-label>
            </ion-item>
          } @empty {
            <ion-item><ion-label>No hay productos que mostrar.</ion-label></ion-item>
          }
        </ion-list>
      }
    </ion-content>
  `,
  styles: [`
    .filtros { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 0.75rem; margin-bottom: 1rem; }
    .buscar { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 0.5rem; }
    form { margin-bottom: 1.5rem; }
    .previa { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 1rem; margin-bottom: 1.5rem; }
    .previa img { max-width: 16rem; max-height: 16rem; border-radius: 0.5rem; }
  `]
})
export class MisProductosComponent implements OnInit {
  private readonly servicio = inject(ProductosVendedorService);

  @Output() cerrar = new EventEmitter<void>();

  error = '';
  aviso = '';

  productos: ProductoVendedor[] = [];
  texto = '';
  estadoFiltro: EstadoProducto | '' = '';

  // Estructura del catálogo (CU-17) para llenar las listas del formulario.
  categorias: string[] = [];
  marcas: Marca[] = [];
  opcionesAtributo: { id: number; etiqueta: string }[] = [];

  mostrarFormulario = false;
  editandoId: number | null = null;
  formulario: Formulario = this.formularioVacio();
  vistaPrevia: ProductoVendedor | null = null;

  ngOnInit(): void {
    this.cargarEstructura();
    this.buscar();
  }

  // ---------- Textos ----------

  etiqueta(estado: EstadoProducto): string {
    return ETIQUETA_ESTADO[estado];
  }

  color(estado: EstadoProducto): string {
    return COLOR_ESTADO[estado];
  }

  nombreMarca(id: number): string {
    return this.marcas.find(marca => marca.id === id)?.name ?? `#${id}`;
  }

  etiquetasAtributos(ids: number[]): string {
    return this.opcionesAtributo.filter(opcion => ids.includes(opcion.id)).map(opcion => opcion.etiqueta).join(', ');
  }

  // ---------- Lista ----------

  buscar(): void {
    this.servicio.buscar(this.texto, this.estadoFiltro).subscribe({
      next: productos => (this.productos = productos),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  verPrevia(producto: ProductoVendedor): void {
    this.vistaPrevia = producto;
  }

  /** Publicar, pausar, reactivar, retirar o duplicar; después se recarga la lista. */
  ejecutar(producto: ProductoVendedor, accion: AccionProducto, mensaje: string): void {
    this.tramitar(this.servicio.ejecutar(producto.id, accion), mensaje);
  }

  // ---------- Formulario ----------

  nuevo(): void {
    this.editandoId = null;
    this.formulario = this.formularioVacio();
    this.mostrarFormulario = true;
    this.vistaPrevia = null;
  }

  editar(producto: ProductoVendedor): void {
    this.editandoId = producto.id;
    this.formulario = {
      nombre: producto.name,
      descripcion: producto.description ?? '',
      precio: producto.price,
      inventario: producto.stock,
      categoria: producto.category,
      marcaId: producto.brandId,
      imagenes: producto.imageUrls.join('\n'),
      atributos: [...producto.attributeValueIds],
      variantes: producto.variants.map(variante => ({
        nombre: variante.name, precio: variante.price, inventario: variante.stock
      }))
    };
    this.mostrarFormulario = true;
    this.vistaPrevia = null;
  }

  cancelar(): void {
    this.mostrarFormulario = false;
  }

  agregarVariante(): void {
    this.formulario.variantes.push({ nombre: '', precio: null, inventario: null });
  }

  quitarVariante(indice: number): void {
    this.formulario.variantes.splice(indice, 1);
  }

  guardar(): void {
    const datos = this.aSolicitud();
    const operacion = this.editandoId === null
      ? this.servicio.crear(datos)
      : this.servicio.actualizar(this.editandoId, datos);
    this.tramitar(operacion, this.editandoId === null ? 'Producto creado como borrador.' : 'Cambios guardados.',
      () => (this.mostrarFormulario = false));
  }

  // ---------- Comunes ----------

  private formularioVacio(): Formulario {
    return {
      nombre: '', descripcion: '', precio: null, inventario: null, categoria: '', marcaId: null,
      imagenes: '', atributos: [], variantes: []
    };
  }

  /** Convierte lo escrito en el formulario en el cuerpo que espera el backend. */
  private aSolicitud(): SolicitudProducto {
    const variantes: VarianteProducto[] = this.formulario.variantes.map(variante => ({
      name: variante.nombre, price: Number(variante.precio ?? 0), stock: Number(variante.inventario ?? 0)
    }));
    return {
      name: this.formulario.nombre,
      description: this.formulario.descripcion,
      price: Number(this.formulario.precio ?? 0),
      stock: Number(this.formulario.inventario ?? 0),
      category: this.formulario.categoria,
      brandId: this.formulario.marcaId,
      imageUrls: this.formulario.imagenes.split('\n').map(linea => linea.trim()).filter(linea => linea !== ''),
      attributeValueIds: this.formulario.atributos,
      variants: variantes
    };
  }

  /** Ejecuta una operación, muestra su resultado (o el mensaje del backend) y recarga la lista. */
  private tramitar(operacion: Observable<unknown>, mensaje: string, alTerminar?: () => void): void {
    this.error = '';
    this.aviso = '';
    operacion.subscribe({
      next: () => {
        this.aviso = mensaje;
        alTerminar?.();
        this.buscar();
      },
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
  }

  private cargarEstructura(): void {
    this.servicio.obtenerCategorias().subscribe(arbol => (this.categorias = this.aplanar(arbol)));
    this.servicio.obtenerMarcas().subscribe(marcas => (this.marcas = marcas));
    this.servicio.obtenerAtributos().subscribe(atributos => {
      this.opcionesAtributo = atributos.flatMap(atributo => atributo.values.map(valor => ({
        id: valor.id, etiqueta: `${atributo.name}: ${valor.value}`
      })));
    });
  }

  /** Los productos guardan el nombre de la categoría, así que basta una lista plana con todos los niveles. */
  private aplanar(nodos: NodoCategoria[]): string[] {
    return nodos.flatMap(nodo => [nodo.name, ...this.aplanar(nodo.children)]);
  }
}
