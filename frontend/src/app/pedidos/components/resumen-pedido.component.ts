import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { Pedido } from '../models/pedido.model';
/** Resume una compra para listas y conserva un estado vacío útil mientras carga la API. */
@Component({ selector: 'app-resumen-pedido', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<article>{{ pedido ? 'Pedido ' + pedido.id + ' · ' + pedido.estado : 'Tus próximos pedidos aparecerán aquí.' }}</article>` })
export class ResumenPedidoComponent { /** El pedido opcional permite representar el estado vacío sin falsos datos. */ @Input() pedido?: Pedido; }
