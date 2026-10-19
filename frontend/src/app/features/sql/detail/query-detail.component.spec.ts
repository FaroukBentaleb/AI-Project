import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { InAppHistory } from '../core/in-app-history.service';
import { SqlApiService } from '../core/sql-api.service';
import { ToastService } from '../shared/toast.service';
import { PICKER_DEBOUNCE_MS } from './replaced-by-picker.component';
import { QueryDetailComponent } from './query-detail.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

describe('QueryDetailComponent', () => {
  let backend: HttpTestingController;
  let toasts: ToastService;
  let h: RouterTestingHarness;
  let api: SqlApiService;

  const server = (over: object = {}) => ({
    id: 7,
    title: 'Weekly orders',
    description: 'Orders per region',
    sql_content: 'SELECT 1\nFROM t',
    tags: ['orders', 'weekly'],
    notes: 'Assumes created_at is UTC.',
    dialect: 'postgresql',
    author: 'Leila',
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-02-01T00:00:00Z',
    is_llm_generated: true,
    original_nl_prompt: 'weekly orders by region',
    deprecated: false,
    deprecated_reason: null,
    deprecated_at: null,
    replaced_by: null,
    use_count: 3,
    last_used_at: null,
    version: 2,
    warnings: [],
    ...over
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/queries/:id', component: QueryDetailComponent },
          { path: 'sql/queries/:id/edit', component: StubComponent }
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
    TestBed.inject(InAppHistory);
    h = await RouterTestingHarness.create('/sql/queries/7');
  });

  afterEach(() => {
    toasts.dismiss();
    backend.verify();
  });

  const doc = () => document;
  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const dq = <T extends HTMLElement>(sel: string) => doc().querySelector<T>(sel);
  const url = () => TestBed.inject(Router).url;
  const wait = (ms = 0) => new Promise((r) => setTimeout(r, ms));
  const settle = async () => {
    for (let i = 0; i < 3; i++) {
      h.detectChanges();
      await wait();
    }
    h.detectChanges();
  };
  const one = (id = 7) => `${api.baseUrl}/queries/${id}`;
  function loaded(over: object = {}): void {
    backend.expectOne(one()).flush(server(over));
    h.detectChanges();
  }
  function errorOn(req: TestRequest, error: string, status: number, field: string | null = null, message = 'msg'): void {
    req.flush({ error, message, field, details: null }, { status, statusText: 'x' });
    h.detectChanges();
  }
  function setReason(text: string): void {
    const r = dq<HTMLTextAreaElement>('#sql-deprecate-reason')!;
    r.value = text;
    r.dispatchEvent(new Event('input'));
    h.detectChanges();
  }
  const confirmBtn = () => dq<HTMLButtonElement>('.deprecate-modal .confirm')!;

  it('shows the skeleton and a polite Loading while fetching', () => {
    h.detectChanges();
    expect(q('.skeleton-main')).not.toBeNull();
    expect(q('.layout')!.getAttribute('aria-hidden')).toBe('true');
    expect(q('[role="status"]')!.textContent).toContain('Loading');
    loaded();
  });

  it('shows every FR31 attribute, including the original prompt', () => {
    loaded();
    expect(q('.page-title')!.textContent).toContain('Weekly orders');
    expect(q('.breadcrumb')!.textContent).toContain('Library');
    expect(q('.crumb-title')!.getAttribute('title')).toBe('Weekly orders');
    expect(q('.badges')!.textContent).toContain('LLM-generated');
    expect(q('.description')!.textContent).toContain('Orders per region');
    expect(q('.author')!.textContent).toContain('Leila');
    expect(q('.dates span')!.getAttribute('title')).toBe('2026-01-01T00:00:00Z');
    expect(q('.aside')!.textContent).toContain('PostgreSQL');
    expect(el().querySelectorAll('.aside .tag').length).toBe(2);
    expect(q('.origin')!.textContent).toContain("Original prompt: 'weekly orders by region'");
    expect(q('.usage')!.textContent).toContain('Used 3×');
    expect(q('.notes')!.textContent).toContain('Assumes created_at is UTC.');
    expect(q('.team')!.textContent).toContain('Used as a team example');
    expect(q('.detail-code')!.textContent).toContain('FROM t');
    expect(q('.gutter')!.textContent).toBe('1\n2');
    expect(q('.gutter')!.getAttribute('aria-hidden')).toBe('true');
    expect(q('.action-row')!.textContent).toContain('Copy SQL');
    expect(q('.edit-btn')!.getAttribute('href')).toBe('/sql/queries/7/edit');
    expect(q('.deprecate-btn')!.textContent).toContain('Deprecate');
    expect(q('.deprecated-banner')).toBeNull();
  });

  it('says "Written by hand" for a manual query', () => {
    loaded({ is_llm_generated: false, original_nl_prompt: null });
    expect(q('.origin')!.textContent).toContain('Written by hand');
  });

  it('shows the NOT_FOUND message with Back to Library on 404', () => {
    errorOn(backend.expectOne(one()), 'NOT_FOUND', 404);
    expect(q('.not-found')!.textContent).toContain("This query doesn't exist or was deleted.");
    expect(q('.not-found a')!.getAttribute('href')).toBe('/sql/library');
  });

  it('shows a danger banner with Retry on other failures', () => {
    backend.expectOne(one()).error(new ProgressEvent('error'));
    h.detectChanges();
    expect(q('.banner-danger')!.textContent).toContain('Service unavailable');
    q<HTMLButtonElement>('.retry')!.click();
    loaded();
    expect(q('.page-title')).not.toBeNull();
  });

  it('shows the neutral deprecated banner with the replacement link', () => {
    loaded({ deprecated: true, deprecated_reason: 'Uses old status codes', replaced_by: 9 });
    backend.expectOne(one(9)).flush(server({ id: 9, title: 'Weekly orders v2' }));
    h.detectChanges();
    const banner = q('.deprecated-banner')!;
    expect(banner.classList).toContain('banner-neutral');
    expect(banner.textContent).toContain("replaced by 'Weekly orders v2'");
    expect(banner.textContent).toContain('reason: Uses old status codes');
    expect(banner.querySelector('a')!.getAttribute('href')).toBe('/sql/queries/9');
    expect(q('.badges')!.textContent).toContain('Deprecated');
    expect(q('.deprecate-btn')!.textContent).toContain('Undeprecate');
    expect(q('.team')!.textContent).toContain('Not used as a team example');
  });

  it('omits the link when the replacement was deleted', () => {
    loaded({ deprecated: true, deprecated_reason: 'old', replaced_by: 9 });
    errorOn(backend.expectOne(one(9)), 'NOT_FOUND', 404);
    expect(q('.deprecated-banner a')).toBeNull();
    expect(q('.deprecated-banner')!.textContent).toContain('reason: old');
  });

  it('deprecates with a reason and a replacement picked from the listbox', async () => {
    loaded();
    q<HTMLButtonElement>('.deprecate-btn')!.click();
    await settle();
    expect(dq('[role="dialog"]')).not.toBeNull();
    expect(confirmBtn().disabled).toBeTrue();
    setReason('Uses old status codes');
    expect(confirmBtn().disabled).toBeFalse();

    const input = dq<HTMLInputElement>('#sql-deprecate-replaced-by')!;
    input.value = 'v2';
    input.dispatchEvent(new Event('input'));
    h.detectChanges();
    expect(dq('[role="listbox"]')!.textContent).toContain('Searching…');
    await wait(PICKER_DEBOUNCE_MS + 20);
    const search = backend.expectOne((r) => r.url === `${api.baseUrl}/queries`);
    expect(search.request.params.get('q')).toBe('v2');
    expect(search.request.params.get('deprecated')).toBe('false');
    search.flush({ content: [server(), server({ id: 9, title: 'Weekly orders v2' })], page: 0, size: 9, total_elements: 2, total_pages: 1 });
    h.detectChanges();
    const options = Array.from(doc().querySelectorAll('[role="option"]'));
    expect(options.map((o) => o.textContent!.trim())).toEqual(['Weekly orders v2']);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    h.detectChanges();
    expect(dq('.picked')!.textContent).toContain('Weekly orders v2');

    confirmBtn().click();
    h.detectChanges();
    const put = backend.expectOne(`${one()}/deprecation`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ reason: 'Uses old status codes', replaced_by: 9 });
    expect(confirmBtn().textContent).toContain('Deprecating…');
    put.flush(server({ deprecated: true, deprecated_reason: 'Uses old status codes', replaced_by: 9 }));
    await settle();
    expect(dq('[role="dialog"]')).toBeNull();
    expect(toasts.current()!.text).toBe('Deprecated · removed from team examples');
    expect(q('.deprecated-banner a')!.textContent).toContain('Weekly orders v2');
    expect(q('.badges')!.textContent).toContain('Deprecated');
    expect(document.activeElement).toBe(q('.deprecate-btn'));
  });

  it('deprecates without a replacement', async () => {
    loaded();
    q<HTMLButtonElement>('.deprecate-btn')!.click();
    await settle();
    setReason('obsolete');
    confirmBtn().click();
    const put = backend.expectOne(`${one()}/deprecation`);
    expect(put.request.body).toEqual({ reason: 'obsolete', replaced_by: null });
    put.flush(server({ deprecated: true, deprecated_reason: 'obsolete' }));
    await settle();
    expect(q('.deprecated-banner')!.textContent).toContain('reason: obsolete');
    expect(q('.deprecated-banner a')).toBeNull();
  });

  it('keeps the modal open with 400s inline (replaced_by, reason) and 404 at modal level', async () => {
    loaded();
    q<HTMLButtonElement>('.deprecate-btn')!.click();
    await settle();
    setReason('obsolete');
    confirmBtn().click();
    errorOn(backend.expectOne(`${one()}/deprecation`), 'VALIDATION_FAILED', 400, 'replaced_by', 'replaced_by: query not found');
    expect(dq('[role="dialog"]')).not.toBeNull();
    expect(dq('.deprecate-modal')!.textContent).toContain('replaced_by: query not found');

    confirmBtn().click();
    errorOn(backend.expectOne(`${one()}/deprecation`), 'VALIDATION_FAILED', 400, 'reason', 'reason: must not be blank');
    expect(dq('#sql-deprecate-reason-help')!.textContent).toContain('reason: must not be blank');

    confirmBtn().click();
    errorOn(backend.expectOne(`${one()}/deprecation`), 'NOT_FOUND', 404);
    expect(dq('.modal-error')!.textContent).toContain("doesn't exist or was deleted");
    expect(dq('[role="dialog"]')).not.toBeNull();
  });

  it('limits the reason to 500 characters and closes on Esc without a request', async () => {
    loaded();
    q<HTMLButtonElement>('.deprecate-btn')!.click();
    await settle();
    setReason('a'.repeat(501));
    expect(confirmBtn().disabled).toBeTrue();
    dq('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    expect(dq('[role="dialog"]')).toBeNull();
  });

  it('undeprecates with a disabled "Undeprecating…" action and the post-mutation toast', async () => {
    loaded({ deprecated: true, deprecated_reason: 'old' });
    q<HTMLButtonElement>('.deprecate-btn')!.click();
    h.detectChanges();
    expect(q<HTMLButtonElement>('.deprecate-btn')!.disabled).toBeTrue();
    expect(q('.deprecate-btn')!.textContent).toContain('Undeprecating…');
    const del = backend.expectOne(`${one()}/deprecation`);
    expect(del.request.method).toBe('DELETE');
    del.flush(server());
    await settle();
    expect(toasts.current()!.text).toBe('Undeprecated · added to team examples');
    expect(q('.deprecated-banner')).toBeNull();
    expect(q('.deprecate-btn')!.textContent).toContain('Deprecate');
  });

  it('confirms delete: focus on Cancel, cancelling does nothing', async () => {
    loaded();
    q<HTMLButtonElement>('.delete-btn')!.click();
    await settle();
    const dialog = dq('[role="dialog"]')!;
    expect(dialog.textContent).toContain("Delete 'Weekly orders'?");
    expect(dialog.textContent).toContain('It will be removed from the library and team examples.');
    expect(document.activeElement!.textContent).toContain('Cancel');
    (document.activeElement as HTMLButtonElement).click();
    await settle();
    expect(dq('[role="dialog"]')).toBeNull();
    backend.expectNone(one());
    expect(url()).toBe('/sql/queries/7');
  });

  it('deletes on confirm and returns to the Library with a toast', async () => {
    loaded();
    q<HTMLButtonElement>('.delete-btn')!.click();
    await settle();
    dq<HTMLButtonElement>('.btn-danger')!.click();
    h.detectChanges();
    expect(dq('.btn-danger')!.textContent).toContain('Deleting…');
    const del = backend.expectOne(one());
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(url()).toBe('/sql/library');
    expect(toasts.current()!.text).toBe("Deleted 'Weekly orders'");
  });

  it('treats a 404 on delete as already deleted', async () => {
    loaded();
    q<HTMLButtonElement>('.delete-btn')!.click();
    await settle();
    dq<HTMLButtonElement>('.btn-danger')!.click();
    errorOn(backend.expectOne(one()), 'NOT_FOUND', 404);
    await settle();
    expect(url()).toBe('/sql/library');
    expect(toasts.current()!.text).toBe('Already deleted');
  });

  it('shows other delete failures inside the modal', async () => {
    loaded();
    q<HTMLButtonElement>('.delete-btn')!.click();
    await settle();
    dq<HTMLButtonElement>('.btn-danger')!.click();
    backend.expectOne(one()).error(new ProgressEvent('error'));
    h.detectChanges();
    expect(dq('[role="dialog"] .modal-error')!.textContent).toContain('Service unavailable');
    expect(url()).toBe('/sql/queries/7');
  });
});
