export type InteractionType =
  | 'VIEW'
  | 'ADD_TO_CART'
  | 'PURCHASE'
  | 'SEARCH';

export interface InteractionRequest {
  productId: number | null;
  interactionType: InteractionType;
  searchTerm: string | null;
}