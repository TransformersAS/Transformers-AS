import { Component, Input, inject } from '@angular/core';
import { finalize } from 'rxjs';
import { MensajeriaService } from '../services/mensajeria.service';
@Component({ selector: 'app-contactar-vendedor', standalone: true,
  template: `<button type="button" (click)="contactar()" [disabled]="ocupado">Contactar al vendedor</button>
    @if (error) { <p role="alert">{{error}}</p> }` })
export class ContactarVendedorComponent {
  @Input({required: true}) productId!: number;
  @Input() orderId: number | null = null;
  private readonly servicio = inject(MensajeriaService);
  ocupado = false; error = '';
  contactar() {
    this.ocupado = true; this.error = '';
    this.servicio.iniciar(this.productId, this.orderId).pipe(finalize(() => this.ocupado = false)).subscribe({
      next: c => this.servicio.abrir(c.id), error: e => this.error = e.error?.message || 'No se pudo abrir la conversación. Inicia sesión como comprador.'
    });
  }
}
