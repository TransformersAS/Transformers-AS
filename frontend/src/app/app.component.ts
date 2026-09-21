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
  HttpClient,
  HttpErrorResponse
} from '@angular/common/http';

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

import {
  finalize,
  switchMap
} from 'rxjs';

import {
  CatalogoService
} from './catalogo/services/catalogo.service';

import {
  CarritoService
} from './carrito/services/carrito.service';

import {
  AuthService
} from './core/services/auth.service';

import { MisPedidosComponent } from './pedidos/components/mis-pedidos.component';

import { AccesoComponent } from './core/components/acceso.component';

import { ColaSoporteComponent } from './panel-admin-soporte/components/cola-soporte.component';
import { PedidosRecibidosComponent } from './panel-vendedor/components/pedidos-recibidos.component';
import { CatalogoAdminComponent } from './panel-admin-catalogo/components/catalogo-admin.component';
import { InventarioComponent } from './panel-vendedor/components/inventario.component';
import { MisProductosComponent } from './panel-vendedor/components/mis-productos.component';
import { MiTiendaComponent } from './panel-vendedor/components/mi-tienda.component';
import { ReclamacionesComponent } from './reclamaciones-devoluciones/components/reclamaciones.component';
import { RegistroVendedorComponent } from './registro-vendedor/components/registro-vendedor.component';
import { MisReportesComponent } from './reportes/components/mis-reportes.component';
import { ReportarContenidoComponent } from './reportes/components/reportar-contenido.component';
import { ReportesService } from './reportes/services/reportes.service';

import {
  CheckoutService
} from './checkout/services/checkout.service';

import {
  ReservationService
} from './checkout/services/reservation.service';

import {
  PaymentService
} from './checkout/services/payment.service';

import {
  ItemCarrito
} from './carrito/models/carrito.model';

import {
  CheckoutPreviewResponse
} from './checkout/models/checkout.model';

import {
  PaymentResponse
} from './checkout/models/payment.model';

import {
  RecommendationService
} from './recomendaciones/services/recommendation.service';

import {
  InteractionService
} from './recomendaciones/services/interaction.service';

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
    FormsModule,
    AccesoComponent,
    MisPedidosComponent,
    ColaSoporteComponent,
    PedidosRecibidosComponent,
    CatalogoAdminComponent,
    MisProductosComponent,
    InventarioComponent,
    MiTiendaComponent,
    ReclamacionesComponent,
    RegistroVendedorComponent,
    MisReportesComponent,
    ReportarContenidoComponent
  ],

  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {

  // =========================================================
  // SERVICIOS
  // =========================================================

  private readonly catalogo =
    inject(CatalogoService);

  private readonly carrito =
    inject(CarritoService);

  /** Panel "Mis reportes" (CU-20): su visibilidad y el reporte a mostrar viven en el servicio. */
  protected readonly reportes = inject(ReportesService);

  private readonly auth =
    inject(AuthService);

  private readonly checkout =
    inject(CheckoutService);

  private readonly reservation =
    inject(ReservationService);

  private readonly payment =
    inject(PaymentService);

  private readonly http =
    inject(HttpClient);

  private readonly recommendation =
  inject(RecommendationService);

  private readonly interaction =
  inject(InteractionService);


  // =========================================================
  // CATÁLOGO
  // =========================================================

  readonly destacados$ =
    this.catalogo.obtenerDestacados();

  readonly categorias$ =
    this.catalogo.obtenerCategorias();

  recommendations$ =
  this.recommendation.getRecommendations(1);

  // =========================================================
  // CARRITO
  // =========================================================

  readonly items$ =
    this.carrito.items$;

  readonly total$ =
    this.carrito.total$;

  mostrarCarrito = false;
 
   searchTerm = '';


  // =========================================================
  // CHECKOUT
  // =========================================================

  mostrarCheckout = false;

  /*
   * Dirección escrita por el usuario.
   */
  deliveryAddress = '';

  /*
   * ID generado por el backend después de
   * guardar la dirección.
   */
  addressId: number | null = null;

  shippingMethod = 'STANDARD';

  couponCode = '';

  checkoutPreview:
    CheckoutPreviewResponse | null = null;

  checkoutError = '';

  procesandoCheckout = false;


  // =========================================================
  // PAGO
  // =========================================================

  paymentMethod = 'CARD';

  procesandoCompra = false;

  compraResultado:
    PaymentResponse | null = null;

  compraError = '';


  // =========================================================
  // USUARIO
  // =========================================================

  mostrarAcceso = false;

  mostrarModeracion = false;

  mostrarPedidos = false;

  /** Panel de pedidos recibidos del vendedor (CU-23); "mostrarPedidos" es el de "Mis pedidos" del comprador. */
  mostrarPedidosRecibidos = false;

  /** Panel del administrador para configurar categorías, marcas y atributos (CU-17). */
  mostrarCatalogoAdmin = false;

  /** Panel del vendedor para publicar y mantener sus productos (CU-14). */
  mostrarMisProductos = false;

  /** Panel del vendedor para controlar su inventario (CU-15). */
  mostrarInventario = false;

  /** "Mi tienda" del vendedor (CU-18). */
  mostrarMiTienda = false;

  /** Reclamaciones de compra (CU-13): la ven el comprador, el vendedor y soporte, cada uno a su manera. */
  mostrarReclamaciones = false;

  /** Registro de vendedores (CU-12): lo abre el botón "Conocer el espacio vendedor" de la portada. */
  mostrarRegistroVendedor = false;

  get esComprador(): boolean {
    return this.auth.cuenta()?.activeRole === 'COMPRADOR';
  }

  get nombre(): string {
    return this.auth.obtenerNombreVisible();
  }

  /** La cola de moderación solo se muestra con el rol activo SOPORTE; el backend lo exige igualmente. */
  get esSoporte(): boolean {
    return this.auth.cuenta()?.activeRole === 'SOPORTE';
  }

  /** Los pedidos recibidos (CU-23) solo se muestran con el rol activo VENDEDOR; el backend lo exige igualmente. */
  get esVendedor(): boolean {
    return this.auth.cuenta()?.activeRole === 'VENDEDOR';
  }

  /** La configuración del catálogo (CU-17) solo se muestra con el rol activo ADMIN; el backend lo exige igualmente. */
  get esAdmin(): boolean {
    return this.auth.cuenta()?.activeRole === 'ADMIN';
  }


  // =========================================================
  // CONSTRUCTOR
  // =========================================================

  constructor() {

    this.auth.restaurar();

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


  // =========================================================
  // AGREGAR AL CARRITO
  // =========================================================

  agregarAlCarrito(
  id: number
): void {

  this.carrito.agregar(id);

  this.interaction.register({
    userId: 1,
    productId: id,
    interactionType: 'ADD_TO_CART',
    searchTerm: null
  }).subscribe({

    next: () => {
      console.log(
        'Interacción ADD_TO_CART registrada'
      );
    },

    error: error => {
      console.error(
        'No se pudo registrar la interacción',
        error
      );
    }
  });
}
verProducto(
  id: number
): void {

  this.interaction.register({
    userId: 1,
    productId: id,
    interactionType: 'VIEW',
    searchTerm: null
  }).subscribe({

    next: () => {
      console.log(
        'Interacción VIEW registrada:',
        id
      );
    },

    error: error => {
      console.error(
        'No se pudo registrar VIEW',
        error
      );
    }
  });
}

buscarProductos(): void {

  const term =
    this.searchTerm.trim();

  if (!term) {
    return;
  }

  this.interaction.register({
    userId: 1,
    productId: null,
    interactionType: 'SEARCH',
    searchTerm: term
  }).subscribe({

    next: () => {

      console.log(
        'Interacción SEARCH registrada:',
        term
      );

      // Volvemos a consultar a Gemini
      // para que considere la nueva búsqueda.
      this.recommendations$ =
        this.recommendation
          .getRecommendations(1);
    },

    error: error => {

      console.error(
        'No se pudo registrar la búsqueda',
        error
      );
    }
  });
}

  // =========================================================
  // CONTADOR DEL CARRITO
  // =========================================================

  cantidadCarrito(
    items: readonly {
      cantidad: number
    }[]
  ): number {

    return items.reduce(
      (total, item) =>
        total + item.cantidad,
      0
    );
  }


  // =========================================================
  // ABRIR / CERRAR CARRITO
  // =========================================================

  toggleCarrito(): void {

    this.mostrarCarrito =
      !this.mostrarCarrito;

    if (this.mostrarCarrito) {
      this.carrito.refrescar();
    }
  }


  cerrarCarrito(): void {

    this.mostrarCarrito = false;
  }


  // =========================================================
  // CAMBIAR CANTIDAD
  // =========================================================

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


  // =========================================================
  // ELIMINAR
  // =========================================================

  eliminarDelCarrito(
    itemId: number
  ): void {

    this.carrito.eliminar(itemId);
  }


  // =========================================================
  // IR AL CHECKOUT
  // =========================================================

  continuarCompra(): void {

    this.mostrarCarrito = false;

    this.mostrarCheckout = true;

    this.checkoutPreview = null;

    this.checkoutError = '';

    this.compraResultado = null;

    this.compraError = '';

    /*
     * Cada vez que inicia un nuevo checkout,
     * todavía no existe una dirección guardada
     * para esta compra.
     */
    this.addressId = null;
  }


  // =========================================================
  // CERRAR CHECKOUT
  // =========================================================

  cerrarCheckout(): void {

    this.mostrarCheckout = false;

    this.checkoutPreview = null;

    this.checkoutError = '';

    this.compraResultado = null;

    this.compraError = '';
  }


  // =========================================================
  // ENVÍO
  // =========================================================

  actualizarMetodoEnvio(
    metodo: string
  ): void {

    this.shippingMethod = metodo;

    /*
     * Si cambia el método de envío,
     * eliminamos el preview anterior
     * para no mostrar un total viejo.
     */
    this.checkoutPreview = null;

    this.compraResultado = null;
  }


  // =========================================================
  // CALCULAR CHECKOUT
  // =========================================================

  aplicarCheckout(): void {

    /*
     * Primero validamos que el usuario
     * realmente haya escrito una dirección.
     */
    if (!this.deliveryAddress.trim()) {

      this.checkoutError =
        'Escribe una dirección de entrega.';

      return;
    }


    this.procesandoCheckout = true;

    this.checkoutError = '';

    this.checkoutPreview = null;

    this.compraResultado = null;

    this.compraError = '';


    /*
     * PASO 1:
     *
     * Guardamos la dirección que escribió
     * el usuario.
     *
     * POST /api/addresses
     */
    this.http
      .post<{ id: number }>(
        '/api/addresses',
        {
          recipientName: 'Comprador',

          street:
            this.deliveryAddress.trim(),

          /*
           * Como por ahora el frontend solamente
           * solicita una caja de texto para la dirección,
           * dejamos estos datos generales para el prototipo.
           */
          city: 'Bogotá',

          department: 'Bogotá D.C.',

          postalCode: '',

          phone: '333-333-3333'
        }
      )

      .pipe(

        /*
         * PASO 2:
         *
         * Cuando el backend crea la dirección,
         * devuelve su ID.
         *
         * Utilizamos ese ID para calcular
         * el checkout.
         */
        switchMap(address => {

          this.addressId = address.id;

          return this.checkout.preview({

            addressId:
              address.id,

            shippingMethod:
              this.shippingMethod,

            couponCode:
              this.couponCode
          });
        }),

        /*
         * Se ejecuta tanto si funciona
         * como si ocurre un error.
         */
        finalize(() => {

          this.procesandoCheckout =
            false;
        })
      )

      .subscribe({

        next: (
          response: CheckoutPreviewResponse
        ) => {

          this.checkoutPreview =
            response;
        },


        error: (
          error: HttpErrorResponse
        ) => {

          console.error(
            'Error guardando dirección o calculando checkout',
            error
          );


          this.checkoutError =
            error.error?.message
            ??
            error.error?.detail
            ??
            error.message
            ??
            'No fue posible calcular el checkout.';
        }
      });
  }


  // =========================================================
  // CONFIRMAR COMPRA
  // =========================================================

  confirmarCompra(): void {

    /*
     * No permitimos confirmar si el usuario
     * todavía no calculó el checkout.
     */
    if (!this.checkoutPreview) {

      this.compraError =
        'Primero debes calcular el total de la compra.';

      return;
    }


    /*
     * Tampoco podemos confirmar si por alguna
     * razón la dirección todavía no fue guardada.
     */
    const addressId =
      this.addressId;

    if (addressId === null) {

      this.compraError =
        'Primero debes registrar una dirección de entrega.';

      return;
    }


    this.procesandoCompra = true;

    this.compraError = '';

    this.compraResultado = null;


    /*
     * PASO 1
     *
     * POST /api/reservations/cart
     *
     * El backend crea las reservas ACTIVE.
     */
    this.reservation
      .reserveCart()

      .pipe(

        /*
         * PASO 2
         *
         * Tomamos los IDs de las reservas
         * creadas y procesamos el pago.
         */
        switchMap(
          reservations => {

            const reservationIds =
              reservations.map(
                reservation =>
                  reservation.id
              );


            /*
             * POST /api/payments/process
             */
            return this.payment.process({

              paymentMethod:
                this.paymentMethod,

              reservationIds,

              /*
               * Aquí ya usamos el ID REAL
               * de la dirección creada.
               */
              addressId,

              shippingMethod:
                this.shippingMethod,

              couponCode:
                this.couponCode
            });
          }
        ),


        /*
         * Esto se ejecuta tanto si funciona
         * como si ocurre un error.
         */
        finalize(() => {

          this.procesandoCompra =
            false;
        })
      )

      .subscribe({

        next: (
          response: PaymentResponse
        ) => {

          this.compraResultado =
            response;


          /*
           * APPROVED:
           *
           * El backend ya hizo:
           *
           * ACTIVE → CONFIRMED
           * descontó stock
           * creó Order
           * creó OrderItems
           * vació carrito
           */
          if (
            response.status === 'APPROVED'
          ) {

            this.carrito.refrescar();
            this.recommendations$ =
            this.recommendation
             .getRecommendations(1);
          }
        },


        error: (
          error: HttpErrorResponse
        ) => {

          console.error(
            'Error confirmando compra',
            error
          );


          this.compraError =
            error.error?.message
            ??
            error.error?.detail
            ??
            error.message
            ??
            'No fue posible completar la compra.';
        }
      });
  }
}