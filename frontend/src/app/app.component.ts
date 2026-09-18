import {
  ChangeDetectionStrategy,
  Component,
  inject
} from '@angular/core';

import {
  AsyncPipe,
  CurrencyPipe,
  NgFor,
  NgIf
} from '@angular/common';

import {
  IonApp,
  IonContent,
  IonIcon
} from '@ionic/angular/standalone';

import { addIcons } from 'ionicons';

import {
  arrowForwardOutline,
  bagHandleOutline,
  bicycleOutline,
  chatbubbleEllipsesOutline,
  chevronForwardOutline,
  flashOutline,
  heartOutline,
  homeOutline,
  personCircleOutline,
  searchOutline,
  shirtOutline,
  sparklesOutline,
  star
} from 'ionicons/icons';

import { CatalogoService } from './catalogo/services/catalogo.service';
import { CarritoService } from './carrito/services/carrito.service';
import { AuthService } from './core/services/auth.service';

import { ItemCarrito } from './carrito/models/carrito.model';


@Component({
  selector: 'app-root',

  standalone: true,

  changeDetection: ChangeDetectionStrategy.OnPush,

  imports: [
    IonApp,
    IonContent,
    IonIcon,
    AsyncPipe,
    CurrencyPipe,
    NgFor,
    NgIf
  ],

  templateUrl: './app.component.html',

  styleUrl: './app.component.scss'
})
export class AppComponent {

  // =========================
  // SERVICIOS
  // =========================

  private readonly catalogo =
    inject(CatalogoService);

  private readonly carrito =
    inject(CarritoService);

  private readonly auth =
    inject(AuthService);


  // =========================
  // CATÁLOGO
  // =========================

  readonly destacados$ =
    this.catalogo.obtenerDestacados();

  readonly categorias$ =
    this.catalogo.obtenerCategorias();


  // =========================
  // CARRITO
  // =========================

  readonly items$ =
    this.carrito.items$;

  readonly total$ =
    this.carrito.total$;

  mostrarCarrito = false;


  // =========================
  // USUARIO
  // =========================

  readonly nombre =
    this.auth.obtenerNombreVisible();


  constructor() {

    // Registrar únicamente los iconos utilizados.
    addIcons({
      bagHandleOutline,
      chatbubbleEllipsesOutline,
      chevronForwardOutline,
      homeOutline,
      searchOutline,
      shirtOutline,
      sparklesOutline,
      bicycleOutline,
      heartOutline,
      star,
      arrowForwardOutline,
      flashOutline,
      personCircleOutline
    });
  }


  // =========================
  // AGREGAR PRODUCTO
  // =========================

  agregarAlCarrito(id: number): void {

    this.carrito.agregar(id);
  }


  // =========================
  // CONTADOR DEL CARRITO
  // =========================

  cantidadCarrito(
    items: readonly { cantidad: number }[]
  ): number {

    return items.reduce(
      (total, item) =>
        total + item.cantidad,
      0
    );
  }


  // =========================
  // ABRIR / CERRAR CARRITO
  // =========================

  toggleCarrito(): void {

    this.mostrarCarrito =
      !this.mostrarCarrito;

    /*
     * Cada vez que abrimos el carrito,
     * consultamos nuevamente el backend.
     */
    if (this.mostrarCarrito) {

      this.carrito.refrescar();
    }
  }


  cerrarCarrito(): void {

    this.mostrarCarrito = false;
  }


  // =========================
  // MODIFICAR CANTIDADES
  // =========================

  aumentarCantidad(
    item: ItemCarrito
  ): void {

    this.carrito.aumentar(item);
  }


  disminuirCantidad(
    item: ItemCarrito
  ): void {

    this.carrito.disminuir(item);
  }


  // =========================
  // ELIMINAR PRODUCTO
  // =========================

  eliminarDelCarrito(
    itemId: number
  ): void {

    this.carrito.eliminar(itemId);
  }


  // =========================
  // CHECKOUT
  // =========================

  continuarCompra(): void {

    /*
     * Por ahora solamente comprobamos
     * que el botón funciona.
     *
     * El siguiente paso será abrir
     * la vista de checkout:
     *
     * Dirección
     * Método de envío
     * Cupón
     * Resumen
     */
    console.log(
      'Iniciar checkout del CU-03'
    );
  }
}