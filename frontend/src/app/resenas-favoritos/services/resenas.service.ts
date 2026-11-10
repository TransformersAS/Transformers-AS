import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { API_BASE } from '../../core/config/api.config';
export interface Review {id:number;productId:number;productName:string;rating:number;comment:string;images:number[];reply:{text:string;updatedAt:string}|null;orderId:number|null;createdAt:string;canManageReply:boolean;}
export interface Rating {productId:number;count:number;average:number;distribution:number[];}
export interface ReviewInput {orderId:number;productId:number;rating:number|null;comment:string;keepImageIds:number[];}
@Injectable({providedIn:'root'})
export class ResenasService {
  private http=inject(HttpClient);private url=`${API_BASE}/reviews`;
  eligibility(orderId:number,productId:number){return this.http.get<{eligible:boolean;message:string;review:Review|null}>(`${this.url}/eligibility`,{params:{orderId,productId}});}
  save(id:number|null,input:ReviewInput,files:File[]){
    let body:ReviewInput|FormData=input;
    if(files.length){body=new FormData();body.append('review',new Blob([JSON.stringify(input)],{type:'application/json'}));files.forEach(file=>(body as FormData).append('images',file,file.name));}
    return id===null?this.http.post<Review>(this.url,body):this.http.put<Review>(`${this.url}/${id}`,body);
  }
  remove(id:number){return this.http.delete<void>(`${this.url}/${id}`);}
  mine(page:number){return this.http.get<Review[]>(`${this.url}/mine`,{params:{page}});}
  product(product:number,page:number){return this.http.get<Review[]>(`${this.url}/products/${product}`,{params:{page}});}
  summary(product:number){return this.http.get<Rating>(`${this.url}/products/${product}/summary`);}
  seller(product:string,rating:string,answered:string,page:number){let params=new HttpParams().set('page',page);if(product)params=params.set('productId',product);if(rating)params=params.set('rating',rating);if(answered)params=params.set('answered',answered);return this.http.get<Review[]>(`${API_BASE}/seller/reviews`,{params});}
  sellerSummary(){return this.http.get<Rating[]>(`${API_BASE}/seller/reviews/summary`);}
  purchase(id:number){return this.http.get<{orderId:number;productId:number;productName:string;quantity:number;status:string;createdAt:string}>(`${API_BASE}/seller/reviews/${id}/purchase`);}
  reply(id:number,text:string){return this.http.put<void>(`${API_BASE}/seller/reviews/${id}/reply`,{text});}
  removeReply(id:number){return this.http.delete<void>(`${API_BASE}/seller/reviews/${id}/reply`);}
  image(id:number){return `${this.url}/images/${id}`;}
}
