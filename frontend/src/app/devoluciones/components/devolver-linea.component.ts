import { Component, Input, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';

import { CampoDevolucion, interpretarErrorDevolucion } from '../models/devolucion-errores';
import {
    ETIQUETA_ESTADO,
    LineaElegible,
    MAX_BYTES_IMAGEN,
    MAX_IMAGENES,
    MOTIVOS_DEVOLUCION
} from '../models/devolucion.model';
import { DevolucionesService } from '../services/devoluciones.service';

/**
 * «Devolver» de una línea de un pedido (CU-19). Solo aparece si el pedido está entregado y la línea es una de las que
 * el backend lista como candidatas: el id de la línea no viaja en el detalle del pedido, así que se localiza por
 * pedido + producto + posición. Cada error va junto a su campo y, ante un error, lo ya escrito se conserva (A4).
 */
@Component({
    selector: 'app-devolver-linea',
    standalone: true,
    imports: [DatePipe, FormsModule],
    template: `
    @if (linea) {
      <div class="devolver">
        @if (linea.existingReturnId !== null) {
          <p class="nota" role="status">
            Ya solicitaste esta devolución
            @if (linea.existingReturnStatus) { ({{ etiquetaEstado(linea.existingReturnStatus) }}) }.
          </p>
          <button type="button" (click)="verExistente(linea.existingReturnId)">Ver mi devolución</button>
        } @else if (!linea.eligible) {
          <p class="nota" role="status">
            No se puede devolver: {{ linea.ineligibleMessage ?? 'esta línea no es elegible.' }}
          </p>
        } @else if (!abierto) {
          <button type="button" (click)="abrir()">Devolver</button>
          @if (linea.returnWindowEndsAt) {
            <span class="nota"> Puedes solicitarlo hasta el {{ linea.returnWindowEndsAt | date: 'medium' }}.</span>
          }
        } @else {
          <form class="formulario" (ngSubmit)="enviar()" novalidate>
            <h4>Devolver «{{ linea.productName }}»</h4>
            @if (errorGeneral) {
              <p class="error" role="alert">{{ errorGeneral }}</p>
            }

            <label [for]="id('motivo')">Motivo</label>
            <select [id]="id('motivo')" name="motivo" [(ngModel)]="motivo"
              [attr.aria-invalid]="errores['motivo'] ? 'true' : null"
              [attr.aria-describedby]="errores['motivo'] ? id('error-motivo') : null">
              <option value="">Elige un motivo</option>
              @for (m of motivos; track m.codigo) {
                <option [value]="m.codigo">{{ m.etiqueta }}</option>
              }
            </select>
            @if (errores['motivo']) {
              <p class="error" [id]="id('error-motivo')" role="alert">{{ errores['motivo'] }}</p>
            }

            <label [for]="id('descripcion')">Descripción</label>
            <textarea [id]="id('descripcion')" name="descripcion" rows="4" maxlength="2000" [(ngModel)]="descripcion"
              [attr.aria-invalid]="errores['descripcion'] ? 'true' : null"
              [attr.aria-describedby]="errores['descripcion'] ? id('error-descripcion') : null"></textarea>
            @if (errores['descripcion']) {
              <p class="error" [id]="id('error-descripcion')" role="alert">{{ errores['descripcion'] }}</p>
            }

            <label [for]="id('imagenes')">Imágenes (opcional, hasta {{ maxImagenes }}; JPG o PNG de máximo 5 MB)</label>
            <input [id]="id('imagenes')" type="file" accept="image/jpeg,image/png" multiple (change)="elegirImagenes($event)"
              [attr.aria-invalid]="errores['imagenes'] ? 'true' : null"
              [attr.aria-describedby]="errores['imagenes'] ? id('error-imagenes') : null">
            @if (imagenes.length > 0) {
              <p class="nota">{{ imagenes.length }} imagen(es) seleccionada(s): {{ nombresImagenes() }}</p>
            }
            @if (errores['imagenes']) {
              <p class="error" [id]="id('error-imagenes')" role="alert">{{ errores['imagenes'] }}</p>
            }

            <div class="acciones">
              <button type="submit" [disabled]="enviando">{{ enviando ? 'Enviando…' : 'Solicitar devolución' }}</button>
              <button type="button" [disabled]="enviando" (click)="cancelar()">Cancelar</button>
            </div>
          </form>
        }

        @if (aviso) {
          <p class="exito" role="status">{{ aviso }}</p>
          @if (solicitada !== null) {
            <button type="button" (click)="verExistente(solicitada)">Ver mi devolución</button>
          }
        }
      </div>
    }
  `,
    styles: [`
    .devolver { margin: 0.5rem 0; }
    .nota { color: #666; font-size: 0.9rem; margin: 0.25rem 0; }
    .exito { color: #1b5e20; font-weight: 600; margin: 0.25rem 0; }
    .formulario { margin-top: 0.5rem; padding: 0.75rem 1rem; border: 1px solid #d8dee4; border-radius: 0.75rem; }
    .formulario h4 { margin: 0 0 0.5rem; }
    label { display: block; margin-top: 0.5rem; font-weight: 600; font-size: 0.9rem; }
    select, textarea, input[type='file'] { width: 100%; box-sizing: border-box; padding: 0.5rem; border: 1px solid #b7c0c8;
      border-radius: 0.5rem; font: inherit; }
    [aria-invalid='true'] { border-color: #b3261e; }
    .error { margin: 0.15rem 0; color: #b3261e; font-size: 0.85rem; font-weight: 600; }
    .acciones { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
  `]
})
export class DevolverLineaComponent implements OnInit {
    private readonly servicio = inject(DevolucionesService);

    @Input({ required: true }) pedidoId!: number;
    @Input({ required: true }) productoId!: number;
    /** Posición de la línea en el detalle del pedido, para distinguir dos líneas del mismo producto. */
    @Input() posicion = 0;
    /** Solo los pedidos entregados pueden tener devolución: ahorra la consulta en los demás. */
    @Input() entregado = false;

    readonly motivos = MOTIVOS_DEVOLUCION;
    readonly maxImagenes = MAX_IMAGENES;

    linea: LineaElegible | null = null;
    abierto = false;
    enviando = false;
    motivo = '';
    descripcion = '';
    imagenes: File[] = [];
    errores: Partial<Record<CampoDevolucion, string>> = {};
    errorGeneral: string | null = null;
    aviso: string | null = null;
    solicitada: number | null = null;

    ngOnInit(): void {
        if (!this.entregado) {
            return;
        }
        this.servicio.lineasDelPedido(this.pedidoId).subscribe({
            next: (lineas) => {
                // Solo se acepta la línea de esa posición si es del mismo producto: ante la duda no se ofrece nada
                // antes que devolver una línea equivocada del mismo producto.
                const enPosicion = lineas[this.posicion];
                this.linea = enPosicion?.productId === this.productoId ? enPosicion : null;
            },
            // Sin la lista no se ofrece nada: el resto del pedido sigue funcionando.
            error: () => (this.linea = null)
        });
    }

    id(base: string): string {
        return `${base}-${this.pedidoId}-${this.posicion}`;
    }

    etiquetaEstado(estado: keyof typeof ETIQUETA_ESTADO): string {
        return ETIQUETA_ESTADO[estado];
    }

    abrir(): void {
        this.abierto = true;
        this.aviso = null;
    }

    cancelar(): void {
        this.abierto = false;
        this.errores = {};
        this.errorGeneral = null;
    }

    nombresImagenes(): string {
        return this.imagenes.map((i) => i.name).join(', ');
    }

    elegirImagenes(evento: Event): void {
        const entrada = evento.target as HTMLInputElement;
        this.imagenes = Array.from(entrada.files ?? []);
        delete this.errores['imagenes'];
        if (this.imagenes.length > MAX_IMAGENES) {
            this.errores['imagenes'] = `Puedes adjuntar como máximo ${MAX_IMAGENES} imágenes.`;
        } else if (this.imagenes.some((i) => i.size > MAX_BYTES_IMAGEN)) {
            this.errores['imagenes'] = 'Cada imagen debe pesar como máximo 5 MB.';
        } else if (this.imagenes.some((i) => i.type !== 'image/jpeg' && i.type !== 'image/png')) {
            this.errores['imagenes'] = 'Solo se aceptan imágenes JPG o PNG.';
        }
    }

    verExistente(id: number): void {
        this.servicio.abrirPanelComprador(id);
    }

    enviar(): void {
        if (!this.linea || this.enviando) {
            return;
        }
        // A4: se validan todos los campos a la vez y lo escrito no se borra.
        const previoImagenes = this.errores['imagenes'];
        this.errores = {};
        this.errorGeneral = null;
        if (!this.motivo) {
            this.errores['motivo'] = 'Elige el motivo de la devolución.';
        }
        if (!this.descripcion.trim()) {
            this.errores['descripcion'] = 'Describe el problema con el producto.';
        }
        if (previoImagenes) {
            this.errores['imagenes'] = previoImagenes;
        }
        if (Object.keys(this.errores).length > 0) {
            return;
        }
        this.enviando = true;
        const linea = this.linea;
        this.servicio.solicitar({ orderId: this.pedidoId, orderItemId: linea.orderItemId, reason: this.motivo,
            description: this.descripcion.trim() }, this.imagenes)
            .pipe(finalize(() => (this.enviando = false)))
            .subscribe({
                next: (resultado) => {
                    this.solicitada = resultado.id;
                    // A3: si ya existía, no se creó otra: se avisa y se lleva a la existente.
                    this.aviso = resultado.duplicate
                        ? 'Ya solicitaste esta devolución; no se creó otra.'
                        : 'Tu devolución quedó solicitada. El vendedor la revisará.';
                    this.abierto = false;
                    this.linea = { ...linea, existingReturnId: resultado.id, existingReturnStatus: resultado.status };
                },
                error: (e: HttpErrorResponse) => {
                    const interpretado = interpretarErrorDevolucion(e, 'No se pudo solicitar la devolución.');
                    if (interpretado.campo) {
                        this.errores[interpretado.campo.nombre] = interpretado.campo.mensaje;
                    } else {
                        this.errorGeneral = interpretado.general;
                    }
                    // A1 detectado al enviar (cambió la elegibilidad): se explica el motivo y se cierra el formulario.
                    if (e.status === 422 || e.status === 409) {
                        this.servicio.invalidarElegibles();
                    }
                }
            });
    }
}
