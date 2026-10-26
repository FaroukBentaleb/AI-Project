import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiError, Language, SnippetDetail, SnippetRequest, SnippetSummary } from './snippets.models';

@Injectable()
export class SnippetsApiService {
  private readonly base = `${environment.apiBaseUrl}/snippets`;

  constructor(private readonly http: HttpClient) {}

  create(request: SnippetRequest): Observable<SnippetDetail> {
    return this.http.post<SnippetDetail>(this.base, request);
  }

  list(language?: Language | '', q?: string): Observable<SnippetSummary[]> {
    let params = new HttpParams();
    if (language) {
      params = params.set('language', language);
    }
    if (q?.trim()) {
      params = params.set('q', q.trim());
    }
    return this.http.get<SnippetSummary[]>(this.base, { params });
  }

  get(id: number): Observable<SnippetDetail> {
    return this.http.get<SnippetDetail>(`${this.base}/${id}`);
  }

  update(id: number, request: SnippetRequest): Observable<SnippetDetail> {
    return this.http.put<SnippetDetail>(`${this.base}/${id}`, request);
  }

  /** (Re)writes the docstring of a saved snippet with the model. */
  generateDocstring(id: number): Observable<SnippetDetail> {
    return this.http.post<SnippetDetail>(`${this.base}/${id}/docstring`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}

/** The readable message of a failed call, using the service's error envelope when there is one. */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiError> | null;
    if (body && typeof body.message === 'string') {
      return body.message;
    }
    if (error.status === 0) {
      return 'The server is unreachable.';
    }
  }
  return 'Something went wrong. Please try again.';
}
