export interface CheckoutPreviewRequest {
  addressId: number;
  shippingMethod: string;
  couponCode: string;
}

export interface CheckoutPreviewResponse {
  subtotal: number;
  discount: number;
  shippingCost: number;
  total: number;
  couponValid: boolean;
}