import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { IonApp, IonContent, IonIcon } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { bagHandleOutline, chatbubbleEllipsesOutline, chevronForwardOutline, homeOutline, searchOutline, shirtOutline, sparklesOutline, bicycleOutline, heartOutline, star, arrowForwardOutline, flashOutline, personCircleOutline } from 'ionicons/icons';
import { CatalogoService } from './catalogo/services/catalogo.service';
import { CarritoService } from './carrito/services/carrito.service';
import { AuthService } from './core/services/auth.service';
import { AsyncPipe, CurrencyPipe, NgFor, NgIf } from '@angular/common';

/** Portada de descubrimiento: compone datos de dominio sin conocer la futura API REST. */
@Component({
  selector: 'app-root', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IonApp, IonContent, IonIcon, AsyncPipe, CurrencyPipe, NgFor, NgIf],
  templateUrl: './app.component.html', styleUrl: './app.component.scss'
})
export class AppComponent {
  private readonly catalogo = inject(CatalogoService);
  private readonly carrito = inject(CarritoService);
  private readonly auth = inject(AuthService);
  /** Streams conservan al componente reactivo y evitan suscripciones manuales. */
  readonly destacados$ = this.catalogo.obtenerDestacados();
  readonly categorias$ = this.catalogo.obtenerCategorias();
  readonly items$ = this.carrito.items$;
  /** Personaliza el saludo desde un único servicio de identidad. */
  readonly nombre = this.auth.obtenerNombreVisible();

  constructor() {
    // Registrar solo los iconos usados mantiene el bundle de Ionic contenido.
    addIcons({ bagHandleOutline, chatbubbleEllipsesOutline, chevronForwardOutline, homeOutline, searchOutline, shirtOutline, sparklesOutline, bicycleOutline, heartOutline, star, arrowForwardOutline, flashOutline, personCircleOutline });
  }

  /** Añade el artículo seleccionado al carrito local y activa su feedback visual. */
  agregarAlCarrito(id: string): void { this.carrito.agregar(id); }

  /** Suma cantidades para que el indicador represente unidades, no líneas distintas. */
  cantidadCarrito(items: readonly { cantidad: number }[]): number { return items.reduce((total, item) => total + item.cantidad, 0); }
}
