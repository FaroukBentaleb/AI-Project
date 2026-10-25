import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DISPLAY_NAME_KEY, IdentityService } from './identity.service';
import { SQL_API_BASE } from './sql-api.config';
import { userIdInterceptor } from './user-id.interceptor';
import { environment } from '../../../../environments/environment';

describe('userIdInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let identity: IdentityService;

  beforeEach(() => {
    localStorage.removeItem(DISPLAY_NAME_KEY);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([userIdInterceptor])), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    identity = TestBed.inject(IdentityService);
  });

  afterEach(() => {
    backend.verify();
    localStorage.removeItem(DISPLAY_NAME_KEY);
  });

  it('adds X-User-Id to SQL calls', () => {
    identity.setDisplayName('sami');
    http.get(`${SQL_API_BASE}/queries`).subscribe();
    expect(backend.expectOne(`${SQL_API_BASE}/queries`).request.headers.get('X-User-Id')).toBe('sami');
  });

  it('uses the new name after a change', () => {
    identity.setDisplayName('sami');
    http.get(`${SQL_API_BASE}/queries/1`).subscribe();
    backend.expectOne(`${SQL_API_BASE}/queries/1`).flush({});
    identity.setDisplayName('leila');
    http.get(`${SQL_API_BASE}/queries/2`).subscribe();
    expect(backend.expectOne(`${SQL_API_BASE}/queries/2`).request.headers.get('X-User-Id')).toBe('leila');
  });

  it('leaves other modules untouched', () => {
    identity.setDisplayName('sami');
    const bugs = `${environment.apiBaseUrl}/bugs/1`;
    const lookalike = `${environment.apiBaseUrl}/sqlish/1`;
    http.get(bugs).subscribe();
    http.get(lookalike).subscribe();
    expect(backend.expectOne(bugs).request.headers.has('X-User-Id')).toBeFalse();
    expect(backend.expectOne(lookalike).request.headers.has('X-User-Id')).toBeFalse();
  });

  it('sends no header when no name is set', () => {
    http.get(`${SQL_API_BASE}/queries`).subscribe();
    expect(backend.expectOne(`${SQL_API_BASE}/queries`).request.headers.has('X-User-Id')).toBeFalse();
  });
});
