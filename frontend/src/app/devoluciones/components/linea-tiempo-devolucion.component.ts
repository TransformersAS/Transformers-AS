import { Component, Input } from '@angular/core';
import { DatePipe } from '@angular/common';

import { ETIQUETA_ACTOR, ETIQUETA_ESTADO, ETIQUETA_EVENTO, EventoDevolucion } from '../models/devolucion.model';

/** Línea de tiempo de una devolución (RF-051): cada cambio con su fecha, quién lo hizo y el estado al que pasó. */
@Component({
    selector: 'app-linea-tiempo-devolucion',
    standalone: true,
    imports: [DatePipe],
    template: `
    <ol class="tiempo" aria-label="Historial de la devolución">
      @for (evento of eventos; track $index) {
        <li>
          <strong>{{ etiqueta(evento) }}</strong>
          <span class="nota">
            {{ evento.at | date: 'medium' }} · {{ actor(evento.actor) }}
            @if (evento.to) { · {{ estado(evento.to) }} }
          </span>
          @if (evento.details) {
            <span class="detalle">{{ evento.details }}</span>
          }
        </li>
      } @empty {
        <li class="nota">Todavía no hay movimientos.</li>
      }
    </ol>
  `,
    styles: [`
    .tiempo { margin: 0; padding: 0 0 0 1.1rem; border-left: 2px solid #d8dee4; list-style: none; }
    li { position: relative; margin: 0 0 0.75rem; padding-left: 0.6rem; display: flex; flex-direction: column; }
    li::before { content: ''; position: absolute; left: -1.5rem; top: 0.35rem; width: 0.55rem; height: 0.55rem;
      border-radius: 50%; background: #3880ff; }
    .nota { color: #666; font-size: 0.85rem; }
    .detalle { font-size: 0.9rem; }
  `]
})
export class LineaTiempoDevolucionComponent {
    @Input({ required: true }) eventos: EventoDevolucion[] = [];

    etiqueta(evento: EventoDevolucion): string {
        return ETIQUETA_EVENTO[evento.type] ?? evento.type;
    }

    actor(actor: string): string {
        return ETIQUETA_ACTOR[actor] ?? actor;
    }

    estado(estado: keyof typeof ETIQUETA_ESTADO): string {
        return ETIQUETA_ESTADO[estado];
    }
}
