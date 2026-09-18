import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { Tienda } from '../models/tienda.model';
/** Bloque de pulso comercial que mantiene útil el panel antes de tener ventas. */
@Component({ selector: 'app-pulso-tienda', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<p>{{ tienda ? tienda.nombre + ': ' + tienda.pedidosPendientes + ' pedidos por revisar' : 'Tu escaparate está listo para cobrar vida.' }}</p>` })
export class PulsoTiendaComponent { /** Tienda opcional para distinguir onboarding de operación diaria. */ @Input() tienda: Tienda | null = null; }
