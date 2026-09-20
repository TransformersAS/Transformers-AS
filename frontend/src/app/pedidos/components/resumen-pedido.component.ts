import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ETIQUETA_ESTADO_PEDIDO, Pedido } from '../models/pedido.model';

@Component({
  selector: 'app-resumen-pedido', standalone: true,
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<article>
    <h3>Pedido #{{ pedido.id }}</h3>
    <p>{{ etiqueta(pedido.status) }}</p>
    <p>Total: {{ pedido.total | number:'1.2-2' }} · Envío: {{ pedido.shippingMethod }}</p>
    <p>{{ pedido.createdAt | date:'dd/MM/yyyy HH:mm' }}</p>
  </article>`
})
export class ResumenPedidoComponent {
  @Input({ required: true }) pedido!: Pedido;

  etiqueta(estado: Pedido['status']): string {
    return ETIQUETA_ESTADO_PEDIDO[estado] ?? estado;
  }
}
