import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ReservationResponse
} from '../models/reservation.model';

@Injectable({
  providedIn: 'root'
})
export class ReservationService {

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/reservations`;

  reserveCart(): Observable<ReservationResponse[]> {

    return this.http.post<ReservationResponse[]>(
      `${this.apiUrl}/cart`,
      null
    );
  }
}