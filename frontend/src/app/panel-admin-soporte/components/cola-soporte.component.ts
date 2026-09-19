import { Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FormsModule } from '@angular/forms';
import {
    IonBadge,
    IonButton,
    IonButtons,
    IonCard,
    IonCardContent,
    IonCardHeader,
    IonCardSubtitle,
    IonCardTitle,
    IonContent,
    IonHeader,
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
import { AdminSoporteService } from '../services/admin-soporte.service';
import {
    CasoDetalle,
    CasoModeracion,
    DecisionModeracion,
    DestinoInformacion,
    EstadoCaso
} from '../models/caso-soporte.model';

const MIN_JUSTIFICACION = 10;

@Component({
    selector: 'app-cola-soporte',
    standalone: true,
    imports: [
        DatePipe, FormsModule, IonBadge, IonButton, IonButtons, IonCard, IonCardContent, IonCardHeader,
        IonCardSubtitle, IonCardTitle, IonContent, IonHeader, IonItem, IonLabel, IonList, IonListHeader,
        IonSelect, IonSelectOption, IonText, IonTextarea, IonTitle, IonToolbar
    ],
    template: `
    <ion-header>
      <ion-toolbar color="primary">
        @if (detalle || cargandoDetalle) {
          <ion-buttons slot="start">
            <ion-button (click)="volverALista()">Volver</ion-button>
          </ion-buttons>
        }
        <ion-title>Moderación de reportes</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      @if (error) {
        <ion-text color="danger"><p>{{ error }}</p></ion-text>
      }

      @if (!detalle && !cargandoDetalle) {
        <ion-item>
          <ion-select label="Mostrar" [(ngModel)]="filtro" (ionChange)="cargarCola()">
            <ion-select-option value="ABIERTOS">Casos abiertos</ion-select-option>
            <ion-select-option value="RESUELTO">Casos resueltos</ion-select-option>
          </ion-select>
        </ion-item>

        <ion-list>
          @for (caso of casos; track caso.id) {
            <ion-item button (click)="verDetalle(caso.id)">
              <ion-label>
                <h2>{{ caso.contentType }} · {{ caso.contentId }}</h2>
                <p>{{ caso.reportCount }} {{ caso.reportCount === 1 ? 'reporte' : 'reportes' }}
                  · abierto {{ caso.openedAt | date: 'short' }}</p>
              </ion-label>
              <ion-badge slot="end" [color]="colorEstado(caso.status)">{{ caso.status }}</ion-badge>
            </ion-item>
          }
        </ion-list>

        @if (casos.length === 0 && !error) {
          <p class="vacio">No hay casos {{ filtro === 'RESUELTO' ? 'resueltos' : 'abiertos' }}.</p>
        }
      }

      @if (detalle; as caso) {
        <ion-card>
          <ion-card-header>
            <ion-card-subtitle>Caso #{{ caso.id }} · {{ caso.reportCount }} reporte(s)</ion-card-subtitle>
            <ion-card-title>{{ caso.content?.title || (caso.contentType + ' ' + caso.contentId) }}</ion-card-title>
          </ion-card-header>
          <ion-card-content>
            <p><strong>Estado:</strong> <ion-badge [color]="colorEstado(caso.status)">{{ caso.status }}</ion-badge>
              · contenido: <strong>{{ caso.contentState }}</strong></p>
            @if (caso.assignedAgentId) { <p><strong>Agente:</strong> {{ caso.assignedAgentId }}</p> }
            @if (caso.content?.text) { <p>{{ caso.content?.text }}</p> }
            @if (!caso.content) {
              <p class="nota">Este tipo de contenido aún no expone su vista a soporte.</p>
            }
          </ion-card-content>
        </ion-card>

        <ion-list>
          <ion-list-header>Reportes recibidos</ion-list-header>
          @for (reporte of caso.reports; track reporte.id) {
            <ion-item>
              <ion-label class="ion-text-wrap">
                <h3>{{ reporte.reason }} · {{ reporte.reporterId }}</h3>
                <p>{{ reporte.description }}</p>
                <p>{{ reporte.createdAt | date: 'short' }}</p>
                @for (evidencia of reporte.evidences; track evidencia.id) {
                  <p><a [href]="evidencia.fileUrl" target="_blank" rel="noopener">Evidencia ({{ evidencia.fileType }})</a></p>
                }
              </ion-label>
            </ion-item>
          }
        </ion-list>

        @if (caso.informationRequests.length > 0) {
          <ion-list>
            <ion-list-header>Solicitudes de información</ion-list-header>
            @for (solicitud of caso.informationRequests; track solicitud.id) {
              <ion-item>
                <ion-label class="ion-text-wrap">
                  <h3>{{ solicitud.target }} ({{ solicitud.targetUserId }}) · {{ solicitud.status }}</h3>
                  <p>{{ solicitud.message }}</p>
                  <p>Plazo: {{ solicitud.dueAt | date: 'medium' }}</p>
                  @if (solicitud.responseText) { <p><strong>Respuesta:</strong> {{ solicitud.responseText }}</p> }
                </ion-label>
              </ion-item>
            }
          </ion-list>
        }

        @if (caso.decisions.length > 0) {
          <ion-list>
            <ion-list-header>Decisiones</ion-list-header>
            @for (decision of caso.decisions; track decision.id) {
              <ion-item>
                <ion-label class="ion-text-wrap">
                  <h3>{{ decision.decision }} · {{ decision.agentId }}</h3>
                  <p>{{ decision.justification }}</p>
                  <p>{{ decision.createdAt | date: 'short' }}</p>
                </ion-label>
              </ion-item>
            }
          </ion-list>
        }

        @if (caso.history.length > 0) {
          <ion-list>
            <ion-list-header>Casos anteriores sobre este contenido</ion-list-header>
            @for (previo of caso.history; track previo.caseId) {
              <ion-item>
                <ion-label class="ion-text-wrap">
                  <h3>Caso #{{ previo.caseId }} · {{ previo.reportCount }} reporte(s)</h3>
                  @for (decision of previo.decisions; track decision.id) {
                    <p>{{ decision.decision }}: {{ decision.justification }}</p>
                  }
                </ion-label>
              </ion-item>
            }
          </ion-list>
        }

        @if (caso.status !== 'RESUELTO') {
          <ion-card>
            <ion-card-header><ion-card-title>Acciones</ion-card-title></ion-card-header>
            <ion-card-content>
              @if (!caso.assignedAgentId) {
                <ion-button expand="block" color="secondary" (click)="tomar()">Tomar caso</ion-button>
              }

              <ion-item>
                <ion-textarea label="Mensaje o justificación" labelPlacement="stacked" [(ngModel)]="texto" rows="3"
                  placeholder="Mínimo 10 caracteres. No incluya la identidad de quienes reportaron."></ion-textarea>
              </ion-item>

              <ion-item>
                <ion-select label="Pedir información a" [(ngModel)]="destino">
                  <ion-select-option value="REPORTADOR">Reportador</ion-select-option>
                  <ion-select-option value="PROPIETARIO">Propietario del contenido</ion-select-option>
                </ion-select>
              </ion-item>
              @if (destino === 'REPORTADOR' && caso.reports.length > 1) {
                <ion-item>
                  <ion-select label="Reportador" [(ngModel)]="reportadorElegido">
                    @for (id of reportadores(caso); track id) {
                      <ion-select-option [value]="id">{{ id }}</ion-select-option>
                    }
                  </ion-select>
                </ion-item>
              }
              <ion-button expand="block" color="warning" [disabled]="!textoValido()" (click)="pedirInformacion()">
                Pedir información (plazo 72 h)
              </ion-button>

              <div class="acciones">
                <ion-button color="success" [disabled]="!textoValido() || solicitudVigente(caso)"
                  (click)="decidir('MANTENER')">Mantener</ion-button>
                <ion-button color="warning" [disabled]="!textoValido()"
                  (click)="decidir('OCULTAR_TEMPORALMENTE')">Ocultar temporalmente</ion-button>
                <ion-button color="danger" [disabled]="!textoValido() || solicitudVigente(caso)"
                  (click)="decidir('RETIRAR')">Retirar</ion-button>
                <ion-button color="dark" fill="outline" [disabled]="!textoValido()"
                  (click)="remitir()">Remitir a administración de cuentas</ion-button>
              </div>
              @if (!textoValido()) {
                <p class="nota">Escribe al menos 10 caracteres en el cuadro de arriba para habilitar los botones.</p>
              }
              @if (solicitudVigente(caso)) {
                <p class="nota">Hay una solicitud de información vigente: espere la respuesta o el vencimiento del
                  plazo para mantener o retirar el contenido.</p>
              }
              @if (aviso) { <ion-text color="success"><p>{{ aviso }}</p></ion-text> }
            </ion-card-content>
          </ion-card>
        }
      }
    </ion-content>
  `,
    styles: [`
    :host { display: flex; flex-direction: column; height: 100%; }
    .vacio, .nota { text-align: center; color: #666; margin-top: 1rem; }
    .acciones { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 8px; }
  `]
})
export class ColaSoporteComponent implements OnInit {
    private soporte = inject(AdminSoporteService);

    casos: CasoModeracion[] = [];
    detalle: CasoDetalle | null = null;
    cargandoDetalle = false;
    filtro: 'ABIERTOS' | 'RESUELTO' = 'ABIERTOS';
    error = '';
    aviso = '';
    texto = '';
    destino: DestinoInformacion = 'REPORTADOR';
    reportadorElegido = '';

    ngOnInit() {
        this.cargarCola();
    }

    cargarCola() {
        this.error = '';
        const estado: EstadoCaso | undefined = this.filtro === 'RESUELTO' ? 'RESUELTO' : undefined;
        this.soporte.obtenerCola(estado).subscribe({
            next: pagina => this.casos = pagina.items,
            error: (e: HttpErrorResponse) => this.error = this.mensaje(e, 'No se pudo cargar la cola de casos.')
        });
    }

    verDetalle(id: number) {
        this.cargandoDetalle = true;
        this.error = '';
        this.texto = '';
        this.aviso = '';
        this.recargar(id);
    }

    volverALista() {
        this.detalle = null;
        this.cargandoDetalle = false;
        this.error = '';
        this.cargarCola();
    }

    tomar() {
        this.ejecutar(id => this.soporte.tomarCaso(id), 'Caso tomado.');
    }

    pedirInformacion() {
        if (!this.textoValido()) {
            return;
        }
        const objetivo = this.destino === 'REPORTADOR' ? this.reportadorElegido : undefined;
        this.ejecutar(id => this.soporte.solicitarInformacion(id, this.destino, this.texto.trim(), objetivo),
            'Solicitud enviada; el destinatario tiene 72 horas para responder.', true);
    }

    decidir(decision: DecisionModeracion) {
        if (!this.textoValido()) {
            return;
        }
        const version = this.detalle?.version ?? 0;
        this.ejecutar(id => this.soporte.decidir(id, decision, this.texto.trim(), version),
            'Decisión registrada y aplicada.', true);
    }

    remitir() {
        if (!this.textoValido()) {
            return;
        }
        this.ejecutar(id => this.soporte.remitirAAdministracionDeCuentas(id, this.texto.trim()),
            'Caso remitido a administración de cuentas.', true);
    }

    reportadores(caso: CasoDetalle): string[] {
        return [...new Set(caso.reports.map(reporte => reporte.reporterId))];
    }

    textoValido(): boolean {
        return this.texto.trim().length >= MIN_JUSTIFICACION;
    }

    /** Mientras haya una solicitud abierta y vigente, el servidor rechaza mantener o retirar. */
    solicitudVigente(caso: CasoDetalle): boolean {
        const ahora = Date.now();
        return caso.informationRequests.some(s => s.status === 'ABIERTA' && new Date(s.dueAt).getTime() >= ahora);
    }

    colorEstado(estado: string): string {
        switch (estado) {
            case 'PENDIENTE': return 'primary';
            case 'EN_REVISION': return 'warning';
            case 'INFO_SOLICITADA': return 'tertiary';
            case 'RESUELTO': return 'success';
            default: return 'medium';
        }
    }

    private ejecutar(accion: (id: number) => Observable<unknown>, exito: string, limpiar = false) {
        if (!this.detalle) {
            return;
        }
        const id = this.detalle.id;
        this.error = '';
        this.aviso = '';
        accion(id).subscribe({
            next: () => {
                this.aviso = exito;
                if (limpiar) {
                    this.texto = '';
                }
                this.recargar(id);
            },
            error: (e: HttpErrorResponse) => {
                this.error = this.mensaje(e, 'No se pudo completar la acción.');
                // Un 409 suele significar que el caso cambió: se recarga para mostrar el estado real.
                if (e.status === 409) {
                    this.recargar(id);
                }
            }
        });
    }

    private recargar(id: number) {
        this.soporte.obtenerDetalle(id).subscribe({
            next: detalle => {
                this.detalle = detalle;
                this.cargandoDetalle = false;
                this.reportadorElegido = this.reportadores(detalle)[0] ?? '';
            },
            error: (e: HttpErrorResponse) => {
                this.cargandoDetalle = false;
                this.error = this.mensaje(e, 'No se pudo cargar el detalle del caso.');
            }
        });
    }

    private mensaje(e: HttpErrorResponse, porDefecto: string): string {
        return e.error?.message || porDefecto;
    }
}
