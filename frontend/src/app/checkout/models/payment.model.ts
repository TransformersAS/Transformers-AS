export interface PaymentRequest {
  paymentMethod: string;
  reservationIds: number[];
  addressId: number;
  shippingMethod: string;
  couponCode: string;
}

export interface HostedCheckout {
  id: string;
  status: 'STARTING' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVIEW';
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
