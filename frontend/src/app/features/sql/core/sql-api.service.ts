import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page, QueryParams, SavedQuery } from './models';
import { SQL_API_BASE } from './sql-api.config';
import type { CreateQueryRequest } from '../save/save-form';

/** Thin client for sql-service through the gateway. Later stories add mutations. */
@Injectable()
export class SqlApiService {
  private readonly http = inject(HttpClient);
  readonly baseUrl = SQL_API_BASE;

  listQueries(params: QueryParams = {}): Observable<Page<SavedQuery>> {
    return this.http.get<Page<SavedQuery>>(`${this.baseUrl}/queries`, { params: toHttpParams(params) });
  }

  getQuery(id: number): Observable<SavedQuery> {
    return this.http.get<SavedQuery>(`${this.baseUrl}/queries/${id}`);
  }

  /** FR-1: 201 with the full Saved Query (incl. `warnings[]`). */
  createQuery(body: CreateQueryRequest): Observable<SavedQuery> {
    return this.http.post<SavedQuery>(`${this.baseUrl}/queries`, body);
  }

  /** FR8: one copy = one use. 204, empty body. */
  recordUsage(id: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/queries/${id}/usage`, null);
  }
}

/** Drops empty values; arrays become repeated params. */
export function toHttpParams(params: QueryParams): HttpParams {
  let out = new HttpParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      for (const v of value) {
        out = out.append(key, v);
      }
    } else {
      out = out.set(key, String(value));
    }
  }
  return out;
}
