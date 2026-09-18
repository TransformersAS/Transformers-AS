import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CasoSoporte } from '../models/caso-soporte.model';
/** Indicador legible de carga operativa para agentes, sin simular datos reales. */
@Component({ selector: 'app-cola-soporte', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<p>{{ cantidad(casos) }} caso{{ cantidad(casos) === 1 ? '' : 's' }} por revisar.</p>` })
export class ColaSoporteComponent { /** Casos vienen del servicio con control de permisos en la futura ruta. */ @Input() casos: readonly CasoSoporte[] = []; /** Normaliza el conteo para mantener el template simple. */ cantidad(casos: readonly CasoSoporte[]): number { return casos.length; } }
