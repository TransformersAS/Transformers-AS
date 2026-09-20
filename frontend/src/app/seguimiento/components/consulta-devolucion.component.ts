import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { SeguimientoLogisticoComponent } from './seguimiento-logistico.component';
import { RolConsulta } from '../models/seguimiento.model';

/**
 * Consulta del seguimiento logístico de una devolución por su número (CU-25). Las devoluciones las aprueba CU-19; hasta
 * que exista su listado, el número se escribe aquí. El backend solo entrega devoluciones del propio comprador o de la
 * propia tienda y responde 404 con cualquier otra.
 */
@Component({
    selector: 'app-consulta-devolucion',
    standalone: true,
    imports: [FormsModule, SeguimientoLogisticoComponent],
    template: `
    <section class="consulta" aria-label="Seguimiento de devolución">
      <h3>Seguimiento de devolución</h3>
      <form (ngSubmit)="consultar()">
        <label>N.º de devolución
          <input type="number" min="1" name="numero" [(ngModel)]="numero" inputmode="numeric">
        </label>
        <button type="submit" [disabled]="!valido()">Consultar</button>
      </form>
      @if (consultada !== null) {
        <app-seguimiento-logistico tipo="devolucion" [id]="consultada" [rol]="rol"></app-seguimiento-logistico>
      }
    </section>
  `,
    styles: [`
    .consulta { margin: 1rem 0; padding: 0.75rem 1rem; border: 1px dashed #b7c0c8; border-radius: 0.75rem; }
    h3 { margin: 0 0 0.5rem; }
    form { display: flex; align-items: flex-end; gap: 0.5rem; flex-wrap: wrap; }
    label { display: flex; flex-direction: column; font-size: 0.85rem; gap: 0.2rem; }
    input { padding: 0.5rem; border: 1px solid #d5e2e5; border-radius: 0.5rem; width: 9rem; }
    button { cursor: pointer; border: 1px solid #d5e2e5; border-radius: 0.5rem; padding: 0.55rem 0.8rem; background: #f0f7f7; color: #073b4c; }
    button:disabled { opacity: 0.6; cursor: default; }
  `]
})
export class ConsultaDevolucionComponent {
    @Input({ required: true }) rol!: RolConsulta;

    numero: number | string | null = null;
    consultada: number | null = null;

    valido(): boolean {
        const n = Number(this.numero);
        return Number.isInteger(n) && n > 0;
    }

    consultar(): void {
        if (this.valido()) {
            this.consultada = Number(this.numero);
        }
    }
}
