import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

import { VistaTienda, etiquetaMetodoEnvio } from '../models/mi-tienda.model';

/**
 * Cómo ven los compradores la tienda. Solo pinta lo que recibe: las imágenes llegan como URL (la del servidor, ya
 * versionada por su sha256, o un objeto local mientras el cambio está pendiente) y se piden bajo demanda.
 */
@Component({
    selector: 'app-tarjeta-tienda',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
    <article class="tienda" aria-label="Vista previa de la tienda">
      <div class="portada">
        @if (tienda.portadaUrl) {
          <img [src]="tienda.portadaUrl" alt="Portada de la tienda" loading="lazy" decoding="async" />
        } @else {
          <span class="sin-imagen">Sin portada</span>
        }
      </div>
      <div class="cabecera">
        <div class="logo">
          @if (tienda.logoUrl) {
            <img [src]="tienda.logoUrl" alt="Logo de la tienda" loading="lazy" decoding="async" />
          } @else {
            <span aria-hidden="true">{{ inicial }}</span>
          }
        </div>
        <h3>{{ tienda.nombre }}</h3>
      </div>

      @if (tienda.descripcion) {
        <p class="texto">{{ tienda.descripcion }}</p>
      }

      <dl>
        @if (tienda.correo || tienda.telefono) {
          <dt>Contacto</dt>
          <dd>
            @if (tienda.correo) { <span>{{ tienda.correo }}</span> }
            @if (tienda.telefono) { <span>{{ tienda.telefono }}</span> }
          </dd>
        }
        @if (tienda.horarios) {
          <dt>Horarios</dt>
          <dd class="texto">{{ tienda.horarios }}</dd>
        }
        <dt>Devoluciones</dt>
        <dd>
          <span>Hasta {{ tienda.plazoDevolucion }} días</span>
          @if (tienda.politica) { <span class="texto">{{ tienda.politica }}</span> }
        </dd>
        <dt>Envíos</dt>
        <dd>
          @for (metodo of tienda.metodosEnvio; track metodo) {
            <span class="etiqueta">{{ etiqueta(metodo) }}</span>
          } @empty {
            <span class="tenue">Sin métodos de envío</span>
          }
        </dd>
      </dl>
    </article>
  `,
    styles: [`
    :host { display: block; }
    .tienda { border: 1px solid #d8dee4; border-radius: 0.75rem; overflow: hidden; background: #fff; }
    .portada { height: 9rem; background: #eef1f3; display: flex; align-items: center; justify-content: center; }
    .portada img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .cabecera { display: flex; align-items: center; gap: 0.75rem; padding: 0 1rem; margin-top: -1.75rem; }
    .logo { flex: none; width: 3.5rem; height: 3.5rem; border-radius: 50%; border: 3px solid #fff; background: #073b4c;
      color: #fff; font-weight: 700; font-size: 1.4rem; display: flex; align-items: center; justify-content: center;
      overflow: hidden; }
    .logo img { width: 100%; height: 100%; object-fit: cover; display: block; }
    h3 { margin: 1.5rem 0 0; font-size: 1.1rem; overflow-wrap: anywhere; }
    .texto { white-space: pre-line; overflow-wrap: anywhere; }
    p.texto { margin: 0.75rem 1rem 0; }
    dl { margin: 0.75rem 1rem 1rem; display: grid; grid-template-columns: max-content 1fr; gap: 0.35rem 1rem; }
    dt { color: #667b80; font-size: 0.85rem; }
    dd { margin: 0; display: flex; flex-direction: column; gap: 0.15rem; overflow-wrap: anywhere; }
    .etiqueta { display: inline-block; padding: 0.1rem 0.6rem; border-radius: 999px; background: #eef6e0; font-size: 0.85rem; }
    .sin-imagen, .tenue { color: #667b80; font-size: 0.85rem; }
  `]
})
export class TarjetaTiendaComponent {
    @Input({ required: true }) tienda!: VistaTienda;

    get inicial(): string {
        return (this.tienda.nombre.trim().charAt(0) || '?').toUpperCase();
    }

    etiqueta(metodo: string): string {
        return etiquetaMetodoEnvio(metodo);
    }
}
