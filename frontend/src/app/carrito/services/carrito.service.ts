import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

import {
  ItemCarrito
} from '../models/carrito.model';

interface CartItemApi {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

interface CartApi {
  cartId: number;
  items: CartItemApi[];
  total: number;
}

@Injectable({ providedIn: 'root' })
export class CarritoService {

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/cart`;

  private readonly itemsSubject =
    new BehaviorSubject<ItemCarrito[]>([]);

  private readonly totalSubject =
    new BehaviorSubject<number>(0);

  readonly items$ = this.itemsSubject.asObservable();
  readonly total$ = this.totalSubject.asObservable();

  constructor() {
    this.refrescar();
  }

  agregar(productoId: number): void {

    this.http.post(
      `${this.apiUrl}/items`,
      {
        productId: productoId,
        quantity: 1
      }
    ).subscribe({
      next: () => this.refrescar(),

      error: (error) => {
        console.error(
          'Error agregando producto al carrito',
          error
        );
      }
    });
  }

  aumentar(item: ItemCarrito): void {

    this.actualizarCantidad(
      item.id,
      item.cantidad + 1
    );
  }

  disminuir(item: ItemCarrito): void {

    if (item.cantidad <= 1) {
      this.eliminar(item.id);
      return;
    }

    this.actualizarCantidad(
      item.id,
      item.cantidad - 1
    );
  }

  actualizarCantidad(
    itemId: number,
    cantidad: number
  ): void {

    this.http.patch(
      `${this.apiUrl}/items/${itemId}`,
      {
        quantity: cantidad
      }
    ).subscribe({
      next: () => this.refrescar(),

      error: (error) => {
        console.error(
          'Error actualizando cantidad',
          error
        );
      }
    });
  }

  eliminar(itemId: number): void {

    this.http.delete(
      `${this.apiUrl}/items/${itemId}`
    ).subscribe({
      next: () => this.refrescar(),

      error: (error) => {
        console.error(
          'Error eliminando producto',
          error
        );
      }
    });
  }

  refrescar(): void {

    this.http.get<CartApi>(this.apiUrl)
      .subscribe({
        next: (carrito) => {

          const items: ItemCarrito[] =
            carrito.items.map((item) => ({
              id: item.id,
              productoId: item.productId,
              nombreProducto: item.productName,
              cantidad: item.quantity,
              precioUnitario: item.unitPrice,
              subtotal: item.subtotal
            }));

          this.itemsSubject.next(items);
          this.totalSubject.next(carrito.total);
        },

        error: (error) => {
          console.error(
            'Error consultando carrito',
            error
          );
        }
      });
  }
}