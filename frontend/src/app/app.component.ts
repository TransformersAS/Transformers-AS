import { CheckoutStatusComponent } from './checkout/components/checkout-status.component';
import { PushService } from './notificaciones/push.service';
import { MisResenasComponent } from './resenas-favoritos/components/mis-resenas.component';
import { ResenasProductoComponent } from './resenas-favoritos/components/resenas-producto.component';
import { FavoritosService } from './resenas-favoritos/services/favoritos.service';
import { MisFavoritosComponent } from './resenas-favoritos/components/mis-favoritos.component';
import { NotificacionesComponent } from './notificaciones/notificaciones.component';
import {
  Component,
  inject, effect
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
  laptopOutline,
  personCircleOutline,
  searchOutline,
  shirtOutline,
  sparklesOutline,
  star
} from 'ionicons/icons';

import {
  finalize,
  switchMap, catchError, of, tap, shareReplay, combineLatest
} from 'rxjs';
import { toObservable } from '@angular/core/rxjs-interop';
import { Producto } from './catalogo/models/producto.model';
import { categoriaComparable, filtrarCatalogo } from './catalogo/catalogo-filtros';

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
import { AnaliticaVentasComponent } from './analitica/components/analitica-ventas.component';
import { EstadoTiendaComponent } from './suspensiones/components/estado-tienda.component';
import { EvaluacionTiendasComponent } from './suspensiones/components/evaluacion-tiendas.component';
import { MisProductosComponent } from './panel-vendedor/components/mis-productos.component';
import { MiTiendaComponent } from './panel-vendedor/components/mi-tienda.component';
import { ReclamacionesComponent } from './reclamaciones-devoluciones/components/reclamaciones.component';
import { RegistroVendedorComponent } from './registro-vendedor/components/registro-vendedor.component';
import { MisReportesComponent } from './reportes/components/mis-reportes.component';
import { ReportarContenidoComponent } from './reportes/components/reportar-contenido.component';
import { ReportesService } from './reportes/services/reportes.service';
import { DevolucionesRecibidasComponent } from './devoluciones/components/devoluciones-recibidas.component';
import { MisDevolucionesComponent } from './devoluciones/components/mis-devoluciones.component';
import { DevolucionesService } from './devoluciones/services/devoluciones.service';

import {
  CheckoutService
} from './checkout/services/checkout.service';

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

type OpcionMenu =
  | 'pedidos-recibidos' | 'devoluciones-recibidas' | 'reclamaciones' | 'mis-productos' | 'inventario' | 'mi-tienda'
  | 'estado-tienda' | 'analitica' | 'mis-reportes' | 'mis-devoluciones' | 'favoritos' | 'moderacion'
  | 'evaluar-tiendas' | 'catalogo-admin' | 'resenas';

interface MenuRol {
  /** Texto visible del botón; el nombre accesible es «Opciones de …». */
  titulo: string;
  grupos: { titulo: string; opciones: { id: OpcionMenu; etiqueta: string }[] }[];
}

/**
 * Accesos de cada rol, agrupados por tarea. Mis pedidos, notificaciones y carrito siguen en la cabecera.
 * El backend valida los permisos por su cuenta: esto solo decide qué se muestra.
 */
const MENUS_POR_ROL: Record<string, MenuRol> = {
  COMPRADOR: {
    titulo: 'Mis compras',
    grupos: [{ titulo: 'Después de comprar', opciones: [
      { id: 'mis-devoluciones', etiqueta: 'Mis devoluciones' },
      { id: 'reclamaciones', etiqueta: 'Mis reclamaciones' },
      { id: 'mis-reportes', etiqueta: 'Mis reportes' },
      { id: 'favoritos', etiqueta: 'Mis favoritos y alertas' },
      { id: 'resenas', etiqueta: 'Mis reseñas' }
    ] }]
  },
  VENDEDOR: {
    titulo: 'Mi negocio',
    grupos: [
      { titulo: 'Ventas', opciones: [
        { id: 'pedidos-recibidos', etiqueta: 'Pedidos recibidos' },
        { id: 'resenas', etiqueta: 'Reseñas recibidas' },
        { id: 'devoluciones-recibidas', etiqueta: 'Devoluciones recibidas' },
        { id: 'reclamaciones', etiqueta: 'Reclamaciones' },
        { id: 'analitica', etiqueta: 'Analítica' }
      ] },
      { titulo: 'Catálogo', opciones: [
        { id: 'mis-productos', etiqueta: 'Mis productos' },
        { id: 'inventario', etiqueta: 'Inventario' }
      ] },
      { titulo: 'Tienda', opciones: [
        { id: 'mi-tienda', etiqueta: 'Mi tienda' },
        { id: 'estado-tienda', etiqueta: 'Estado de mi tienda' },
        { id: 'mis-reportes', etiqueta: 'Mis reportes' }
      ] }
    ]
  },
  SOPORTE: {
    titulo: 'Soporte',
    grupos: [{ titulo: 'Atención', opciones: [
      { id: 'moderacion', etiqueta: 'Moderación de reportes' },
      { id: 'reclamaciones', etiqueta: 'Reclamaciones escaladas' }
    ] }]
  },
  ADMIN: {
    titulo: 'Administración',
    grupos: [{ titulo: 'Plataforma', opciones: [
      { id: 'evaluar-tiendas', etiqueta: 'Evaluar tiendas' },
      { id: 'catalogo-admin', etiqueta: 'Configurar catálogo' }
    ] }]
  }
};

@Component({
  selector: 'app-root',

  standalone: true,

  imports: [
    CheckoutStatusComponent, MisFavoritosComponent, MisResenasComponent, ResenasProductoComponent, IonApp,
    IonContent,
    IonIcon,
    AsyncPipe,
    CurrencyPipe,
    NgFor,
    NgIf,
    FormsModule,
    AccesoComponent,
    MisPedidosComponent, NotificacionesComponent,
    ColaSoporteComponent,
    PedidosRecibidosComponent,
    CatalogoAdminComponent,
    MisProductosComponent,
    InventarioComponent,
    AnaliticaVentasComponent,
    EstadoTiendaComponent,
    EvaluacionTiendasComponent,
    MiTiendaComponent,
    ReclamacionesComponent,
    RegistroVendedorComponent,
    MisReportesComponent,
    ReportarContenidoComponent,
    MisDevolucionesComponent,
    DevolucionesRecibidasComponent
  ],

  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {

  /** Menú de accesos del rol activo (botón fijo abajo a la izquierda). */
  mostrarMenu = false;
  mostrarFavoritos = false;
  readonly favoritos = inject(FavoritosService);

  guardarFavorito(id: number): void {
    if (!this.esComprador) { this.mostrarAcceso = true; return; }
    this.favoritos.toggle(id);
  }

  abrirProductoGuardado(id: number, comparar = false, enlaceCompartido = false): void {
    this.errorCatalogo = '';
    const cuenta = this.auth.cuenta();
    this.catalogo.obtenerDetalle(id).subscribe({
      next: producto => {
        if (!enlaceCompartido && this.auth.cuenta() !== cuenta) return;
        if (comparar) { this.comparar(producto); this.mostrarFavoritos = false; }
        else { this.mostrarFavoritos = false; this.mostrarNotificaciones = false; this.abrirDetalle(producto); }
      },
      error: () => this.errorCatalogo = 'El producto ya no está disponible o no se pudo consultar.'
    });
  }

  // =========================================================
  // SERVICIOS
  // =========================================================

  private readonly catalogo =
    inject(CatalogoService);

  private readonly carrito =
    inject(CarritoService);

  /** Panel "Mis reportes" (CU-20): su visibilidad y el reporte a mostrar viven en el servicio. */
  protected readonly reportes = inject(ReportesService);

  /** Paneles de devoluciones (CU-19): su visibilidad y la devolución a mostrar viven en el servicio. */
  protected readonly devoluciones = inject(DevolucionesService);

  private readonly auth =
    inject(AuthService);

  private readonly checkout =
    inject(CheckoutService);

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

  cargandoCatalogo = false;
  errorCatalogo = '';
  categoriaFiltro = '';
  precioMinimo: number | null = null;
  precioMaximo: number | null = null;
  ordenCatalogo = 'relevancia';
  comparados: Producto[] = [];
  errorComparacion = '';
  detalle: Producto | null = null;
  imagenDetalle = '';
  errorDetalle = '';
  cargandoDetalle = false;
  private detalleSolicitud = 0;
  private focoAntesDelDetalle: HTMLElement | null = null;

  mostrarResenas=false;
  actualizarResenas(){this.catalogo.refrescarValoraciones();}
  readonly destacados$ = combineLatest([toObservable(this.auth.cuenta),this.catalogo.actualizaciones]).pipe(
    tap(() => {
      this.comparados = [];
      this.detalle = null;
      this.detalleSolicitud++;
      this.cargandoDetalle = false;
      this.errorDetalle = '';
      this.errorComparacion = '';
    }),
    // El catálogo es público: el visitante lo explora sin sesión y se vuelve a leer al entrar o salir.
    switchMap(() => {
      this.errorCatalogo = '';
      this.cargandoCatalogo = true;
      return this.catalogo.obtenerDestacados().pipe(
        catchError(() => {
          this.errorCatalogo = 'No se pudo cargar el catálogo. Recarga la página para reintentar.';
          return of([] as Producto[]);
        }),
        finalize(() => this.cargandoCatalogo = false)
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  get avisoSesionFinalizada(): boolean { return this.auth.sesionFinalizada(); }

  get autenticada(): boolean { return this.auth.autenticada(); }

  get preciosInvalidos(): boolean {
    return (this.precioMinimo !== null && this.precioMinimo < 0)
      || (this.precioMaximo !== null && this.precioMaximo < 0)
      || (this.precioMinimo !== null && this.precioMaximo !== null && this.precioMinimo > this.precioMaximo);
  }

  filtrarProductos(productos: Producto[]): Producto[] {
    return this.preciosInvalidos ? [] : filtrarCatalogo(productos, this.searchTerm,
      this.categoriaFiltro, this.precioMinimo, this.precioMaximo, this.ordenCatalogo);
  }

  categoriasDisponibles(productos: Producto[]): string[] {
    return [...new Set(productos.map(p => categoriaComparable(p.categoria)))].sort();
  }

  limpiarFiltros(): void {
    this.searchTerm = ''; this.categoriaFiltro = '';
    this.precioMinimo = null; this.precioMaximo = null; this.ordenCatalogo = 'relevancia';
  }

  estaComparado(id: number): boolean { return this.comparados.some(p => p.id === id); }

  comparar(producto: Producto): void {
    this.errorComparacion = '';
    if (this.estaComparado(producto.id)) {
      this.comparados = this.comparados.filter(p => p.id !== producto.id);
    } else if (this.comparados.length >= 3) {
      this.errorComparacion = 'Puedes comparar hasta tres productos. Quita uno para agregar otro.';
    } else if (this.comparados.length && categoriaComparable(this.comparados[0].categoria) !== categoriaComparable(producto.categoria)) {
      this.errorComparacion = 'Elige productos de la misma categoría para compararlos.';
    } else {
      this.comparados = [...this.comparados, producto];
    }
  }

  abrirDetalle(producto: Producto): void {
    this.detalle = producto;
    this.imagenDetalle = producto.imagen;
    this.verProducto(producto.id);
  }

  cerrarDetalle(): void {
    this.detalleSolicitud++; this.detalle = null;
    this.errorDetalle = ''; this.cargandoDetalle = false;
    this.focoAntesDelDetalle?.focus();
  }

  tecladoDetalle(evento: KeyboardEvent): void {
    if (evento.key === 'Escape') { evento.preventDefault(); this.cerrarDetalle(); return; }
    if (evento.key !== 'Tab') return;
    const panel = evento.currentTarget as HTMLElement;
    const controles = Array.from(panel.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input, select, [tabindex="0"]'));
    const primero = controles[0], ultimo = controles[controles.length - 1];
    if (evento.shiftKey && document.activeElement === primero) { evento.preventDefault(); ultimo?.focus(); }
    else if (!evento.shiftKey && document.activeElement === ultimo) { evento.preventDefault(); primero?.focus(); }
  }

  readonly categorias$ =
    this.catalogo.obtenerCategorias();

  recommendations$ =
  this.recommendation.getRecommendations();

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

  procesandoCompra = false;

  compraResultado:
    PaymentResponse | null = null;

  compraError = '';
  private cuentaErrorCompra: number | null = null;
  private revisionCompra = 0;


  // =========================================================
  // USUARIO
  // =========================================================

  mostrarAcceso = false;

  mostrarModeracion = false;

  mostrarPedidos = false;
  mostrarNotificaciones = false;

  /** Panel de pedidos recibidos del vendedor (CU-23); "mostrarPedidos" es el de "Mis pedidos" del comprador. */
  mostrarPedidosRecibidos = false;

  /** Panel del administrador para configurar categorías, marcas y atributos (CU-17). */
  mostrarCatalogoAdmin = false;

  /** Panel del vendedor para publicar y mantener sus productos (CU-14). */
  mostrarMisProductos = false;

  /** Panel del vendedor para controlar su inventario (CU-15). */
  mostrarInventario = false;
  /** Analítica de ventas y rentabilidad (CU-26), solo con el rol activo VENDEDOR. */
  mostrarAnalitica = false;
  /** Estado de la tienda y aclaraciones de la administración (CU-27), solo con el rol activo VENDEDOR. */
  mostrarEstadoTienda = false;
  /** Evaluación y suspensión de tiendas (CU-27), solo con el rol activo ADMIN. */
  mostrarEvaluacionTiendas = false;

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

  /** Menú del rol activo; null si no hay sesión o el rol no tiene accesos propios. */
  get menuRol(): MenuRol | null {
    const rol = this.auth.cuenta()?.activeRole;
    return rol ? MENUS_POR_ROL[rol] ?? null : null;
  }

  abrirDesdeMenu(opcion: OpcionMenu): void {
    this.mostrarMenu = false;
    switch (opcion) {
      case 'pedidos-recibidos': this.mostrarPedidosRecibidos = true; break;
      case 'devoluciones-recibidas': this.devoluciones.abrirPanelVendedor(); break;
      case 'reclamaciones': this.mostrarReclamaciones = true; break;
      case 'mis-productos': this.mostrarMisProductos = true; break;
      case 'inventario': this.mostrarInventario = true; break;
      case 'mi-tienda': this.mostrarMiTienda = true; break;
      case 'estado-tienda': this.mostrarEstadoTienda = true; break;
      case 'analitica': this.mostrarAnalitica = true; break;
      case 'mis-reportes': this.reportes.abrirPanel(); break;
      case 'mis-devoluciones': this.devoluciones.abrirPanelComprador(); break;
      case 'favoritos': this.mostrarFavoritos = true; break;
      case 'resenas': this.mostrarResenas = true; break;
      case 'moderacion': this.mostrarModeracion = true; break;
      case 'evaluar-tiendas': this.mostrarEvaluacionTiendas = true; break;
      case 'catalogo-admin': this.mostrarCatalogoAdmin = true; break;
    }
  }


  // =========================================================
  // CONSTRUCTOR
  // =========================================================

  constructor() {
    inject(PushService);

    this.auth.restaurar();
    effect(() => {
      const cuenta=this.auth.cuenta();this.mostrarFavoritos=false;this.mostrarMenu=false;this.mostrarResenas=false;
      if(cuenta && ['COMPRADOR','VENDEDOR'].includes(cuenta.activeRole || '') && new URLSearchParams(location.search).get('notificaciones')==='1') {
        this.mostrarNotificaciones=true;
        const url=new URL(location.href);url.searchParams.delete('notificaciones');history.replaceState(null,'',url);
      }
    });
    const compartido = new URLSearchParams(location.search).get('producto');
    if (compartido && /^[1-9][0-9]{0,14}$/.test(compartido)) this.abrirProductoGuardado(Number(compartido), false, true);

    // Solo estado del inicio de compra; el seguimiento de pagos pertenece a CheckoutStatusComponent.
    effect(() => {
      const cuenta = this.auth.cuenta();
      if (!cuenta) {
        this.mostrarCarrito = false;
        this.mostrarCheckout = false;
        if (this.auth.sesionFinalizada()) this.mostrarAcceso = false;
      }
      const comprador = cuenta?.activeRole === 'COMPRADOR' ? cuenta.accountId : null;
      if (comprador !== this.cuentaErrorCompra) {
        this.cuentaErrorCompra = comprador;
        this.revisionCompra++;
        this.compraError = '';
      }
    });

    addIcons({
      bagHandleOutline,
      chatbubbleEllipsesOutline,
      chevronForwardOutline,
      homeOutline,
      laptopOutline,
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

  // Comprar sí exige sesión: el visitante inicia sesión primero.
  if (!this.autenticada) {
    this.mostrarAcceso = true;
    return;
  }

  this.carrito.agregar(id);

  this.interaction.register({
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

  const solicitud = ++this.detalleSolicitud;
  this.focoAntesDelDetalle = document.activeElement as HTMLElement;
  this.errorDetalle = '';
  this.cargandoDetalle = true;
  setTimeout(() => {
    if (solicitud === this.detalleSolicitud) document.querySelector<HTMLElement>('.catalogo-detalle button')?.focus();
  });
  this.catalogo.obtenerDetalle(id).subscribe({
    next: producto => {
      if (solicitud !== this.detalleSolicitud) return;
      this.detalle = producto; this.imagenDetalle = producto.imagen; this.cargandoDetalle = false;
      this.comparados = this.comparados.map(p => p.id === producto.id ? producto : p);
    },
    error: () => {
      if (solicitud !== this.detalleSolicitud) return;
      this.detalle = null; this.cargandoDetalle = false;
      this.errorDetalle = 'El producto ya no está disponible o no se pudo consultar.';
    }
  });

  // Las interacciones son de una cuenta: las del visitante no se registran.
  if (!this.autenticada) return;

  this.interaction.register({
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

  document.querySelector('#catalogo')?.scrollIntoView({ behavior: 'smooth' });

  const term =
    this.searchTerm.trim();

  if (!term || !this.autenticada) {
    return;
  }

  this.interaction.register({
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
          .getRecommendations();
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
    this.revisionCompra++;

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
    if (this.procesandoCompra) return;
    if (!this.checkoutPreview || this.addressId === null) {
      this.compraError = 'Primero registra la dirección y calcula el total de la compra.';
      return;
    }
    const cuenta = this.auth.cuenta()?.accountId;
    const revision = this.revisionCompra;
    const vigente = () => this.mostrarCheckout && revision === this.revisionCompra
      && this.auth.cuenta()?.activeRole === 'COMPRADOR' && this.auth.cuenta()?.accountId === cuenta;
    this.procesandoCompra = true;
    this.compraError = '';
    this.payment.startCheckout({ addressId: this.addressId, shippingMethod: this.shippingMethod,
      couponCode: this.couponCode }).pipe(finalize(() => this.procesandoCompra = false)).subscribe({
      next: result => {
        if (!vigente()) return;
        if (!result.checkoutUrl) { this.compraError = 'No se pudo abrir el checkout de pago.'; return; }
        const target = new URL(result.checkoutUrl);
        if (target.protocol !== 'https:' || target.hostname !== 'sandbox.mercadopago.com.co') {
          this.compraError = 'El enlace de la pasarela de pruebas no es válido.';
          return;
        }
        window.location.assign(target.href);
      },
      error: (error: HttpErrorResponse) => {
        if (vigente()) this.compraError = error.error?.detail ?? error.error?.message ?? 'No fue posible iniciar el pago.';
      }
    });
  }

}
