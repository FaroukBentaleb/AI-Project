import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { InAppHistory } from '../core/in-app-history.service';
import { SqlApiService } from '../core/sql-api.service';
import { ToastService } from '../shared/toast.service';
import { SaveQueryComponent, confirmLeaveSaveForm } from './save-query.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

describe('SaveQueryComponent (edit mode)', () => {
  let backend: HttpTestingController;
  let toasts: ToastService;
  let h: RouterTestingHarness;
  let api: SqlApiService;

  const server = (over: object = {}) => ({
    id: 7,
    title: 'Weekly orders',
    description: 'Orders per region',
    sql_content: 'SELECT 1',
    tags: ['orders'],
    notes: null,
    dialect: 'postgresql',
    is_llm_generated: false,
    original_nl_prompt: null,
    deprecated: false,
    version: 3,
    warnings: [],
    ...over
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/queries/:id', component: StubComponent },
          { path: 'sql/queries/:id/edit', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm], data: { mode: 'edit' } }
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
    h = await RouterTestingHarness.create('/sql/queries/7/edit');
  });

  afterEach(() => {
    toasts.dismiss();
    backend.verify();
  });

  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const url = () => TestBed.inject(Router).url;
  const settle = async () => {
    for (let i = 0; i < 3; i++) {
      h.detectChanges();
      await new Promise((r) => setTimeout(r));
    }
    h.detectChanges();
  };
  const one = () => `${api.baseUrl}/queries/7`;
  function loaded(over: object = {}): void {
    backend.expectOne(one()).flush(server(over));
    h.detectChanges();
  }
  function type(id: string, value: string): void {
    const field = q<HTMLInputElement>(`#sql-save-${id}`)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    h.detectChanges();
  }
  function submit(): void {
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
  }
  function fail(error: string, status: number, field: string | null = null, message = 'msg'): void {
    backend.expectOne(one()).flush({ error, message, field, details: null }, { status, statusText: 'x' });
    h.detectChanges();
  }

  it('shows a skeleton while loading, then the prefilled form with notice, breadcrumb and summary', () => {
    h.detectChanges();
    expect(q('.skeleton-form')).not.toBeNull();
    expect(q('[role="status"]')!.textContent).toContain('Loading');
    loaded({ notes: 'utc' });
    expect(q('.page-title')!.textContent).toContain('Edit query');
    expect(q<HTMLInputElement>('#sql-save-title')!.value).toBe('Weekly orders');
    expect(q('.edit-notice')!.textContent).toContain('re-checks its read-only status');
    expect(q('.breadcrumb')!.textContent).toContain('Library');
    expect(q('.crumb-title')!.textContent).toContain('Weekly orders');
    expect(q('.more-summary')!.textContent).toContain('1 tag · notes · PostgreSQL');
    expect(q('button[type="submit"]')!.textContent).toContain('Save changes');
  });

  it('shows the detail 404 treatment when the query is gone', () => {
    fail('NOT_FOUND', 404);
    expect(q('.not-found')!.textContent).toContain("This query doesn't exist or was deleted.");
    expect(q('.not-found a')!.textContent).toContain('Back to Library');
  });

  it('patches only the changed fields with version, toasts and lands on detail', async () => {
    loaded();
    type('title', 'Weekly orders v2');
    submit();
    const req = backend.expectOne(one());
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ version: 3, title: 'Weekly orders v2' });
    h.detectChanges();
    expect(q('button[type="submit"]')!.textContent).toContain('Saving…');
    req.flush(server({ title: 'Weekly orders v2', version: 4 }));
    await settle();
    expect(url()).toBe('/sql/queries/7');
    expect(toasts.current()!.text).toBe('Saved · added to team examples');
  });

  it('maps a 400 field error inline and opens More options for it', async () => {
    loaded();
    type('description', 'New');
    submit();
    fail('VALIDATION_FAILED', 400, 'tags', 'tags: must not be blank');
    await settle();
    expect(q('.more .field-error')!.textContent).toContain('tags: must not be blank');
    expect(url()).toBe('/sql/queries/7/edit');
  });

  it('on 409 shows the conflict banner; Reload merges the latest version under my changes', async () => {
    loaded();
    type('sql_content', 'SELECT 2');
    type('description', 'Mine');
    submit();
    fail('CONFLICT', 409, 'version');
    const banner = q('.conflict-banner')!;
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.textContent).toContain('Someone else saved changes to this query');

    q<HTMLButtonElement>('.reload-btn')!.click();
    h.detectChanges();
    const get = backend.expectOne(one());
    expect(get.request.method).toBe('GET');
    get.flush(server({ version: 5, tags: ['orders', 'region'], description: 'Theirs', title: 'Renamed' }));
    h.detectChanges();

    expect(q('.conflict-banner')).toBeNull();
    expect(q<HTMLInputElement>('#sql-save-title')!.value).toBe('Renamed');
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe('SELECT 2');
    expect(q<HTMLTextAreaElement>('#sql-save-description')!.value).toBe('Mine');
    const markers = Array.from(el().querySelectorAll('.marker')).map((m) => m.textContent!.trim());
    expect(markers).toEqual(['Your change — also changed by someone else', 'Your change']);
    expect(q('.marker-server')!.textContent).toContain('Theirs');
    expect(q('.more-summary')!.textContent).toContain('2 tags');

    submit();
    const again = backend.expectOne(one());
    expect(again.request.body).toEqual({ version: 5, description: 'Mine', sql_content: 'SELECT 2' });
    again.flush(server({ version: 6 }));
    await settle();
    expect(url()).toBe('/sql/queries/7');
  });

  it('shows the 404 treatment with my SQL when the query was deleted before saving', async () => {
    loaded();
    type('sql_content', 'SELECT 42');
    submit();
    fail('NOT_FOUND', 404);
    expect(q('.not-found')).not.toBeNull();
    expect(q('.lost-sql')!.textContent).toContain('SELECT 42');
    TestBed.inject(Router).navigateByUrl('/sql/library');
    await settle();
    expect(url()).toBe('/sql/library');
  });

  it('cancels to the detail page when the edit form was the entry page', async () => {
    loaded();
    q<HTMLButtonElement>('.cancel-btn')!.click();
    await settle();
    expect(url()).toBe('/sql/queries/7');
  });
});
