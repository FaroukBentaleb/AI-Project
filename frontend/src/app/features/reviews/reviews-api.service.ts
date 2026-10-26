import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Review, ReviewRequest } from './reviews.models';

@Injectable()
export class ReviewsApiService {
  private readonly base = `${environment.apiBaseUrl}/reviews`;

  constructor(private readonly http: HttpClient) {}

  list(): Observable<Review[]> {
    return this.http.get<Review[]>(this.base);
  }

  get(id: number): Observable<Review> {
    return this.http.get<Review>(`${this.base}/${id}`);
  }

  create(request: ReviewRequest): Observable<Review> {
    return this.http.post<Review>(this.base, request);
  }

  update(id: number, request: ReviewRequest): Observable<Review> {
    return this.http.put<Review>(`${this.base}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}

export function reviewErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as { message?: unknown; detail?: unknown } | null;
    if (typeof body?.message === 'string') {
      return body.message;
    }
    if (typeof body?.detail === 'string') {
      return body.detail;
    }
    if (error.status === 0) {
      return 'The server is unreachable. Check that the review services are running.';
    }
  }
  return 'Something went wrong while handling this review. Please try again.';
}
