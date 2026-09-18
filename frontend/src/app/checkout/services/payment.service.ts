import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  PaymentRequest,
  PaymentResponse
} from '../models/payment.model';

@Injectable({
  providedIn: 'root'
})
export class PaymentService {

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    'http://localhost:8080/api/payments';

  process(
    request: PaymentRequest
  ): Observable<PaymentResponse> {

    return this.http.post<PaymentResponse>(
      `${this.apiUrl}/process`,
      request
    );
  }
}