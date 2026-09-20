import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import {
  IonBadge,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonInput,
  IonItem,
  IonLabel,
  IonList,
  IonListHeader,
  IonSelect,
  IonSelectOption,
  IonText,
  IonTextarea,
  IonTitle,
  IonToolbar
} from '@ionic/angular/standalone';

import { AuthService, Rol } from '../../core/services/auth.service';
import { ItemPedido, Pedido } from '../../pedidos/models/pedido.model';
import { PedidosService } from '../../pedidos/services/pedidos.service';
import {
  DecisionSoporte,
  EstadoReclamacion,
  MensajeReclamacion,
  Reclamacion
} from '../models/reclamacion.model';
import { ReclamacionesService } from '../services/reclamaciones.service';

const ETIQUETA_ESTADO: Record<EstadoReclamacion, string> = {
  OPEN: 'Abierta',
  INFO_REQUESTED: 'Información solicitada',
  SOLUTION_PROPOSED: 'Solución propuesta',
  ESCALATED: 'Escalada a soporte',
  RESOLVED: 'Resuelta'
};

const COLOR_ESTADO: Record<EstadoReclamacion, string> = {
  OPEN: 'primary',
  INFO_REQUESTED: 'warning',
  SOLUTION_PROPOSED: 'tertiary',
  ESCALATED: 'danger',
  RESOLVED: 'success'
};

const ETIQUETA_AUTOR: Record<MensajeReclamacion['author'], string> = {
  BUYER: 'Comprador',
  SELLER: 'Vendedor',
  SUPPORT: 'Soporte'
};

const ETIQUETA_TIPO: Record<MensajeReclamacion['kind'], string> = {
  MESSAGE: 'Mensaje',
  INFO_REQUEST: 'Pide información',
  PROPOSAL: 'Propone una solución',
  ESCALATION: 'Escala a soporte',
  DECISION: 'Decisión'
};

const ETIQUETA_RESOLUCION: Record<NonNullable<Reclamacion['resolution']>, string> = {
  SOLUTION_ACCEPTED: 'El comprador aceptó la solución del vendedor',
  REFUND_GRANTED: 'Soporte concedió un reembolso',
  REJECTED: 'Soporte rechazó la reclamación'
};

/**
 * Reclamaciones de compra (CU-13) para los tres actores: el comprador abre y responde, el vendedor pide información
 * o propone una solución, y soporte decide las escaladas. Las reglas las decide el backend; aquí solo se muestran las
 * acciones que el rol y el estado permiten, y los mensajes del backend cuando rechaza algo.
 */
@Component({
  selector: 'app-reclamaciones',
  standalone: true,
  imports: [
    CurrencyPipe, DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonContent, IonHeader, IonInput, IonItem,
    IonLabel, IonList, IonListHeader, IonSelect, IonSelectOption, IonText, IonTextarea, IonTitle, IonToolbar
  ],
  template: `
    <ion-header>
      <ion-toolbar color="primary">
        @if (mostrarNueva || seleccionada) {
          <ion-buttons slot="start">
            <ion-button (click)="volver()">Volver</ion-button>
          </ion-buttons>
        }
        <ion-title>{{ titulo }}</ion-title>
        <ion-buttons slot="end">
          <ion-button (click)="cerrar.emit()">Cerrar</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) {
        <ion-text color="danger"><p role="alert">{{ error }}</p></ion-text>
      }
      @if (aviso) {
        <ion-text color="success"><p role="status">{{ aviso }}</p></ion-text>
      }

      <!-- ===== Comprador: abrir una reclamación ===== -->
      @if (mostrarNueva) {
        <form (ngSubmit)="abrir()">
          <ion-item>
            <ion-select label="Compra" labelPlacement="stacked" name="pedido" [(ngModel)]="pedidoId"
              (ionChange)="elegirPedido()">
              @for (pedido of pedidos; track pedido.id) {
                <ion-select-option [value]="pedido.id">
                  Pedido #{{ pedido.id }} · {{ pedido.createdAt | date: 'shortDate' }}
                </ion-select-option>
              }
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-select label="Producto afectado" labelPlacement="stacked" name="producto" [(ngModel)]="productoId">
              @for (item of items; track item.productId) {
                <ion-select-option [value]="item.productId">{{ item.productName }}</ion-select-option>
              }
            </ion-select>
          </ion-item>
          <ion-item>
            <ion-textarea label="Cuéntanos qué pasó" labelPlacement="stacked" name="descripcion"
              [(ngModel)]="descripcion" maxlength="1000" [autoGrow]="true"></ion-textarea>
          </ion-item>
          <ion-item>
            <ion-textarea label="Evidencias (una dirección por línea, máximo 5)" labelPlacement="stacked"
              name="evidencias" [(ngModel)]="evidencias" [autoGrow]="true"></ion-textarea>
          </ion-item>
          <ion-button type="submit">Enviar reclamación</ion-button>
        </form>
      }

      @else if (seleccionada) {
        @let r = seleccionada!;
        <h2>Reclamación #{{ r.id }} · {{ r.productName }}</h2>
        <p>
          <ion-badge [color]="color(r.status)">{{ etiquetaEstado(r.status) }}</ion-badge>
          Compra #{{ r.orderId }} · Pagado por este producto: {{ r.itemTotal | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}
        </p>
        <p><strong>Problema:</strong> {{ r.description }}</p>
        @if (r.evidenceUrls.length > 0) {
          <p><strong>Evidencias:</strong></p>
          <ul>
            @for (url of r.evidenceUrls; track $index) {
              <li><a [href]="url" target="_blank" rel="noopener noreferrer">{{ url }}</a></li>
            }
          </ul>
        }
        @if (r.proposalText && r.status !== 'RESOLVED') {
          <p><strong>Solución propuesta:</strong> {{ r.proposalText }}
            @if (r.proposedRefund) { · Reembolso ofrecido: {{ r.proposedRefund | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} }</p>
        }
        @if (r.resolution) {
          <p><strong>Resultado:</strong> {{ etiquetaResolucion(r.resolution) }}.
            @if (r.refundAmount) {
              Reembolso: {{ r.refundAmount | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}
              ({{ r.refundStatus === 'COMPLETED' ? 'completado' : 'en proceso' }}).
            }</p>
        }

        <ion-list>
          <ion-list-header>Historial</ion-list-header>
          @for (mensaje of r.messages; track $index) {
            <ion-item>
              <ion-label class="ion-text-wrap">
                <h3>{{ etiquetaAutor(mensaje) }} · {{ etiquetaTipo(mensaje) }}
                  <small>{{ mensaje.createdAt | date: 'short' }}</small></h3>
                <p>{{ mensaje.message }}</p>
              </ion-label>
            </ion-item>
          } @empty {
            <ion-item><ion-label>Todavía no hay mensajes.</ion-label></ion-item>
          }
        </ion-list>

        <!-- Comprador -->
        @if (rol === 'COMPRADOR' && r.status !== 'RESOLVED') {
          <ion-item>
            <ion-textarea label="Escribe un mensaje o el motivo para escalar" labelPlacement="stacked"
              [(ngModel)]="texto" maxlength="1000" [autoGrow]="true"></ion-textarea>
          </ion-item>
          <ion-button [disabled]="!texto.trim()" (click)="tramitar(servicio.escribir(r.id, texto), 'Mensaje enviado.')">Enviar mensaje</ion-button>
          @if (r.status === 'SOLUTION_PROPOSED') {
            <ion-button color="success" (click)="tramitar(servicio.aceptarSolucion(r.id), 'Aceptaste la solución.')">Aceptar la solución</ion-button>
          }
          @if (r.status !== 'ESCALATED') {
            <ion-button color="danger" fill="outline" (click)="tramitar(servicio.escalar(r.id, texto), 'Reclamación escalada a soporte.')">Escalar a soporte</ion-button>
          }
        }

        <!-- Vendedor -->
        @if (rol === 'VENDEDOR' && r.status !== 'RESOLVED' && r.status !== 'ESCALATED') {
          <ion-item>
            <ion-textarea label="Mensaje para el comprador" labelPlacement="stacked" [(ngModel)]="texto"
              maxlength="1000" [autoGrow]="true"></ion-textarea>
          </ion-item>
          @if (r.status === 'OPEN') {
            <ion-button fill="outline" [disabled]="!texto.trim()" (click)="tramitar(servicio.pedirInformacion(r.id, texto), 'Información solicitada.')">Pedir información</ion-button>
          }
          <ion-item>
            <ion-input label="Reembolso que ofreces (COP, opcional; máximo lo pagado)" labelPlacement="stacked"
              type="number" min="0" [(ngModel)]="monto"></ion-input>
          </ion-item>
          <ion-button [disabled]="!texto.trim()" (click)="tramitar(servicio.proponerSolucion(r.id, texto, numeroMonto), 'Solución propuesta.')">Proponer solución</ion-button>
        }

        <!-- Soporte -->
        @if (rol === 'SOPORTE' && r.status === 'ESCALATED') {
          <ion-item>
            <ion-select label="Decisión" labelPlacement="stacked" [(ngModel)]="decision">
              <ion-select-option value="REFUND_GRANTED">Conceder reembolso</ion-select-option>
              <ion-select-option value="REJECTED">Rechazar la reclamación</ion-select-option>
            </ion-select>
          </ion-item>
          @if (decision === 'REFUND_GRANTED') {
            <ion-item>
              <ion-input label="Monto a reembolsar (COP; máximo lo pagado)" labelPlacement="stacked" type="number"
                min="0" [(ngModel)]="monto"></ion-input>
            </ion-item>
          }
          <ion-item>
            <ion-textarea label="Explica tu decisión" labelPlacement="stacked" [(ngModel)]="texto"
              maxlength="1000" [autoGrow]="true"></ion-textarea>
          </ion-item>
          <ion-button [disabled]="!texto.trim()" (click)="tramitar(servicio.decidir(r.id, decision, decision === 'REFUND_GRANTED' ? numeroMonto : null, texto), 'Decisión registrada.')">Registrar decisión</ion-button>
        }
      }

      @else {
        @if (rol === 'COMPRADOR') {
          <ion-button (click)="nueva()">Nueva reclamación</ion-button>
        }
        @if (rol === 'SOPORTE') {
          <ion-item>
            <ion-select label="Mostrar" labelPlacement="stacked" [(ngModel)]="estadoSoporte" (ionChange)="cargar()">
              <ion-select-option value="ESCALATED">Escaladas (por decidir)</ion-select-option>
              <ion-select-option value="RESOLVED">Resueltas</ion-select-option>
            </ion-select>
          </ion-item>
        }
        <ion-list>
          @for (reclamacion of reclamaciones; track reclamacion.id) {
            <ion-item button detail (click)="seleccionar(reclamacion)">
              <ion-label>
                <h2>#{{ reclamacion.id }} · {{ reclamacion.productName }}</h2>
                <p>Compra #{{ reclamacion.orderId }} · {{ reclamacion.createdAt | date: 'short' }}</p>
              </ion-label>
              <ion-badge slot="end" [color]="color(reclamacion.status)">{{ etiquetaEstado(reclamacion.status) }}</ion-badge>
            </ion-item>
          } @empty {
            <ion-item><ion-label>No hay reclamaciones que mostrar.</ion-label></ion-item>
          }
        </ion-list>
      }
    </ion-content>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    ion-content { flex: 1; }
    form { margin-bottom: 1.5rem; }
    small { margin-left: 0.5rem; color: #666; font-weight: 400; }
  `]
})
export class ReclamacionesComponent implements OnInit {
  readonly servicio = inject(ReclamacionesService);
  private readonly pedidosServicio = inject(PedidosService);
  private readonly auth = inject(AuthService);

  @Output() cerrar = new EventEmitter<void>();

  error = '';
  aviso = '';

  reclamaciones: Reclamacion[] = [];
  seleccionadaId: number | null = null;

  // Comprador: formulario para abrir una reclamación.
  mostrarNueva = false;
  pedidos: Pedido[] = [];
  items: ItemPedido[] = [];
  pedidoId: number | null = null;
  productoId: number | null = null;
  descripcion = '';
  evidencias = '';

  // Acciones sobre la reclamación seleccionada.
  texto = '';
  monto: number | null = null;
  decision: DecisionSoporte = 'REFUND_GRANTED';
  estadoSoporte: EstadoReclamacion = 'ESCALATED';

  ngOnInit(): void {
    this.cargar();
  }

  get rol(): Rol | null {
    return this.auth.cuenta()?.activeRole ?? null;
  }

  get titulo(): string {
    switch (this.rol) {
      case 'VENDEDOR': return 'Reclamaciones de mi tienda';
      case 'SOPORTE': return 'Reclamaciones para soporte';
      default: return 'Mis reclamaciones';
    }
  }

  /** El campo numérico de Ionic puede entregar texto: se convierte a número, o a nulo si está vacío. */
  get numeroMonto(): number | null {
    return this.monto === null || (this.monto as unknown) === '' ? null : Number(this.monto);
  }

  get seleccionada(): Reclamacion | null {
    return this.reclamaciones.find(reclamacion => reclamacion.id === this.seleccionadaId) ?? null;
  }

  // ---------- Textos ----------

  etiquetaEstado(estado: EstadoReclamacion): string {
    return ETIQUETA_ESTADO[estado];
  }

  color(estado: EstadoReclamacion): string {
    return COLOR_ESTADO[estado];
  }

  etiquetaAutor(mensaje: MensajeReclamacion): string {
    return ETIQUETA_AUTOR[mensaje.author];
  }

  etiquetaTipo(mensaje: MensajeReclamacion): string {
    return ETIQUETA_TIPO[mensaje.kind];
  }

  etiquetaResolucion(resolucion: NonNullable<Reclamacion['resolution']>): string {
    return ETIQUETA_RESOLUCION[resolucion];
  }

  // ---------- Lista y navegación ----------

  /** Carga la lista con la entrada del backend que corresponde al rol activo. */
  cargar(): void {
    const lista = this.rol === 'VENDEDOR' ? this.servicio.listarDeLaTienda()
      : this.rol === 'SOPORTE' ? this.servicio.listarParaSoporte(this.estadoSoporte)
        : this.servicio.listarMias();
    lista.subscribe({
      next: reclamaciones => (this.reclamaciones = reclamaciones),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  seleccionar(reclamacion: Reclamacion): void {
    this.limpiarMensajes();
    this.texto = '';
    this.monto = null;
    this.seleccionadaId = reclamacion.id;
  }

  volver(): void {
    this.seleccionadaId = null;
    this.mostrarNueva = false;
    this.limpiarMensajes();
    this.cargar();
  }

  // ---------- Comprador: abrir ----------

  nueva(): void {
    this.limpiarMensajes();
    this.mostrarNueva = true;
    this.pedidosServicio.listarMisPedidos().subscribe({
      next: pedidos => (this.pedidos = pedidos),
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  /** Al elegir una compra se cargan sus productos para escoger el afectado. */
  elegirPedido(): void {
    this.productoId = null;
    this.items = [];
    if (this.pedidoId !== null) {
      this.pedidosServicio.obtenerPedido(this.pedidoId).subscribe({
        next: pedido => (this.items = pedido.items),
        error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
      });
    }
  }

  abrir(): void {
    if (this.pedidoId === null || this.productoId === null) {
      this.error = 'Elige la compra y el producto afectado.';
      return;
    }
    const evidencias = this.evidencias.split('\n').map(linea => linea.trim()).filter(linea => linea !== '');
    this.servicio.abrir({
      orderId: this.pedidoId, productId: this.productoId, description: this.descripcion, evidenceUrls: evidencias
    }).subscribe({
      next: reclamacion => {
        this.limpiarMensajes();
        this.aviso = 'Reclamación enviada. El vendedor la revisará.';
        this.mostrarNueva = false;
        this.pedidoId = this.productoId = null;
        this.descripcion = this.evidencias = '';
        this.items = [];
        this.reclamaciones = [reclamacion, ...this.reclamaciones];
      },
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  // ---------- Acciones sobre la reclamación abierta ----------

  /** Ejecuta una acción del rol, muestra el resultado (o el mensaje del backend) y recarga la lista. */
  tramitar(operacion: Observable<Reclamacion>, mensaje: string): void {
    this.limpiarMensajes();
    operacion.subscribe({
      next: () => {
        this.aviso = mensaje;
        this.texto = '';
        this.monto = null;
        this.cargar();
      },
      error: (respuesta: HttpErrorResponse) => this.mostrarError(respuesta)
    });
  }

  private limpiarMensajes(): void {
    this.error = '';
    this.aviso = '';
  }

  private mostrarError(respuesta: HttpErrorResponse): void {
    this.error = respuesta.error?.message ?? 'No se pudo completar la operación.';
  }
}
