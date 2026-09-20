import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
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
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { CatalogoAdminService } from '../services/catalogo-admin.service';
import { Atributo, FilaCategoria, Marca, NodoCategoria } from '../models/catalogo-admin.model';

type Pestana = 'categorias' | 'marcas' | 'atributos';

/**
 * Administración de la estructura del catálogo (CU-17): categorías con subcategorías, marcas y
 * atributos con sus valores permitidos. Las reglas las decide el backend; aquí solo se muestran
 * sus mensajes cuando rechaza una operación.
 */
@Component({
  selector: 'app-catalogo-admin',
  standalone: true,
  imports: [
    FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonInput, IonItem, IonLabel,
    IonList, IonListHeader, IonSelect, IonSelectOption, IonText, IonTitle, IonToolbar
  ],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Estructura del catálogo</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      <div class="pestanas">
        <ion-button [fill]="pestana === 'categorias' ? 'solid' : 'outline'" (click)="pestana = 'categorias'">Categorías</ion-button>
        <ion-button [fill]="pestana === 'marcas' ? 'solid' : 'outline'" (click)="pestana = 'marcas'">Marcas</ion-button>
        <ion-button [fill]="pestana === 'atributos' ? 'solid' : 'outline'" (click)="pestana = 'atributos'">Atributos</ion-button>
      </div>

      @if (error) {
        <ion-text color="danger"><p role="alert">{{ error }}</p></ion-text>
      }
      @if (aviso) {
        <ion-text color="success"><p role="status">{{ aviso }}</p></ion-text>
      }

      @if (pestana === 'categorias') {
        <form (ngSubmit)="guardarCategoria()">
          <ion-item>
            <ion-input label="Nombre" labelPlacement="stacked" name="nombreCategoria"
              [(ngModel)]="nombreCategoria" maxlength="100"></ion-input>
          </ion-item>
          <ion-item>
            <ion-select label="Dentro de" labelPlacement="stacked" name="padreCategoria" [(ngModel)]="padreCategoria">
              <ion-select-option [value]="null">(ninguna: categoría principal)</ion-select-option>
              @for (fila of categorias; track fila.id) {
                <ion-select-option [value]="fila.id">{{ etiqueta(fila) }}</ion-select-option>
              }
            </ion-select>
          </ion-item>
          <ion-button type="submit">{{ editandoId === null ? 'Crear categoría' : 'Guardar cambios' }}</ion-button>
          @if (editandoId !== null) {
            <ion-button fill="outline" (click)="cancelarEdicion()">Cancelar</ion-button>
          }
        </form>

        <ion-list>
          <ion-list-header>Estructura actual</ion-list-header>
          @for (fila of categorias; track fila.id) {
            <ion-item [style.padding-left.rem]="fila.nivel * 1.5">
              <ion-label>{{ fila.name }}</ion-label>
              <ion-badge slot="end" [color]="fila.active ? 'success' : 'medium'">{{ fila.active ? 'Activa' : 'Inactiva' }}</ion-badge>
              <ion-button slot="end" size="small" fill="clear" (click)="editarCategoria(fila)">Editar</ion-button>
              <ion-button slot="end" size="small" fill="clear"
                (click)="cambiarEstadoCategoria(fila)">{{ fila.active ? 'Desactivar' : 'Activar' }}</ion-button>
              <ion-button slot="end" size="small" fill="clear" color="danger"
                (click)="eliminarCategoria(fila)">Eliminar</ion-button>
            </ion-item>
          } @empty {
            <ion-item><ion-label>Todavía no hay categorías.</ion-label></ion-item>
          }
        </ion-list>
      }

      @if (pestana === 'marcas') {
        <form (ngSubmit)="crearMarca()">
          <ion-item>
            <ion-input label="Nombre de la marca" labelPlacement="stacked" name="nombreMarca"
              [(ngModel)]="nombreMarca" maxlength="100"></ion-input>
          </ion-item>
          <ion-button type="submit">Registrar marca</ion-button>
        </form>

        <ion-list>
          <ion-list-header>Marcas</ion-list-header>
          @for (marca of marcas; track marca.id) {
            <ion-item>
              <ion-label>{{ marca.name }}</ion-label>
              <ion-badge slot="end" [color]="marca.active ? 'success' : 'medium'">{{ marca.active ? 'Activa' : 'Inactiva' }}</ion-badge>
              <ion-button slot="end" size="small" fill="clear"
                (click)="cambiarEstadoMarca(marca)">{{ marca.active ? 'Desactivar' : 'Activar' }}</ion-button>
              <ion-button slot="end" size="small" fill="clear" color="danger"
                (click)="eliminarMarca(marca)">Eliminar</ion-button>
            </ion-item>
          } @empty {
            <ion-item><ion-label>Todavía no hay marcas.</ion-label></ion-item>
          }
        </ion-list>
      }

      @if (pestana === 'atributos') {
        <form (ngSubmit)="crearAtributo()">
          <ion-item>
            <ion-input label="Nombre del atributo (por ejemplo, Color)" labelPlacement="stacked"
              name="nombreAtributo" [(ngModel)]="nombreAtributo" maxlength="100"></ion-input>
          </ion-item>
          <ion-button type="submit">Crear atributo</ion-button>
        </form>

        @for (atributo of atributos; track atributo.id) {
          <ion-list>
            <ion-list-header>
              {{ atributo.name }}
              <ion-button size="small" fill="clear" color="danger" (click)="eliminarAtributo(atributo)">Eliminar atributo</ion-button>
            </ion-list-header>
            @for (valor of atributo.values; track valor.id) {
              <ion-item>
                <ion-label>{{ valor.value }}</ion-label>
                <ion-button slot="end" size="small" fill="clear" color="danger"
                  (click)="eliminarValor(atributo, valor.id)">Quitar</ion-button>
              </ion-item>
            } @empty {
              <ion-item><ion-label>Sin valores permitidos todavía.</ion-label></ion-item>
            }
            <ion-item>
              <ion-input label="Nuevo valor" labelPlacement="stacked" [name]="'valor' + atributo.id"
                [(ngModel)]="valoresNuevos[atributo.id]" maxlength="100"></ion-input>
              <ion-button slot="end" size="small" (click)="agregarValor(atributo)">Agregar</ion-button>
            </ion-item>
          </ion-list>
        } @empty {
          <p>Todavía no hay atributos.</p>
        }
      }
    </ion-content>
  `,
  styles: [`
    .pestanas { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 1rem; }
    form { margin-bottom: 1.5rem; }
  `]
})
export class CatalogoAdminComponent implements OnInit {
  private readonly servicio = inject(CatalogoAdminService);

  @Output() cerrar = new EventEmitter<void>();

  pestana: Pestana = 'categorias';
  error = '';
  aviso = '';

  categorias: FilaCategoria[] = [];
  marcas: Marca[] = [];
  atributos: Atributo[] = [];

  // Formulario de categorías: sirve para crear y también para editar (editandoId != null).
  nombreCategoria = '';
  padreCategoria: number | null = null;
  editandoId: number | null = null;

  nombreMarca = '';
  nombreAtributo = '';
  valoresNuevos: Record<number, string> = {};

  ngOnInit(): void {
    this.recargar();
  }

  // ---------- Categorías ----------

  etiqueta(fila: FilaCategoria): string {
    return '— '.repeat(fila.nivel) + fila.name;
  }

  guardarCategoria(): void {
    const accion = this.editandoId === null
      ? this.servicio.crearCategoria(this.nombreCategoria, this.padreCategoria)
      : this.servicio.actualizarCategoria(this.editandoId, this.nombreCategoria, this.padreCategoria);
    this.ejecutar(accion, this.editandoId === null ? 'Categoría creada.' : 'Categoría actualizada.', () => this.cancelarEdicion());
  }

  editarCategoria(fila: FilaCategoria): void {
    this.editandoId = fila.id;
    this.nombreCategoria = fila.name;
    this.padreCategoria = fila.parentId;
  }

  cancelarEdicion(): void {
    this.editandoId = null;
    this.nombreCategoria = '';
    this.padreCategoria = null;
  }

  cambiarEstadoCategoria(fila: FilaCategoria): void {
    this.ejecutar(this.servicio.cambiarEstadoCategoria(fila.id, !fila.active),
      fila.active ? 'Categoría desactivada.' : 'Categoría activada.');
  }

  eliminarCategoria(fila: FilaCategoria): void {
    this.ejecutar(this.servicio.eliminarCategoria(fila.id), 'Categoría eliminada.');
  }

  // ---------- Marcas ----------

  crearMarca(): void {
    this.ejecutar(this.servicio.crearMarca(this.nombreMarca), 'Marca registrada.', () => (this.nombreMarca = ''));
  }

  cambiarEstadoMarca(marca: Marca): void {
    this.ejecutar(this.servicio.cambiarEstadoMarca(marca.id, !marca.active),
      marca.active ? 'Marca desactivada.' : 'Marca activada.');
  }

  eliminarMarca(marca: Marca): void {
    this.ejecutar(this.servicio.eliminarMarca(marca.id), 'Marca eliminada.');
  }

  // ---------- Atributos ----------

  crearAtributo(): void {
    this.ejecutar(this.servicio.crearAtributo(this.nombreAtributo), 'Atributo creado.', () => (this.nombreAtributo = ''));
  }

  eliminarAtributo(atributo: Atributo): void {
    this.ejecutar(this.servicio.eliminarAtributo(atributo.id), 'Atributo eliminado.');
  }

  agregarValor(atributo: Atributo): void {
    this.ejecutar(this.servicio.agregarValor(atributo.id, this.valoresNuevos[atributo.id] ?? ''),
      'Valor agregado.', () => (this.valoresNuevos[atributo.id] = ''));
  }

  eliminarValor(atributo: Atributo, valorId: number): void {
    this.ejecutar(this.servicio.eliminarValor(atributo.id, valorId), 'Valor eliminado.');
  }

  // ---------- Comunes ----------

  /** Ejecuta una operación, muestra su resultado (o el mensaje del backend) y recarga las listas. */
  private ejecutar(operacion: Observable<unknown>, mensaje: string, alTerminar?: () => void): void {
    this.error = '';
    this.aviso = '';
    operacion.subscribe({
      next: () => {
        this.aviso = mensaje;
        alTerminar?.();
        this.recargar();
      },
      error: (respuesta: HttpErrorResponse) => {
        this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
      }
    });
  }

  private recargar(): void {
    this.servicio.obtenerCategorias().subscribe(arbol => (this.categorias = this.aplanar(arbol, null, 0)));
    this.servicio.obtenerMarcas().subscribe(marcas => (this.marcas = marcas));
    this.servicio.obtenerAtributos().subscribe(atributos => (this.atributos = atributos));
  }

  /** Convierte el árbol en una lista con el nivel de cada categoría, para mostrarla con sangría. */
  private aplanar(nodos: NodoCategoria[], parentId: number | null, nivel: number): FilaCategoria[] {
    return nodos.flatMap(nodo => [
      { id: nodo.id, name: nodo.name, active: nodo.active, parentId, nivel },
      ...this.aplanar(nodo.children, nodo.id, nivel + 1)
    ]);
  }
}
