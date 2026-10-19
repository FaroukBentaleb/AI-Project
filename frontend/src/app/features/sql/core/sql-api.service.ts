import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Page, QueryParams, SavedQuery, ValidateResponse } from './models';
import { SQL_API_BASE } from './sql-api.config';
import type { CreateQueryRequest, PatchQueryRequest } from '../save/save-form';

export interface DeprecateRequest {
  reason: string;
  replaced_by: number | null;
}

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

  /** FR5: only supplied fields change; `version` is required (409 `CONFLICT` when stale). */
  patchQuery(id: number, body: PatchQueryRequest): Observable<SavedQuery> {
    return this.http.patch<SavedQuery>(`${this.baseUrl}/queries/${id}`, body);
  }

  /** FR6: soft delete, 204. */
  deleteQuery(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/queries/${id}`);
  }

  /** FR7: deprecate with a reason and an optional replacement. */
  deprecateQuery(id: number, body: DeprecateRequest): Observable<SavedQuery> {
    return this.http.put<SavedQuery>(`${this.baseUrl}/queries/${id}/deprecation`, body);
  }

  /** FR7: undeprecate (idempotent). */
  restoreQuery(id: number): Observable<SavedQuery> {
    return this.http.delete<SavedQuery>(`${this.baseUrl}/queries/${id}/deprecation`);
  }

  /** FR12: structural read-only check; never executes or saves. 200 for accepted and rejected. */
  validate(sqlContent: string): Observable<ValidateResponse> {
    return this.http.post<ValidateResponse>(`${this.baseUrl}/validate`, { sql_content: sqlContent });
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
