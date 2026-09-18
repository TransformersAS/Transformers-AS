import { ChangeDetectionStrategy, Component } from '@angular/core';
/** Estado vacío deliberado para mensajería: comunica el valor antes de que haya actividad. */
@Component({ selector: 'app-bandeja-vacia', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<section><strong>Las buenas preguntas empiezan aquí.</strong><p>Escribe a una tienda desde cualquier producto.</p></section>` })
export class BandejaVaciaComponent {}
