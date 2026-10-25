import { HttpClient, HttpErrorResponse, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiError, CommitDetail, CommitSummary } from './commits.models';

@Injectable()
export class CommitsApiService {
  private readonly base = `${environment.apiBaseUrl}/commits`;

  constructor(private readonly http: HttpClient) {}

  /** Emits upload progress events, then the final response event carrying the created commit. */
  upload(project: string, file: File): Observable<HttpEvent<CommitDetail>> {
    const body = new FormData();
    body.append('project', project);
    body.append('file', file);
    return this.http.post<CommitDetail>(`${this.base}/uploads`, body, { observe: 'events', reportProgress: true });
  }

  list(project?: string): Observable<CommitSummary[]> {
    const params = project ? new HttpParams().set('project', project) : new HttpParams();
    return this.http.get<CommitSummary[]>(`${this.base}/uploads`, { params });
  }

  get(id: number): Observable<CommitDetail> {
    return this.http.get<CommitDetail>(`${this.base}/uploads/${id}`);
  }

  updateMessage(id: number, message: string): Observable<CommitDetail> {
    return this.http.put<CommitDetail>(`${this.base}/uploads/${id}`, { message });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/uploads/${id}`);
  }

  projects(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/projects`);
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
