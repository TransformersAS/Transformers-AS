import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  InteractionRequest
} from '../models/interaction.model';

@Injectable({
  providedIn: 'root'
})
export class InteractionService {

  private readonly http =
    inject(HttpClient);

  private readonly apiUrl =
    '/api/interactions';

  register(
    request: InteractionRequest
  ): Observable<unknown> {

    return this.http.post(
      this.apiUrl,
      request
    );
  }
}