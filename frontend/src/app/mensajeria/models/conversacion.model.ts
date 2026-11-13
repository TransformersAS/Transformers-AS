export interface Conversacion {
  id: number; buyerId: number; sellerId: number; storeId: number; productId: number; orderId: number | null;
  productName: string; storeName: string; closed: boolean; archived: boolean;
  unread: number; readThrough: number; updatedAt: string;
}
export interface Mensaje { id: number; conversationId: number; senderId: number; text: string; createdAt: string; image: boolean; }
