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
    'http://localhost:8080/api/reservations';

  reserveCart(): Observable<ReservationResponse[]> {

    return this.http.post<ReservationResponse[]>(
      `${this.apiUrl}/cart`,
      null
    );
  }
}