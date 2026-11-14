import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subject } from 'rxjs';
import { API_BASE } from '../core/config/api.config';

export interface AddressInput { recipientName:string;street:string;city:string;department:string;postalCode:string;phone:string; }
export interface DeliveryAddress extends AddressInput { id:number;defaultAddress:boolean; }
@Injectable({providedIn:'root'})
export class DireccionesService {
  private readonly http=inject(HttpClient);
  private readonly url=`${API_BASE}/addresses`;
  readonly changes=new Subject<void>();
  list(){return this.http.get<DeliveryAddress[]>(this.url);}
  save(id:number|null,data:AddressInput){return id===null?this.http.post<DeliveryAddress>(this.url,data):this.http.put<DeliveryAddress>(`${this.url}/${id}`,data);}
  remove(id:number){return this.http.delete<void>(`${this.url}/${id}`);}
  defaultAddress(id:number){return this.http.put<DeliveryAddress>(`${this.url}/${id}/default`,{});}
}
