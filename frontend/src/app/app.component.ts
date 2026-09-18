import {

  Component,
  inject
} from '@angular/core';

import {
  AsyncPipe,
  CurrencyPipe,
  NgFor,
  NgIf
} from '@angular/common';

import { FormsModule } from '@angular/forms';

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
import { CheckoutService } from './checkout/services/checkout.service';

import { ItemCarrito } from './carrito/models/carrito.model';

import {
  CheckoutPreviewResponse
} from './checkout/models/checkout.model';


@Component({
  selector: 'app-root',

  standalone: true,

  

  imports: [
    IonApp,
    IonContent,
    IonIcon,
    AsyncPipe,
    CurrencyPipe,
    NgFor,
    NgIf,
    FormsModule
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

  private readonly checkout =
    inject(CheckoutService);


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
  // CHECKOUT
  // =========================

  mostrarCheckout = false;

  addressId = 1;

  shippingMethod = 'STANDARD';

  couponCode = '';

  checkoutPreview:
    CheckoutPreviewResponse | null = null;

  checkoutError = '';

  procesandoCheckout = false;


  // =========================
  // USUARIO
  // =========================

  readonly nombre =
    this.auth.obtenerNombreVisible();


  // =========================
  // CONSTRUCTOR
  // =========================

  constructor() {

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

  agregarAlCarrito(
    id: number
  ): void {

    this.carrito.agregar(id);
  }


  // =========================
  // CONTADOR DEL CARRITO
  // =========================

  cantidadCarrito(
    items: readonly {
      cantidad: number
    }[]
  ): number {

    return items.reduce(
      (
        total,
        item
      ) =>
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

    if (
      this.mostrarCarrito
    ) {

      this.carrito.refrescar();
    }
  }


  cerrarCarrito(): void {

    this.mostrarCarrito =
      false;
  }


  // =========================
  // MODIFICAR CANTIDAD
  // =========================

  aumentarCantidad(
    item: ItemCarrito
  ): void {

    this.carrito.aumentar(
      item
    );
  }


  disminuirCantidad(
    item: ItemCarrito
  ): void {

    this.carrito.disminuir(
      item
    );
  }


  // =========================
  // ELIMINAR DEL CARRITO
  // =========================

  eliminarDelCarrito(
    itemId: number
  ): void {

    this.carrito.eliminar(
      itemId
    );
  }


  // =========================
  // IR AL CHECKOUT
  // =========================

  continuarCompra(): void {

    this.mostrarCarrito =
      false;

    this.mostrarCheckout =
      true;

    this.checkoutPreview =
      null;

    this.checkoutError =
      '';
  }


  // =========================
  // CERRAR CHECKOUT
  // =========================

  cerrarCheckout(): void {

    this.mostrarCheckout =
      false;

    this.checkoutPreview =
      null;

    this.checkoutError =
      '';
  }


  // =========================
  // MÉTODO DE ENVÍO
  // =========================

  actualizarMetodoEnvio(
    metodo: string
  ): void {

    this.shippingMethod =
      metodo;
  }


  // =========================
  // CALCULAR CHECKOUT
  // =========================

  aplicarCheckout(): void {

  this.procesandoCheckout = true;
  this.checkoutError = '';

  this.checkout.preview({
    addressId: this.addressId,
    shippingMethod: this.shippingMethod,
    couponCode: this.couponCode
  })
  .subscribe({

    next: (response) => {

      this.checkoutPreview = response;

      this.procesandoCheckout = false;

      console.log(
        'Checkout calculado:',
        response
      );
    },

    error: (error) => {

      console.error(
        'Error calculando checkout:',
        error
      );

      this.checkoutError =
        error?.error?.message
        ??
        error?.error?.detail
        ??
        'No fue posible calcular el checkout.';

      this.procesandoCheckout = false;
    },

    complete: () => {

      this.procesandoCheckout = false;
    }

  });
}
}