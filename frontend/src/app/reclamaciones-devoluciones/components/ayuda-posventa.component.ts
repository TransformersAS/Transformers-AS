import { ChangeDetectionStrategy, Component } from '@angular/core';
/** Mensaje de entrada cálido para el flujo sensible de devolución o reclamo. */
@Component({ selector: 'app-ayuda-posventa', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<section><strong>Estamos para arreglarlo.</strong><p>Elige un pedido y cuéntanos qué pasó.</p></section>` })
export class AyudaPosventaComponent {}
