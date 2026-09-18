export interface PaymentRequest {
  paymentMethod: string;
  reservationIds: number[];
  addressId: number;
  shippingMethod: string;
  couponCode: string;
}

export interface PaymentResponse {
  transactionId: string;
  status: string;
  message: string;
  orderId: number | null;
  total: number | null;
}