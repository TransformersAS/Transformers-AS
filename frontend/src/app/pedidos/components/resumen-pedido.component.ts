import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Pedido } from '../models/pedido.model';

@Component({
  selector: 'app-resumen-pedido', standalone: true,
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<article>
    <h3>Pedido #{{ pedido.id }}</h3>
    <p>{{ pedido.status === 'CONFIRMED' ? 'Confirmado' : 'Cancelación solicitada' }}</p>
    <p>Total: {{ pedido.total | number:'1.2-2' }} · Envío: {{ pedido.shippingMethod }}</p>
    <p>{{ pedido.createdAt | date:'dd/MM/yyyy HH:mm' }}</p>
  </article>`
})
export class ResumenPedidoComponent {
  @Input({ required: true }) pedido!: Pedido;
}
