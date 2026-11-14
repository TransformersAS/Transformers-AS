export interface PaymentRequest {
  paymentMethod: string;
  reservationIds: number[];
  addressId: number;
  shippingMethod: string;
  couponCode: string;
}

export interface HostedCheckout {
  id: string;
  status: 'STARTING' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVIEW' | 'REFUNDED' | 'CANCELLED';
  cancelledAt?: string | null;
  financialStatus?: CheckoutFinancialStatus | null;
  checkoutUrl: string | null;
  orderId: number | null;
  total: number;
}

export interface PaymentResponse {
  transactionId: string;
  status: string;
  message: string;
  orderId: number | null;
  total: number | null;
}

export type CheckoutFinancialStatus = 'REQUIRES_VERIFICATION' | 'PENDING_RESOLUTION' | 'REQUIRES_REVIEW' | 'REFUNDED';
export interface CheckoutCancellation {
  id: string;
  cancelledAt: string;
  status: 'CANCELLED';
  financialStatus: CheckoutFinancialStatus;
  message: string;
}
