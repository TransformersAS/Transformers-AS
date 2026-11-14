import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar } from '@ionic/angular/standalone';

import {
  anchoBarra, fechaCorta, hoy, moneda, numero, periodoAnterior, puntosLinea, sumarDias, validarPeriodo, variacion
} from '../analitica-formato';
import {
  Comparacion, ConsultaAnalitica, IndicadorComparable, InformeVentas, OpcionesAnalitica
} from '../models/analitica.model';
import { AnaliticaService } from '../services/analitica.service';

/** Campos del formulario que pueden tener un error propio (RNF-027). */
type Campo = 'desde' | 'hasta' | 'compDesde' | 'compHasta';

interface Indicador {
  clave: IndicadorComparable | 'marginPercent';
  etiqueta: string;
  valor: string;
  comparacion: Comparacion | null;
  /** Para costos y reembolsos subir es malo: el color de la variación se invierte. */
  menosEsMejor: boolean;
}

/** Medidas de la gráfica de la serie, en unidades del viewBox. */
const GRAFICA = { ancho: 640, alto: 220, margen: 28 };

/**
 * Analítica de ventas y rentabilidad del vendedor (CU-26): periodo con filtros por producto o categoría, indicadores,
 * comparación con otro periodo, gráficas y exportación a Excel. Los cálculos los hace el backend; aquí se piden, se
 * presentan y se explican los casos sin datos (A1, A3, A4, A5). Si la analítica falla, el error queda en este panel
 * y el resto del marketplace sigue funcionando (A7).
 */
@Component({
  selector: 'app-analitica-ventas',
  standalone: true,
  imports: [FormsModule, IonButton, IonButtons, IonContent, IonHeader, IonTitle, IonToolbar],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        <ion-title>Analítica de ventas</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      <form class="consulta" (ngSubmit)="consultar()" novalidate aria-label="Periodo y filtros de la analítica">
        <fieldset>
          <legend>Periodo</legend>
          <label>Desde
            <input type="date" name="desde" [(ngModel)]="desde" [attr.aria-invalid]="!!errores.desde"
              [attr.aria-describedby]="errores.desde ? 'error-desde' : null" />
          </label>
          @if (errores.desde) { <span class="error-campo" id="error-desde">{{ errores.desde }}</span> }
          <label>Hasta
            <input type="date" name="hasta" [(ngModel)]="hasta" [attr.aria-invalid]="!!errores.hasta"
              [attr.aria-describedby]="errores.hasta ? 'error-hasta' : null" />
          </label>
          @if (errores.hasta) { <span class="error-campo" id="error-hasta">{{ errores.hasta }}</span> }
        </fieldset>

        <fieldset>
          <legend>Filtros (opcionales)</legend>
          <label>Producto
            <select name="producto" [(ngModel)]="productoId">
              <option [ngValue]="null">Todos los productos</option>
              @for (producto of opciones?.products ?? []; track producto.id) {
                <option [ngValue]="producto.id">{{ producto.name }}{{ producto.hasCost ? '' : ' (sin costo)' }}</option>
              }
            </select>
          </label>
          <label>Categoría
            <select name="categoria" [(ngModel)]="categoria">
              <option value="">Todas las categorías</option>
              @for (nombre of opciones?.categories ?? []; track nombre) {
                <option [value]="nombre">{{ nombre }}</option>
              }
            </select>
          </label>
        </fieldset>

        <fieldset>
          <legend>Comparación</legend>
          <label class="casilla">
            <input type="checkbox" name="comparar" [(ngModel)]="comparar" (ngModelChange)="alCambiarComparacion($event)" />
            Comparar con otro periodo
          </label>
          @if (comparar) {
            <label>Desde
              <input type="date" name="compDesde" [(ngModel)]="compDesde" [attr.aria-invalid]="!!errores.compDesde"
                [attr.aria-describedby]="errores.compDesde ? 'error-comp-desde' : null" />
            </label>
            @if (errores.compDesde) { <span class="error-campo" id="error-comp-desde">{{ errores.compDesde }}</span> }
            <label>Hasta
              <input type="date" name="compHasta" [(ngModel)]="compHasta" [attr.aria-invalid]="!!errores.compHasta"
                [attr.aria-describedby]="errores.compHasta ? 'error-comp-hasta' : null" />
            </label>
            @if (errores.compHasta) { <span class="error-campo" id="error-comp-hasta">{{ errores.compHasta }}</span> }
            <button type="button" class="secundario" (click)="usarPeriodoAnterior()">Usar el periodo anterior</button>
          }
        </fieldset>

        <div class="acciones">
          <button type="submit" class="principal" [disabled]="cargando">{{ cargando ? 'Consultando…' : 'Consultar' }}</button>
          <button type="button" class="secundario" [disabled]="!informe || exportando" (click)="exportar()">
            {{ exportando ? 'Generando Excel…' : 'Exportar a Excel' }}
          </button>
        </div>
      </form>

      @if (errorCarga) {
        <div class="aviso error" role="alert">
          <p>{{ errorCarga }}</p>
          <button type="button" class="secundario" (click)="consultar()">Reintentar</button>
        </div>
      }
      @if (errorExportar) {
        <div class="aviso error" role="alert">
          <p>{{ errorExportar }}</p>
          <button type="button" class="secundario" (click)="exportar()">Reintentar la exportación</button>
        </div>
      }

      @if (informe; as datos) {
        <section aria-label="Resultados">
          <p class="nota">{{ datos.storeName }} · {{ descripcionPeriodo(datos.current.from, datos.current.to) }}
            @if (datos.comparison) { · comparado con {{ descripcionPeriodo(datos.comparison.from, datos.comparison.to) }} }
            @if (datos.productName) { · Producto: {{ datos.productName }} }
            @if (datos.category) { · Categoría: {{ datos.category }} }
          </p>

          @if (!datos.current.hasData) {
            <p class="aviso" role="status">{{ hayFiltros(datos)
              ? 'No hay resultados para esa combinación de producto y categoría en el periodo.'
              : 'No hubo ventas en este periodo. Los indicadores se muestran en cero.' }}</p>
          }
          @if (datos.comparison && !datos.comparison.hasData) {
            <p class="aviso" role="status">El periodo de comparación no tiene ventas: no hay datos suficientes para
              completar la comparación. Los resultados del periodo principal siguen visibles.</p>
          }
          @if (!datos.current.indicators.costComplete) {
            <p class="aviso" role="status">{{ datos.current.indicators.unitsWithoutCost }} unidad(es) vendidas no tienen
              costo unitario registrado: los costos y la ganancia solo incluyen las que sí lo tienen. Regístralo en
              «Mis productos».</p>
          }

          <ul class="indicadores" aria-label="Indicadores">
            @for (indicador of indicadores; track indicador.clave) {
              <li>
                <span class="etiqueta">{{ indicador.etiqueta }}</span>
                <strong>{{ indicador.valor }}</strong>
                @if (indicador.comparacion; as c) {
                  <span class="comparado">Antes: {{ formatear(indicador.clave, c.previous) }}</span>
                  <span class="variacion" [class.buena]="esBuena(indicador, c)" [class.mala]="esMala(indicador, c)">
                    {{ c.difference >= 0 ? '+' : '−' }}{{ formatear(indicador.clave, abs(c.difference)) }}
                    · {{ textoVariacion(c) }}
                  </span>
                }
              </li>
            }
          </ul>

          <h3>Ventas y costos en el tiempo</h3>
          <svg class="grafica" [attr.viewBox]="'0 0 ' + grafica.ancho + ' ' + grafica.alto" role="img"
            [attr.aria-label]="resumenSerie(datos)">
            <line [attr.x1]="grafica.margen" [attr.x2]="grafica.ancho - grafica.margen"
              [attr.y1]="grafica.alto - grafica.margen" [attr.y2]="grafica.alto - grafica.margen" class="eje" />
            @if (datos.comparison) {
              <polyline [attr.points]="lineaComparacion(datos)" class="linea comparacion" />
            }
            <polyline [attr.points]="lineaCostos(datos)" class="linea costos" />
            <polyline [attr.points]="lineaVentas(datos)" class="linea ventas" />
            <text [attr.x]="grafica.margen" y="16" class="rotulo">{{ moneda(maximoSerie(datos)) }}</text>
            <text [attr.x]="grafica.margen" [attr.y]="grafica.alto - 8" class="rotulo">{{ fechaCorta(primerPunto(datos)) }}</text>
            <text [attr.x]="grafica.ancho - grafica.margen" [attr.y]="grafica.alto - 8" class="rotulo"
              text-anchor="end">{{ fechaCorta(ultimoPunto(datos)) }}</text>
          </svg>
          <p class="leyenda">
            <span class="muestra ventas"></span> Ventas
            <span class="muestra costos"></span> Costos
            @if (datos.comparison) { <span class="muestra comparacion"></span> Ventas del periodo de comparación }
            · Agrupado {{ textoAgrupacion(datos.granularity) }}
          </p>
          <details>
            <summary>Ver los datos de la gráfica</summary>
            <table>
              <thead><tr><th scope="col">Desde</th><th scope="col">Ventas</th><th scope="col">Costos</th><th scope="col">Pedidos</th></tr></thead>
              <tbody>
                @for (punto of datos.current.series; track punto.start) {
                  <tr><td>{{ fechaCorta(punto.start) }}</td><td>{{ moneda(punto.sales) }}</td><td>{{ moneda(punto.costs) }}</td><td>{{ punto.orders }}</td></tr>
                }
              </tbody>
            </table>
          </details>

          <div class="columnas">
            <div>
              <h3>Productos más vendidos</h3>
              @if (datos.current.topProducts.length === 0) {
                <p class="nota">Sin ventas en el periodo.</p>
              } @else {
                <ol class="barras" aria-label="Productos más vendidos por unidades">
                  @for (producto of datos.current.topProducts; track producto.productId) {
                    <li>
                      <span class="nombre">{{ producto.name }}</span>
                      <span class="barra" [style.width.%]="anchoBarra(producto.units, maximoUnidades(datos))"></span>
                      <span class="cifra">{{ producto.units }} u.</span>
                    </li>
                  }
                </ol>
              }
            </div>
            <div>
              <h3>Ventas por categoría</h3>
              @if (datos.current.categories.length === 0) {
                <p class="nota">Sin ventas en el periodo.</p>
              } @else {
                <ul class="barras" aria-label="Ventas por categoría">
                  @for (categoria of datos.current.categories; track categoria.category) {
                    <li>
                      <span class="nombre">{{ categoria.category }}</span>
                      <span class="barra categoria" [style.width.%]="anchoBarra(categoria.sales, maximoCategorias(datos))"></span>
                      <span class="cifra">{{ moneda(categoria.sales) }}</span>
                    </li>
                  }
                </ul>
              }
            </div>
          </div>

          @if (datos.current.topProducts.length > 0) {
            <h3>Detalle por producto</h3>
            <table class="detalle">
              <thead>
                <tr><th scope="col">Producto</th><th scope="col">Unidades</th><th scope="col">Ventas</th>
                  <th scope="col">Reembolsos</th><th scope="col">Costos</th><th scope="col">Ganancia</th></tr>
              </thead>
              <tbody>
                @for (producto of datos.current.topProducts; track producto.productId) {
                  <tr>
                    <th scope="row">{{ producto.name }}<small>{{ producto.category }}</small></th>
                    <td>{{ producto.units }}</td>
                    <td>{{ moneda(producto.sales) }}</td>
                    <td>{{ moneda(producto.refunds) }}</td>
                    <td>{{ moneda(producto.costs) }}@if (producto.unitsWithoutCost > 0) { <small>parcial</small> }</td>
                    <td>{{ moneda(producto.profit) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    .consulta { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1rem; }
    fieldset { border: 1px solid var(--ion-color-medium); border-radius: 0.5rem; padding: 0.5rem 0.75rem;
      display: flex; flex-wrap: wrap; gap: 0.5rem 0.75rem; align-items: flex-end; }
    legend { font-weight: 600; padding: 0 0.25rem; }
    label { display: flex; flex-direction: column; font-size: 0.85rem; gap: 0.2rem; }
    label.casilla { flex-direction: row; align-items: center; gap: 0.4rem; }
    input[type=date], select { padding: 0.35rem; border: 1px solid #aaa; border-radius: 0.3rem; font: inherit; }
    input[aria-invalid=true] { border-color: var(--ion-color-danger); }
    .error-campo { color: var(--ion-color-danger); font-size: 0.8rem; flex-basis: 100%; }
    .acciones { display: flex; gap: 0.5rem; align-items: center; flex-basis: 100%; }
    button { font: inherit; border-radius: 0.3rem; padding: 0.45rem 0.9rem; cursor: pointer; }
    button.principal { background: var(--ion-color-primary); color: #fff; border: none; }
    button.secundario { background: transparent; border: 1px solid var(--ion-color-primary); color: var(--ion-color-primary); }
    button:disabled { opacity: 0.5; cursor: default; }
    .aviso { background: #fff8e1; border-left: 4px solid #f0b400; padding: 0.5rem 0.75rem; margin: 0.5rem 0; }
    .aviso.error { background: #fdecea; border-left-color: var(--ion-color-danger); }
    .nota { color: #666; font-size: 0.9rem; }
    .indicadores { list-style: none; padding: 0; display: grid; gap: 0.5rem;
      grid-template-columns: repeat(auto-fill, minmax(10.5rem, 1fr)); }
    .indicadores li { border: 1px solid #ddd; border-radius: 0.5rem; padding: 0.6rem; display: flex; flex-direction: column; gap: 0.15rem; }
    .indicadores .etiqueta { font-size: 0.8rem; color: #555; }
    .indicadores strong { font-size: 1.15rem; }
    .comparado { font-size: 0.8rem; color: #555; }
    .variacion { font-size: 0.8rem; }
    .variacion.buena { color: #1b7f3b; }
    .variacion.mala { color: #b3261e; }
    .grafica { width: 100%; max-width: 720px; height: auto; background: #fafafa; border-radius: 0.5rem; }
    .eje { stroke: #bbb; }
    .linea { fill: none; stroke-width: 2.5; }
    .linea.ventas { stroke: #1565c0; }
    .linea.costos { stroke: #ef6c00; }
    .linea.comparacion { stroke: #78909c; stroke-dasharray: 6 4; }
    .rotulo { font-size: 11px; fill: #555; }
    .leyenda { font-size: 0.85rem; display: flex; flex-wrap: wrap; align-items: center; gap: 0.35rem; }
    .muestra { display: inline-block; width: 1.4rem; height: 0.25rem; border-radius: 1px; }
    .muestra.ventas { background: #1565c0; } .muestra.costos { background: #ef6c00; }
    .muestra.comparacion { background: repeating-linear-gradient(90deg, #78909c 0 6px, transparent 6px 10px); }
    .columnas { display: grid; gap: 1rem; grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr)); }
    .barras { padding: 0; list-style-position: inside; }
    .barras li { display: grid; grid-template-columns: 8rem 1fr auto; align-items: center; gap: 0.5rem; margin: 0.3rem 0; }
    .barras .nombre { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .barra { display: block; height: 0.8rem; background: #1565c0; border-radius: 0.2rem; }
    .barra.categoria { background: #2e7d32; }
    .cifra { font-size: 0.85rem; white-space: nowrap; }
    table { border-collapse: collapse; width: 100%; font-size: 0.9rem; }
    th, td { border-bottom: 1px solid #e0e0e0; padding: 0.35rem; text-align: right; }
    th:first-child, td:first-child { text-align: left; }
    th small, td small { display: block; color: #777; font-weight: normal; }
  `]
})
export class AnaliticaVentasComponent implements OnInit {
  private readonly servicio = inject(AnaliticaService);

  @Output() cerrar = new EventEmitter<void>();

  readonly grafica = GRAFICA;
  readonly moneda = moneda;
  readonly fechaCorta = fechaCorta;
  readonly anchoBarra = anchoBarra;
  readonly abs = Math.abs;

  desde = sumarDias(hoy(), -29);
  hasta = hoy();
  comparar = false;
  compDesde = '';
  compHasta = '';
  productoId: number | null = null;
  categoria = '';

  opciones: OpcionesAnalitica | null = null;
  informe: InformeVentas | null = null;
  /** La consulta que produjo el informe mostrado: la exportación usa exactamente esa (RF-127). */
  private consultaMostrada: ConsultaAnalitica | null = null;
  indicadores: Indicador[] = [];
  errores: Partial<Record<Campo, string>> = {};
  errorCarga = '';
  errorExportar = '';
  cargando = false;
  exportando = false;

  ngOnInit(): void {
    this.servicio.opciones().subscribe({
      next: opciones => (this.opciones = opciones),
      // Sin opciones la analítica sigue sirviendo, solo que sin filtros.
      error: () => (this.opciones = { products: [], categories: [] })
    });
    this.consultar();
  }

  alCambiarComparacion(activa: boolean): void {
    if (activa && !this.compDesde && !this.compHasta) {
      this.usarPeriodoAnterior();
    }
    if (!activa) {
      delete this.errores.compDesde;
      delete this.errores.compHasta;
    }
  }

  usarPeriodoAnterior(): void {
    if (this.desde && this.hasta && this.desde <= this.hasta) {
      const anterior = periodoAnterior(this.desde, this.hasta);
      this.compDesde = anterior.desde;
      this.compHasta = anterior.hasta;
    }
  }

  /** Valida en pantalla con las mismas reglas del backend y, si todo está bien, consulta. */
  consultar(): void {
    this.errores = this.validar();
    if (Object.keys(this.errores).length > 0) {
      return;
    }
    const consulta: ConsultaAnalitica = {
      from: this.desde, to: this.hasta,
      compareFrom: this.comparar ? this.compDesde : null, compareTo: this.comparar ? this.compHasta : null,
      productId: this.productoId, category: this.categoria || null
    };
    this.cargando = true;
    this.errorCarga = '';
    this.errorExportar = '';
    this.servicio.analizar(consulta).subscribe({
      next: informe => {
        this.cargando = false;
        this.informe = informe;
        this.consultaMostrada = consulta;
        this.indicadores = this.armarIndicadores(informe);
      },
      error: (respuesta: HttpErrorResponse) => {
        this.cargando = false;
        this.mostrarErrorConsulta(respuesta);
      }
    });
  }

  /** A6: si falla, los resultados siguen en pantalla y se puede reintentar sin volver a consultar. */
  exportar(): void {
    if (!this.consultaMostrada) {
      return;
    }
    const consulta = this.consultaMostrada;
    this.exportando = true;
    this.errorExportar = '';
    this.servicio.exportar(consulta).subscribe({
      next: archivo => {
        this.exportando = false;
        this.descargar(archivo, `analitica-ventas-${consulta.from}_${consulta.to}.xlsx`);
      },
      error: () => {
        this.exportando = false;
        this.errorExportar = 'No se pudo generar el archivo Excel. Tus resultados siguen aquí: puedes intentarlo de nuevo.';
      }
    });
  }

  // ---------- Presentación ----------

  formatear(clave: Indicador['clave'], valor: number): string {
    return clave === 'orders' || clave === 'units' ? numero(valor) : moneda(valor);
  }

  textoVariacion(comparacion: Comparacion): string {
    return variacion(comparacion.percentAvailable ? comparacion.percentChange : null);
  }

  esBuena(indicador: Indicador, comparacion: Comparacion): boolean {
    return indicador.menosEsMejor ? comparacion.difference < 0 : comparacion.difference > 0;
  }

  esMala(indicador: Indicador, comparacion: Comparacion): boolean {
    return indicador.menosEsMejor ? comparacion.difference > 0 : comparacion.difference < 0;
  }

  hayFiltros(informe: InformeVentas): boolean {
    return informe.productId !== null || !!informe.category;
  }

  descripcionPeriodo(desde: string, hasta: string): string {
    return `${fechaCorta(desde)} a ${fechaCorta(hasta)}`;
  }

  textoAgrupacion(agrupacion: InformeVentas['granularity']): string {
    return agrupacion === 'DAY' ? 'por día' : agrupacion === 'WEEK' ? 'por semana' : 'por mes';
  }

  maximoSerie(informe: InformeVentas): number {
    const valores = informe.current.series.flatMap(punto => [punto.sales, punto.costs])
      .concat(informe.comparison?.series.map(punto => punto.sales) ?? []);
    return Math.max(0, ...valores);
  }

  lineaVentas(informe: InformeVentas): string {
    return this.linea(informe.current.series.map(punto => punto.sales), informe);
  }

  lineaCostos(informe: InformeVentas): string {
    return this.linea(informe.current.series.map(punto => punto.costs), informe);
  }

  lineaComparacion(informe: InformeVentas): string {
    return this.linea(informe.comparison?.series.map(punto => punto.sales) ?? [], informe);
  }

  primerPunto(informe: InformeVentas): string {
    return informe.current.series.length > 0 ? informe.current.series[0].start : informe.current.from;
  }

  ultimoPunto(informe: InformeVentas): string {
    const serie = informe.current.series;
    return serie.length > 0 ? serie[serie.length - 1].start : informe.current.to;
  }

  resumenSerie(informe: InformeVentas): string {
    const pico = informe.current.series.reduce((mayor, punto) => punto.sales > mayor.sales ? punto : mayor,
      informe.current.series[0] ?? { start: informe.current.from, sales: 0, costs: 0, orders: 0 });
    return `Ventas y costos ${this.textoAgrupacion(informe.granularity)} del ${this.descripcionPeriodo(
      informe.current.from, informe.current.to)}. Mayor venta: ${moneda(pico.sales)} desde el ${fechaCorta(pico.start)}.`;
  }

  maximoUnidades(informe: InformeVentas): number {
    return Math.max(0, ...informe.current.topProducts.map(producto => producto.units));
  }

  maximoCategorias(informe: InformeVentas): number {
    return Math.max(0, ...informe.current.categories.map(categoria => categoria.sales));
  }

  // ---------- Internos ----------

  private linea(valores: number[], informe: InformeVentas): string {
    return puntosLinea(valores, GRAFICA.ancho, GRAFICA.alto, GRAFICA.margen, this.maximoSerie(informe));
  }

  private validar(): Partial<Record<Campo, string>> {
    const hoyIso = hoy();
    const errores: Partial<Record<Campo, string>> = { ...validarPeriodo(this.desde, this.hasta, hoyIso) };
    if (this.comparar) {
      const comparacion = validarPeriodo(this.compDesde, this.compHasta, hoyIso);
      if (comparacion.desde) {
        errores.compDesde = comparacion.desde;
      }
      if (comparacion.hasta) {
        errores.compHasta = comparacion.hasta;
      }
    }
    return errores;
  }

  /** Un periodo rechazado por el backend se marca en su campo; cualquier otro fallo es A7. */
  private mostrarErrorConsulta(respuesta: HttpErrorResponse): void {
    const codigo = respuesta.error?.code;
    const campo = respuesta.error?.details?.field;
    if ((codigo === 'INVALID_PERIOD' || codigo === 'INVALID_COMPARISON_PERIOD') && (campo === 'from' || campo === 'to')) {
      const destino: Campo = codigo === 'INVALID_PERIOD'
        ? (campo === 'from' ? 'desde' : 'hasta')
        : (campo === 'from' ? 'compDesde' : 'compHasta');
      this.errores = { [destino]: respuesta.error.message };
      return;
    }
    this.errorCarga = respuesta.status === 404 && codigo === 'PRODUCT_NOT_FOUND'
      ? 'Ese producto ya no pertenece a tu tienda. Elige otro.'
      : 'No se pudo cargar la analítica en este momento. Puedes reintentar; el resto del marketplace sigue disponible.';
  }

  private armarIndicadores(informe: InformeVentas): Indicador[] {
    const valores = informe.current.indicators;
    const comparacion = (clave: IndicadorComparable) =>
      informe.comparisons.find(item => item.indicator === clave) ?? null;
    const fila = (clave: IndicadorComparable, etiqueta: string, menosEsMejor = false): Indicador =>
      ({ clave, etiqueta, valor: this.formatear(clave, valores[clave]), comparacion: comparacion(clave), menosEsMejor });
    return [
      fila('sales', 'Ventas'),
      fila('refunds', 'Reembolsos', true),
      fila('netRevenue', 'Ingresos netos'),
      fila('costs', 'Costos', true),
      fila('profit', 'Ganancia'),
      {
        clave: 'marginPercent', etiqueta: 'Margen', menosEsMejor: false, comparacion: null,
        valor: valores.marginPercent === null ? 'No calculable' : `${numero(valores.marginPercent)} %`
      },
      fila('orders', 'Pedidos'),
      fila('units', 'Unidades vendidas'),
      fila('averageTicket', 'Ticket promedio')
    ];
  }

  private descargar(archivo: Blob, nombre: string): void {
    const direccion = URL.createObjectURL(archivo);
    const enlace = document.createElement('a');
    enlace.href = direccion;
    enlace.download = nombre;
    enlace.click();
    URL.revokeObjectURL(direccion);
  }
}
