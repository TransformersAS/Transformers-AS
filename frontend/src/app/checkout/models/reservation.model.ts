export interface ReservationResponse {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  status: string;
  expiresAt: string;
}