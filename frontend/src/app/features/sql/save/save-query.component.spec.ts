import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SqlApiService } from '../core/sql-api.service';
import { ToastService } from '../shared/toast.service';
import { InAppHistory } from '../core/in-app-history.service';
import { SaveQueryComponent, confirmLeaveSaveForm } from './save-query.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

describe('SaveQueryComponent', () => {
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
  const url = () => TestBed.inject(Router).url;
  const settle = async () => {
    // Not whenStable(): a navigation held by the discard guard keeps the app unstable on purpose.
    for (let i = 0; i < 3; i++) {
      h.detectChanges();
      await new Promise((r) => setTimeout(r));
    }
    h.detectChanges();
  };

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

  function fillRequired(): void {
    type('title', 'Weekly orders');
    type('description', 'Orders per region');
    type('sql_content', 'SELECT 1');
  }

  const saved = (over: object = {}) => ({ id: 42, title: 'Weekly orders', warnings: [], deprecated: false, ...over });

  it('shows the three required fields with optional ones collapsed', () => {
    expect(q('#sql-save-title')).not.toBeNull();
    expect(q('#sql-save-description')).not.toBeNull();
    expect(q('#sql-save-sql_content')).not.toBeNull();
    expect(q('#sql-save-notes')).toBeNull();
    const toggle = q<HTMLButtonElement>('.more-toggle')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    h.detectChanges();
    expect(q('#sql-save-tags')).not.toBeNull();
    expect(q('#sql-save-notes')).not.toBeNull();
    expect(q<HTMLSelectElement>('#sql-save-dialect')!.value).toBe('postgresql');
  });

  it('blocks submit on missing fields, shows errors and focuses the first invalid field', async () => {
    type('description', 'only this');
    submit();
    await settle();
    backend.expectNone(`${api.baseUrl}/queries`);
    expect(q('#sql-save-title-error')!.textContent).toContain('Title is required.');
    expect(q('#sql-save-sql_content-error')).not.toBeNull();
    expect(q('#sql-save-description-error')).toBeNull();
    expect(q('#sql-save-title')!.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(q('#sql-save-title'));

    // Live per field after the first submit.
    type('title', 'x');
    expect(q('#sql-save-title-error')).toBeNull();
  });

  it('flags over-limit values and disables Save past the SQL limit', async () => {
    fillRequired();
    type('title', 'a'.repeat(201));
    submit();
    await settle();
    backend.expectNone(`${api.baseUrl}/queries`);
    expect(q('#sql-save-title-error')!.textContent).toContain('at most 200');
    expect(q('.counter.over')!.textContent).toContain('201/200');

    type('title', 'ok');
    expect(q('.label-row .counter[class*="over"]')).toBeNull();
    type('sql_content', 'a'.repeat(19001));
    expect(el().textContent).toContain('19001/20000');
    type('sql_content', 'a'.repeat(20001));
    expect(q<HTMLButtonElement>('button[type="submit"]')!.disabled).toBeTrue();
  });

  it('opens More options and focuses an invalid optional field', async () => {
    fillRequired();
    q<HTMLButtonElement>('.more-toggle')!.click();
    h.detectChanges();
    type('notes', 'n'.repeat(2001));
    q<HTMLButtonElement>('.more-toggle')!.click();
    h.detectChanges();
    submit();
    await settle();
    backend.expectNone(`${api.baseUrl}/queries`);
    expect(document.activeElement).toBe(q('#sql-save-notes'));
  });

  it('creates the query, shows the indexed toast and lands on detail', async () => {
    fillRequired();
    submit();
    const req = backend.expectOne(`${api.baseUrl}/queries`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      title: 'Weekly orders',
      description: 'Orders per region',
      sql_content: 'SELECT 1',
      tags: [],
      notes: null,
      dialect: 'postgresql',
      is_llm_generated: false,
      original_nl_prompt: null
    });
    h.detectChanges();
    const btn = q<HTMLButtonElement>('button[type="submit"]')!;
    expect(btn.disabled).toBeTrue();
    expect(btn.textContent).toContain('Saving…');
    req.flush(saved(), { status: 201, statusText: 'Created' });
    await settle();
    expect(url()).toBe('/sql/queries/42');
    expect(toasts.current()).toEqual(jasmine.objectContaining({ kind: 'success', text: 'Saved · added to team examples' }));
  });

  it('shows the not-indexed warning toast when warnings[] carries the indexing Warning', async () => {
    fillRequired();
    submit();
    backend.expectOne(`${api.baseUrl}/queries`).flush(saved({ warnings: ['Not yet indexed: team examples unavailable (Qdrant unreachable).'] }), { status: 201, statusText: 'Created' });
    await settle();
    expect(toasts.current()).toEqual(
      jasmine.objectContaining({ kind: 'warning', text: 'Saved · not yet indexed (team examples unavailable)' })
    );
  });

  it('submits with Ctrl+Enter', async () => {
    fillRequired();
    q('form')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }));
    backend.expectOne(`${api.baseUrl}/queries`).flush(saved());
    await settle();
    expect(url()).toBe('/sql/queries/42');
  });

  it('shows a 400 field error inline from the server envelope and focuses it', async () => {
    fillRequired();
    submit();
    backend
      .expectOne(`${api.baseUrl}/queries`)
      .flush(
        { error: 'VALIDATION_FAILED', message: 'size must be between 0 and 50', field: 'tags', details: null },
        { status: 400, statusText: 'Bad Request' }
      );
    await settle();
    expect(url()).toBe('/sql/queries/new');
    expect(q('.more')).not.toBeNull();
    expect(q('.more .field-error')!.textContent).toContain('size must be between 0 and 50');
    expect(document.activeElement).toBe(q('#sql-save-tags'));
    expect(q('.form-banner')).toBeNull();
    expect(toasts.current()).toBeNull();
  });

  it('shows a form-level banner for a 400 without a form field, or other failures', async () => {
    fillRequired();
    submit();
    backend
      .expectOne(`${api.baseUrl}/queries`)
      .flush(
        { error: 'VALIDATION_FAILED', message: 'X-User-Id is too long', field: 'x_user_id', details: null },
        { status: 400, statusText: 'Bad Request' }
      );
    await settle();
    expect(q('.form-banner')!.textContent).toContain('X-User-Id is too long');

    submit();
    backend.expectOne(`${api.baseUrl}/queries`).error(new ProgressEvent('error'));
    await settle();
    expect(q('.form-banner')!.textContent).toContain('Service unavailable. Try again in a moment.');
    expect(q<HTMLButtonElement>('button[type="submit"]')!.disabled).toBeFalse();
  });

  it('refuses an 11th tag with an inline error', () => {
    q<HTMLButtonElement>('.more-toggle')!.click();
    h.detectChanges();
    const input = q<HTMLInputElement>('#sql-save-tags')!;
    for (let i = 0; i <= 10; i++) {
      input.value = `t${i}`;
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
      h.detectChanges();
    }
    expect(el().querySelectorAll('.tag').length).toBe(10);
    expect(q('.more .field-error')!.textContent).toContain('Up to 10 tags.');
  });

  it('cancels straight back when nothing was typed', async () => {
    q<HTMLButtonElement>('.btn-secondary')!.click();
    await settle();
    expect(url()).toBe('/sql/library');
  });

  it('asks before discarding edits: Keep editing stays, Discard leaves', async () => {
    type('title', 'draft');
    q<HTMLButtonElement>('.form-actions .btn-secondary')!.click();
    await settle();
    expect(url()).toBe('/sql/queries/new');
    const dialog = q('[role="dialog"]')!;
    expect(dialog.textContent).toContain('Discard your changes?');
    expect(document.activeElement!.textContent).toContain('Keep editing');

    (document.activeElement as HTMLButtonElement).click();
    await settle();
    expect(q('[role="dialog"]')).toBeNull();
    expect(url()).toBe('/sql/queries/new');
    expect(q<HTMLInputElement>('#sql-save-title')!.value).toBe('draft');

    q<HTMLButtonElement>('.form-actions .btn-secondary')!.click();
    await settle();
    q<HTMLButtonElement>('[role="dialog"] .btn-primary')!.click();
    await settle();
    expect(url()).toBe('/sql/library');
  });

  it('guards other navigation away from a dirty form too', async () => {
    type('sql_content', 'SELECT 2');
    const nav = TestBed.inject(Router).navigateByUrl('/sql/library');
    await settle();
    expect(q('[role="dialog"]')).not.toBeNull();
    q('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    expect(await nav).toBeFalse();
    expect(url()).toBe('/sql/queries/new');
  });

  it('inserts two spaces on Tab in the SQL field', () => {
    const sql = q<HTMLTextAreaElement>('#sql-save-sql_content')!;
    sql.value = 'SELECT';
    sql.selectionStart = sql.selectionEnd = 6;
    sql.dispatchEvent(new Event('input'));
    const tab = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    sql.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBeTrue();
    expect(sql.value).toBe('SELECT  ');
    sql.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    const leave = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    sql.dispatchEvent(leave);
    expect(leave.defaultPrevented).toBeFalse();
  });
});
