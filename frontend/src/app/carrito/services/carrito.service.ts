import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { ItemCarrito } from '../models/carrito.model';

/** Mantiene el contador local de carrito hasta conectar el endpoint del comprador. */
@Injectable({ providedIn: 'root' })
export class CarritoService {
  private readonly items = new BehaviorSubject<ItemCarrito[]>([]);
  /** Emite cambios para actualizar indicadores en cualquier pantalla. */
  readonly items$ = this.items.asObservable();
  /** Incrementa una línea existente o crea una nueva, sin mutar el estado actual. */
  agregar(productoId: string): void {
    const actual = this.items.value;
    const previo = actual.find((item) => item.productoId === productoId);
    this.items.next(previo ? actual.map((item) => item.productoId === productoId ? { ...item, cantidad: item.cantidad + 1 } : item) : [...actual, { productoId, cantidad: 1 }]);
  }
}
