export interface RecommendedProduct {
  id: number;
  name: string;
  description: string;
  price: number;
  stock: number;
  category: string;
}

export interface RecommendationResponse {
  userId: number;
  strategy: string;
  products: RecommendedProduct[];
}