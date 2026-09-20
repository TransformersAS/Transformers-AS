import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { Usuario } from '../models/usuario.model';
/** Encabezado mínimo de cuenta que distingue claramente una sesión anónima. */
@Component({ selector: 'app-perfil-resumen', standalone: true, changeDetection: ChangeDetectionStrategy.OnPush, template: `<p>{{ usuario ? 'Hola, ' + usuario.email : 'Ingresa para ver tu cuenta' }}</p>` })
export class PerfilResumenComponent { /** La identidad procede de /me a través de CuentaService. */ @Input() usuario: Usuario | null = null; }
