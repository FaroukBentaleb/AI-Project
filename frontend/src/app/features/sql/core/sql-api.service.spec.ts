import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { SqlApiService, toHttpParams } from './sql-api.service';

describe('SqlApiService', () => {
  let api: SqlApiService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [SqlApiService, provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(SqlApiService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('creates a query with a POST of the snake_case body', () => {
    const body = { title: 't', description: 'd', sql_content: 'SELECT 1', tags: [], notes: null, dialect: 'postgresql', is_llm_generated: false, original_nl_prompt: null };
    let id = 0;
    api.createQuery(body).subscribe((q) => (id = q.id));
    const req = backend.expectOne(`${api.baseUrl}/queries`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({ id: 7 }, { status: 201, statusText: 'Created' });
    expect(id).toBe(7);
  });

  it('targets the gateway SQL route', () => {
    expect(api.baseUrl).toBe(`${environment.apiBaseUrl}/sql`);
    expect(api.baseUrl.endsWith('/api/sql')).toBeTrue();
  });

  it('lists queries with params', () => {
    api.listQueries({ q: 'orders', page: 1, tags: ['a', 'b'], author: '' }).subscribe();
    const req = backend.expectOne((r) => r.url === `${api.baseUrl}/queries`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('orders');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.getAll('tags')).toEqual(['a', 'b']);
    expect(req.request.params.has('author')).toBeFalse();
    req.flush({ content: [], page: 1, size: 20, total_elements: 0, total_pages: 0 });
  });

  it('gets one query', () => {
    let title = '';
    api.getQuery(7).subscribe((q) => (title = q.title));
    backend.expectOne(`${api.baseUrl}/queries/7`).flush({ id: 7, title: 'Weekly orders' });
    expect(title).toBe('Weekly orders');
  });

  it('records usage with an empty POST', () => {
    let done = false;
    api.recordUsage(7).subscribe(() => (done = true));
    const req = backend.expectOne(`${api.baseUrl}/queries/7/usage`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(done).toBeTrue();
  });

  it('drops null and undefined params', () => {
    expect(toHttpParams({ a: null, b: undefined, c: false }).keys()).toEqual(['c']);
  });
});
