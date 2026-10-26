import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ApiError, Bug, BugFilter, BugInput, BugStats, Page, Status, TriageInput, TriageResult, TriageStatus
} from './bugs.models';

const PAGE_SIZE = 20;

@Injectable()
export class BugsApiService {
  private readonly base = `${environment.apiBaseUrl}/bugs`;

  constructor(private readonly http: HttpClient) {}

  list(filter: BugFilter): Observable<Page<Bug>> {
    let params = new HttpParams().set('sort', filter.sort).set('page', filter.page).set('size', PAGE_SIZE);
    for (const key of ['status', 'severity', 'category', 'q'] as const) {
      const value = filter[key]?.trim();
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<Bug>>(this.base, { params });
  }

  stats(): Observable<BugStats> {
    return this.http.get<BugStats>(`${this.base}/stats`);
  }

  get(id: number): Observable<Bug> {
    return this.http.get<Bug>(`${this.base}/${id}`);
  }

  create(input: BugInput): Observable<Bug> {
    return this.http.post<Bug>(this.base, input);
  }

  update(id: number, input: BugInput): Observable<Bug> {
    return this.http.put<Bug>(`${this.base}/${id}`, input);
  }

  changeStatus(id: number, status: Status): Observable<Bug> {
    return this.http.patch<Bug>(`${this.base}/${id}/status`, { status });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  triageStatus(): Observable<TriageStatus> {
    return this.http.get<TriageStatus>(`${this.base}/triage/status`);
  }

  /** Suggests severity, category and title for an unsaved report; nothing is stored. */
  triage(input: TriageInput): Observable<TriageResult> {
    return this.http.post<TriageResult>(`${this.base}/triage`, input);
  }

  /** Triage of a stored bug; the suggestion lands in its `ai` block. */
  triageStored(id: number): Observable<Bug> {
    return this.http.post<Bug>(`${this.base}/${id}/triage`, {});
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
      return 'The server is unreachable. Is the gateway running on port 8080?';
    }
    if (error.status === 503) {
      // The gateway answers 503 itself when Eureka has no bug-service instance to route to.
      return 'The gateway cannot find bug-service: it is not running or not registered in Eureka (http://localhost:8761).';
    }
  }
  return 'Something went wrong. Please try again.';
}

/** Error code of the service's envelope (e.g. LLM_QUOTA_EXCEEDED), or ''. */
export function errorCode(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiError> | null;
    return typeof body?.error === 'string' ? body.error : '';
  }
  return '';
}
