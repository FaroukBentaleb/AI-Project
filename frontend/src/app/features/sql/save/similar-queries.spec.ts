import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SqlApiService } from '../core/sql-api.service';
import { ToastService } from '../shared/toast.service';
import { InAppHistory } from '../core/in-app-history.service';
import { SIMILAR_DEBOUNCE_MS, SaveQueryComponent, confirmLeaveSaveForm } from './save-query.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

/** Story 8.4: similar queries on the Save form (advisory, never blocking). */
describe('SaveQueryComponent similar queries', () => {
  let backend: HttpTestingController;
  let toasts: ToastService;
  let h: RouterTestingHarness;
  let api: SqlApiService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/queries/new', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm] },
          { path: 'sql/queries/:id', component: StubComponent }
        ]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        SqlApiService,
        ToastService,
        InAppHistory
      ]
    });
    backend = TestBed.inject(HttpTestingController);
    toasts = TestBed.inject(ToastService);
    api = TestBed.inject(SqlApiService);
    h = await RouterTestingHarness.create('/sql/queries/new');
  });

  afterEach(() => {
    toasts.dismiss();
    backend.verify();
  });

  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const similarUrl = () => `${api.baseUrl}/queries/similar`;
  const quiet = () => new Promise((r) => setTimeout(r, SIMILAR_DEBOUNCE_MS + 60));

  function type(id: string, value: string): void {
    const field = q<HTMLInputElement>(`#sql-save-${id}`)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    h.detectChanges();
  }

  function fillRequired(): void {
    type('title', 'Weekly orders');
    type('description', 'Orders per region');
    type('sql_content', 'SELECT 1');
  }

  it('asks once after typing settles and shows up to 3 links opening in a new tab', async () => {
    fillRequired();
    backend.expectNone(similarUrl());
    await quiet();
    const req = backend.expectOne(similarUrl());
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'Weekly orders', description: 'Orders per region', sql_content: 'SELECT 1' });
    req.flush([
      { id: 1, title: 'Orders by region' },
      { id: 2, title: 'Weekly orders v1' },
      { id: 3, title: 'Region totals' },
      { id: 4, title: 'Extra' }
    ]);
    h.detectChanges();

    const links = Array.from(el().querySelectorAll<HTMLAnchorElement>('.similar-link'));
    expect(links.map((a) => a.textContent!.trim())).toEqual(['Orders by region', 'Weekly orders v1', 'Region totals']);
    expect(links[0].getAttribute('href')).toBe('/sql/queries/1');
    expect(links[0].getAttribute('target')).toBe('_blank');
    expect(q('.similar')!.getAttribute('aria-label')).toBe('Similar queries in the library');
  });

  it('shows nothing when the list is empty', async () => {
    fillRequired();
    await quiet();
    backend.expectOne(similarUrl()).flush([]);
    h.detectChanges();
    expect(q('.similar')).toBeNull();
  });

  it('a failing endpoint shows no suggestions, no error, and Save still works', async () => {
    fillRequired();
    await quiet();
    backend.expectOne(similarUrl()).flush({ error: 'X' }, { status: 503, statusText: 'Unavailable' });
    h.detectChanges();
    expect(q('.similar')).toBeNull();
    expect(q('.form-error')).toBeNull();

    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
    backend.expectOne(`${api.baseUrl}/queries`).flush({ id: 42, title: 'Weekly orders', warnings: [], deprecated: false });
  });

  it('Save never waits for a pending suggestion request', async () => {
    fillRequired();
    await quiet();
    const pending = backend.expectOne(similarUrl());
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
    backend.expectOne(`${api.baseUrl}/queries`).flush({ id: 42, title: 'Weekly orders', warnings: [], deprecated: false });
    if (!pending.cancelled) { pending.flush([]); }
  });

  it('a blank draft makes no request', async () => {
    type('title', '   ');
    await quiet();
    backend.expectNone(similarUrl());
  });
});
