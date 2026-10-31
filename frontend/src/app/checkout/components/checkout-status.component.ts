import { Component, effect, inject, OnDestroy } from '@angular/core';
import { NgIf, DatePipe } from '@angular/common';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { CarritoService } from '../../carrito/services/carrito.service';
import { PaymentService } from '../services/payment.service';
import { HostedCheckout } from '../models/payment.model';

@Component({
  selector: 'app-checkout-status', standalone: true, imports: [NgIf, DatePipe],
  templateUrl: './checkout-status.component.html',
  styles: [':host section { margin: 1rem; padding: 1.25rem; border: 1px solid #cbd5e1; border-radius: 12px; background: #f0f9ff; color: #173039; } button { margin: .4rem; padding: .75rem 1rem; border-radius: 8px; cursor: pointer; } button:disabled { cursor: wait; opacity: .65; }']
})
export class CheckoutStatusComponent implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly payment = inject(PaymentService);
  private readonly cart = inject(CarritoService);
  id: string | null = new URLSearchParams(window.location.search).get('checkout');
  private paymentId: string | null = new URLSearchParams(window.location.search).get('payment_id');
  checkout: HostedCheckout | null = null;
  busy = false;
  confirming = false;
  error = '';
  sessionNotice = false;
  private account: number | null = null;
  private established = false;
  private generation = 0;
  private request?: Subscription;
  get buyer(): boolean { return this.auth.cuenta()?.activeRole === 'COMPRADOR'; }
  get canCancel(): boolean { return this.buyer && !!this.checkout && !this.checkout.orderId && this.checkout.status !== 'CANCELLED'; }
  get financialMessage(): string {
    switch (this.checkout?.financialStatus) {
      case 'REQUIRES_VERIFICATION': return 'Todavía debe verificarse si hubo un cobro.';
      case 'PENDING_RESOLUTION': return 'Existe una operación financiera cuya resolución sigue pendiente.';
      case 'REQUIRES_REVIEW': return 'La resolución financiera requiere revisión. No hay una devolución automática garantizada.';
      case 'REFUNDED': return 'Devolución confirmada según los registros financieros verificados.';
      default: return 'Consulta nuevamente para recuperar la información financiera registrada.';
    }
  }
  constructor() {
    effect(() => {
      const user = this.auth.cuenta();
      const next = user?.activeRole === 'COMPRADOR' ? user.accountId : null;
      if (next !== this.account) {
        this.generation++; this.request?.unsubscribe(); this.busy = false;
        this.checkout = null; this.error = ''; this.confirming = false;
        if (this.established) {
          this.sessionNotice = true;
          this.id = null; this.paymentId = null;
          const url = new URL(window.location.href);
          url.searchParams.delete('checkout'); url.searchParams.delete('payment_id');
          window.history.replaceState(null, '', url);
        }
        this.account = next;
        if (next !== null) this.established = true;
      }
      if (next !== null && this.id && !this.checkout && !this.busy) this.load(true);
    });
  }
  load(refresh = false): void {
    if (!this.id || !this.buyer || this.busy) return;
    const id = this.id, version = this.generation;
    this.busy = true; this.error = '';
    const source = refresh && this.paymentId && /^[0-9]{1,30}$/.test(this.paymentId)
      ? this.payment.refreshCheckout(id, this.paymentId) : this.payment.checkoutStatus(id);
    this.request = source.subscribe({
      next: value => {
        if (!this.valid(version)) return;
        this.checkout = value; this.busy = false;
        if (value.status === 'APPROVED') this.cart.refrescar();
      },
      error: err => { if (this.valid(version)) { this.busy = false; this.handleError(err); } }
    });
  }
  back(): void { if (!this.busy) this.confirming = false; }
  cancel(): void {
    if (!this.confirming || !this.canCancel || this.busy || !this.id) return;
    const version = this.generation, previous = this.checkout!;
    this.busy = true; this.error = '';
    this.request = this.payment.cancelCheckout(this.id).subscribe({
      next: result => {
        if (!this.valid(version)) return;
        this.checkout = { ...previous, ...result, checkoutUrl: null };
        this.confirming = false; this.busy = false;
      },
      error: err => {
        if (!this.valid(version)) return;
        this.busy = false; this.confirming = false; this.handleError(err);
        if (err.status === 409) {
          const message = this.error;
          this.load(); this.error = message;
        }
      }
    });
  }
  private valid(version: number): boolean {
    return version === this.generation && this.buyer && this.auth.cuenta()?.accountId === this.account;
  }
  private handleError(err: { status?: number }): void {
    const messages: Record<number, string> = {
      401: 'Tu sesión terminó. Inicia sesión para consultar esta compra.',
      403: 'No fue posible autorizar la operación. Comprueba la sesión, el rol comprador y vuelve a intentar.',
      404: 'No se encontró un checkout disponible para tu cuenta.',
      409: 'Ya existe un pedido. Consulta Mis pedidos para solicitar su cancelación si corresponde.'
    };
    this.error = messages[err.status ?? 0] ?? 'No se pudo completar la operación. Consulta el estado antes de volver a intentar.';
    if ([401, 403, 404].includes(err.status ?? 0)) this.checkout = null;
  }
  ngOnDestroy(): void { this.generation++; this.request?.unsubscribe(); }
}
