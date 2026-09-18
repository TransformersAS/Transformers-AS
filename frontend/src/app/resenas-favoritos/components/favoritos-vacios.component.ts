import { ChangeDetectionStrategy, Component } from '@angular/core';
/** Invita a guardar hallazgos en vez de mostrar un vacío técnico. */
@Component({ selector: 'app-favoritos-vacios', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<section><strong>Tu lista está esperando hallazgos.</strong><p>Toca el corazón de cualquier producto para guardarlo.</p></section>` })
export class FavoritosVaciosComponent {}
