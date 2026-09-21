import { Component, Input, OnDestroy, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';

import { AuthService } from '../../core/services/auth.service';
import { CampoReporte, interpretarErrorReporte } from '../models/reporte-errores';
import {
    ETIQUETA_ESTADO,
    ETIQUETA_TIPO,
    MAX_BYTES_IMAGEN,
    MAX_IMAGENES,
    MotivoReporte,
    ReporteRadicado
} from '../models/reporte.model';
import { ReportesService } from '../services/reportes.service';

const TIPOS_IMAGEN = ['image/png', 'image/jpeg'];

/**
 * Botón "Reportar" de un contenido (CU-20) con su formulario en una ventana. Es independiente de la pantalla donde se
 * coloca: solo necesita el tipo y el id del contenido. Solo aparece con el rol activo de comprador o vendedor; el
 * backend lo exige igualmente, y también rechaza reportar contenido propio.
 */
@Component({
    selector: 'app-reportar-contenido',
    standalone: true,
    imports: [FormsModule],
    template: `
    @if (puedeReportar) {
      <button type="button" class="reportar" (click)="abrir($event)"
        [attr.aria-label]="'Reportar ' + etiquetaTipo.toLowerCase()">Reportar</button>
    }

    @if (abierto) {
      <div class="fondo" (click)="cerrar()">
        <form class="ventana" role="dialog" aria-modal="true" aria-labelledby="reportar-titulo" novalidate
          (click)="$event.stopPropagation()" (ngSubmit)="enviar()">
          <h2 id="reportar-titulo">Reportar {{ etiquetaTipo.toLowerCase() }}</h2>

          @if (resultado) {
            @if (resultado.duplicate) {
              <p class="aviso" role="status"><strong>Ya reportaste esto.</strong> Tu reporte #{{ resultado.id }} sigue
                abierto ({{ etiquetaEstado(resultado) }}).</p>
            } @else {
              <p class="exito" role="status"><strong>Reporte radicado.</strong> Tu número de reporte es
                #{{ resultado.id }}. Estado: {{ etiquetaEstado(resultado) }}.</p>
            }
            <div class="acciones">
              <button type="button" class="primario" (click)="verReporte(resultado.id)">Ver mi reporte</button>
              <button type="button" (click)="cerrar()">Cerrar</button>
            </div>
          } @else {
            @if (general) {
              <p class="error" role="alert">{{ general }}</p>
              @if (reporteExistente !== null) {
                <button type="button" class="enlace" (click)="verReporte(reporteExistente)">Ver mi reporte
                  #{{ reporteExistente }}</button>
              }
            }

            <label for="reportar-motivo">Motivo</label>
            <select id="reportar-motivo" name="motivo" [(ngModel)]="motivo" (ngModelChange)="cambiaMotivo()"
              [attr.aria-invalid]="errores.motivo ? 'true' : null" [attr.aria-describedby]="descripcionMotivo">
              <option value="" disabled>Elige un motivo</option>
              @for (m of motivos; track m.code) {
                <option [value]="m.code">{{ m.label }}</option>
              }
            </select>
            @if (esProblemaDeCompra) {
              <p class="orientacion" id="reportar-motivo-ayuda" role="status">
                Esto parece un problema con una compra, y no se reporta como contenido. Para un pedido que no llegó,
                llegó defectuoso o que quieres devolver, usa las <strong>reclamaciones y devoluciones</strong> de tu
                pedido.
              </p>
            }
            @if (errores.motivo) {
              <p class="error" id="reportar-motivo-error" role="alert">{{ errores.motivo }}</p>
            }

            <label for="reportar-descripcion">¿Qué está mal?</label>
            <textarea id="reportar-descripcion" name="descripcion" rows="4" maxlength="2000"
              [(ngModel)]="descripcion" placeholder="Describe el problema con este contenido"
              [attr.aria-invalid]="errores.descripcion ? 'true' : null"
              [attr.aria-describedby]="errores.descripcion ? 'reportar-descripcion-error' : null"></textarea>
            @if (errores.descripcion) {
              <p class="error" id="reportar-descripcion-error" role="alert">{{ errores.descripcion }}</p>
            }

            <label for="reportar-imagenes">Imágenes de evidencia (opcional)</label>
            <input id="reportar-imagenes" type="file" accept="image/png,image/jpeg" multiple
              (change)="elegirImagenes($event)" [attr.aria-describedby]="'reportar-imagenes-ayuda'">
            <p class="ayuda" id="reportar-imagenes-ayuda">Hasta {{ maxImagenes }} imágenes JPG o PNG de 5 MB cada una.</p>
            @if (previas.length > 0) {
              <ul class="previas">
                @for (previa of previas; track previa.url; let i = $index) {
                  <li>
                    <img [src]="previa.url" [alt]="'Evidencia ' + (i + 1)">
                    <button type="button" class="enlace" (click)="quitarImagen(i)">Quitar</button>
                  </li>
                }
              </ul>
            }
            @if (errores.imagenes) {
              <p class="error" id="reportar-imagenes-error" role="alert">{{ errores.imagenes }}</p>
            }

            <div class="acciones">
              <button type="submit" class="primario" [disabled]="enviando || esProblemaDeCompra">
                {{ enviando ? 'Enviando…' : 'Enviar reporte' }}
              </button>
              <button type="button" (click)="cerrar()">Cancelar</button>
            </div>
          }
        </form>
      </div>
    }
  `,
    styles: [`
    :host { display: inline-block; }
    .reportar, .enlace { padding: 0; border: 0; background: none; color: #6b7280; font-size: 0.8rem;
      text-decoration: underline; cursor: pointer; }
    .fondo { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: center; justify-content: center;
      padding: 1rem; background: rgba(0, 0, 0, 0.5); }
    .ventana { display: flex; flex-direction: column; gap: 0.35rem; box-sizing: border-box; width: min(32rem, 100%);
      max-height: 100%; overflow-y: auto; padding: 1.25rem; border-radius: 0.75rem; background: #fff; color: #1f2933; }
    .ventana h2 { margin: 0 0 0.5rem; font-size: 1.15rem; }
    .ventana label { margin-top: 0.5rem; font-weight: 600; font-size: 0.9rem; }
    .ventana select, .ventana textarea, .ventana input[type='file'] { width: 100%; box-sizing: border-box;
      padding: 0.5rem; border: 1px solid #b7c0c8; border-radius: 0.5rem; font: inherit; }
    .ventana [aria-invalid='true'] { border-color: #b3261e; }
    .error { margin: 0.15rem 0; color: #b3261e; font-size: 0.85rem; font-weight: 600; }
    .exito { color: #17692f; }
    .aviso { color: #7a5b00; }
    .orientacion { margin: 0.25rem 0; padding: 0.6rem 0.75rem; border: 1px solid #e0a800; border-radius: 0.5rem;
      background: #fff8e1; font-size: 0.88rem; }
    .ayuda { margin: 0; color: #666; font-size: 0.8rem; }
    .previas { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0.25rem 0; padding: 0; list-style: none; }
    .previas li { display: flex; flex-direction: column; align-items: center; gap: 0.15rem; }
    .previas img { width: 4.5rem; height: 4.5rem; object-fit: cover; border-radius: 0.4rem; border: 1px solid #d8dee4; }
    .acciones { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
    .acciones button { padding: 0.55rem 1rem; border: 1px solid #b7c0c8; border-radius: 999px; background: #fff;
      font-weight: 600; cursor: pointer; }
    .acciones .primario { border-color: transparent; background: #1f2933; color: #fff; }
    .acciones button:disabled { opacity: 0.5; cursor: not-allowed; }
  `]
})
export class ReportarContenidoComponent implements OnDestroy {
    private readonly reportes = inject(ReportesService);
    private readonly auth = inject(AuthService);

    /** Tipo de contenido tal como lo nombra el backend (PUBLICACION, TIENDA...). */
    @Input({ required: true }) tipo!: string;
    /** Identificador del contenido reportado. */
    @Input({ required: true }) contenidoId!: number | string;

    readonly maxImagenes = MAX_IMAGENES;

    abierto = false;
    enviando = false;
    motivos: MotivoReporte[] = [];
    motivo = '';
    descripcion = '';
    imagenes: File[] = [];
    previas: { url: string }[] = [];
    errores: Partial<Record<CampoReporte, string>> = {};
    general: string | null = null;
    reporteExistente: number | null = null;
    resultado: ReporteRadicado | null = null;

    /** Solo con el rol activo de comprador o vendedor, el mismo que exige el backend. */
    get puedeReportar(): boolean {
        const rol = this.auth.cuenta()?.activeRole;
        return rol === 'COMPRADOR' || rol === 'VENDEDOR';
    }

    get etiquetaTipo(): string {
        return ETIQUETA_TIPO[this.tipo] ?? 'contenido';
    }

    get esProblemaDeCompra(): boolean {
        return this.motivos.find((m) => m.code === this.motivo)?.purchaseProblem === true;
    }

    get descripcionMotivo(): string | null {
        return this.errores.motivo ? 'reportar-motivo-error'
            : this.esProblemaDeCompra ? 'reportar-motivo-ayuda' : null;
    }

    etiquetaEstado(reporte: ReporteRadicado): string {
        return ETIQUETA_ESTADO[reporte.status];
    }

    abrir(evento: Event): void {
        evento.stopPropagation();
        this.reiniciar();
        this.abierto = true;
        if (this.motivos.length === 0) {
            this.reportes.motivos().subscribe({
                next: (motivos) => (this.motivos = motivos),
                error: (e: HttpErrorResponse) => this.aplicar(interpretarErrorReporte(e,
                    'No se pudieron cargar los motivos.'))
            });
        }
    }

    cerrar(): void {
        this.liberarPrevias();
        this.abierto = false;
    }

    /** Lleva al detalle de un reporte propio en "Mis reportes". */
    verReporte(id: number): void {
        this.cerrar();
        this.reportes.abrirPanel(id);
    }

    cambiaMotivo(): void {
        delete this.errores.motivo;
    }

    elegirImagenes(evento: Event): void {
        const entrada = evento.target as HTMLInputElement;
        const elegidas = Array.from(entrada.files ?? []);
        entrada.value = '';
        delete this.errores.imagenes;
        for (const archivo of elegidas) {
            if (this.imagenes.length >= MAX_IMAGENES) {
                this.errores.imagenes = `Solo puedes adjuntar ${MAX_IMAGENES} imágenes.`;
                break;
            }
            if (!TIPOS_IMAGEN.includes(archivo.type)) {
                this.errores.imagenes = `"${archivo.name}" no es una imagen JPG o PNG.`;
                continue;
            }
            if (archivo.size > MAX_BYTES_IMAGEN) {
                this.errores.imagenes = `"${archivo.name}" supera los 5 MB.`;
                continue;
            }
            this.imagenes.push(archivo);
            this.previas.push({ url: URL.createObjectURL(archivo) });
        }
    }

    quitarImagen(indice: number): void {
        URL.revokeObjectURL(this.previas[indice].url);
        this.imagenes.splice(indice, 1);
        this.previas.splice(indice, 1);
        delete this.errores.imagenes;
    }

    enviar(): void {
        if (this.enviando || this.esProblemaDeCompra) {
            return;
        }
        this.errores = {};
        this.general = null;
        this.reporteExistente = null;
        if (!this.motivo) {
            this.errores.motivo = 'Elige el motivo del reporte.';
        }
        if (!this.descripcion.trim()) {
            this.errores.descripcion = 'Describe qué está mal en el contenido.';
        }
        if (this.errores.motivo || this.errores.descripcion) {
            return;
        }
        this.enviando = true;
        this.reportes.radicar({
            contentType: this.tipo,
            contentId: String(this.contenidoId),
            reason: this.motivo,
            description: this.descripcion.trim()
        }, this.imagenes).pipe(finalize(() => (this.enviando = false))).subscribe({
            next: (radicado) => (this.resultado = radicado),
            error: (e: HttpErrorResponse) => this.aplicar(interpretarErrorReporte(e, 'No se pudo radicar el reporte.'))
        });
    }

    ngOnDestroy(): void {
        this.liberarPrevias();
    }

    private aplicar(error: ReturnType<typeof interpretarErrorReporte>): void {
        this.general = error.general;
        this.reporteExistente = error.reporteExistente;
        if (error.campo) {
            this.errores[error.campo.nombre] = error.campo.mensaje;
        }
    }

    private reiniciar(): void {
        this.liberarPrevias();
        this.motivo = '';
        this.descripcion = '';
        this.errores = {};
        this.general = null;
        this.reporteExistente = null;
        this.resultado = null;
    }

    private liberarPrevias(): void {
        this.previas.forEach((previa) => URL.revokeObjectURL(previa.url));
        this.previas = [];
        this.imagenes = [];
    }
}
