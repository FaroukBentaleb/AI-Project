import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { QueryCardComponent } from './query-card.component';

export function savedQuery(over: Partial<SavedQuery> = {}): SavedQuery {
  return {
    id: 1, title: 'Weekly orders', description: 'Orders per week', sql_content: 'SELECT 1', tags: ['billing'],
    dialect: 'postgresql', notes: null, author: 'sami', created_at: '2026-10-01T10:00:00Z', updated_at: '2026-10-01T10:00:00Z',
    is_llm_generated: false, original_nl_prompt: null, deprecated: false, deprecated_reason: null, deprecated_at: null,
    replaced_by: null, use_count: 0, last_used_at: null, version: 0, warnings: [], ...over
  };
}

describe('QueryCardComponent', () => {
  let fixture: ComponentFixture<QueryCardComponent>;
  const render = (q: SavedQuery): HTMLElement => {
    fixture = TestBed.createComponent(QueryCardComponent);
    fixture.componentRef.setInput('query', q);
    fixture.detectChanges();
    return fixture.nativeElement;
  };

  let backend: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), SqlApiService, provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });
  afterEach(() => backend.verify());

  const copyBtn = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('sql-copy-button button')!;

  it('has Copy next to the title with an accessible name', () => {
    const el = render(savedQuery());
    expect(el.querySelector('.title-row sql-copy-button')).not.toBeNull();
    expect(copyBtn(el).getAttribute('aria-label')).toBe('Copy SQL of Weekly orders');
  });

  it('copies the full SQL (not the clipped preview), bumps the count and records usage', fakeAsync(() => {
    const write = spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    const sql = Array.from({ length: 9 }, (_, i) => `line${i + 1}`).join('\n');
    const el = render(savedQuery({ id: 4, sql_content: sql, use_count: 14 }));
    copyBtn(el).click();
    flushMicrotasks();
    fixture.detectChanges();
    expect(write).toHaveBeenCalledOnceWith(sql);
    expect(el.querySelector('.badge-usage')!.textContent).toContain('Used 15×');
    expect(copyBtn(el).textContent).toContain('Copied ✓');
    // Usage failure: the bump stays.
    backend.expectOne((r) => r.url.endsWith('/sql/queries/4/usage')).flush(null, { status: 503, statusText: 'down' });
    fixture.detectChanges();
    expect(el.querySelector('.badge-usage')!.textContent).toContain('Used 15×');
    tick(2000);
  }));

  it('shows and selects the full SQL when the clipboard is denied', fakeAsync(() => {
    spyOn(navigator.clipboard, 'writeText').and.rejectWith(new Error('denied'));
    const sql = Array.from({ length: 9 }, (_, i) => `line${i + 1}`).join('\n');
    const el = render(savedQuery({ sql_content: sql, use_count: 2 }));
    document.body.appendChild(el);
    copyBtn(el).click();
    flushMicrotasks();
    fixture.detectChanges();
    tick();
    expect(el.querySelector('pre code')!.textContent).toBe(sql);
    expect(el.querySelector('.show-all')).toBeNull();
    expect(window.getSelection()!.toString()).toBe(sql);
    expect(el.querySelector('.badge-usage')!.textContent).toContain('Used 2×');
    backend.expectNone((r) => r.url.endsWith('/usage'));
    window.getSelection()!.removeAllRanges();
    el.remove();
  }));

  it('shows title link, description, author, tags and inline SQL', () => {
    const el = render(savedQuery());
    expect(el.querySelector('h3 a')!.getAttribute('href')).toBe('/sql/queries/1');
    expect(el.querySelector('h3')!.textContent).toContain('Weekly orders');
    expect(el.querySelector('.desc')!.textContent).toContain('Orders per week');
    expect(el.querySelector('.author')!.textContent).toBe('sami');
    expect(el.querySelector('.tag')!.textContent).toContain('billing');
    expect(el.querySelector('pre code')!.textContent).toBe('SELECT 1');
    expect(el.querySelector('.badge-llm')).toBeNull();
    expect(el.querySelector('.badge-deprecated')).toBeNull();
    expect(el.querySelector('.badge-usage')!.textContent).toContain('Never used');
    expect(el.querySelector('.show-all')).toBeNull();
  });

  it('shows LLM-generated, Deprecated and usage badges with text', () => {
    const lastUsed = new Date(Date.now() - 2 * 86400000).toISOString();
    const el = render(savedQuery({ is_llm_generated: true, deprecated: true, use_count: 4, last_used_at: lastUsed }));
    expect(el.querySelector('.badge-llm')!.textContent).toContain('LLM-generated');
    expect(el.querySelector('.badge-deprecated')!.textContent).toContain('Deprecated');
    expect(el.querySelector('.badge-usage')!.textContent).toContain('Used 4× · last 2 days ago');
  });

  it('clips SQL past 6 lines with Show all to detail', () => {
    const sql = Array.from({ length: 9 }, (_, i) => `line${i + 1}`).join('\n');
    const el = render(savedQuery({ id: 9, sql_content: sql }));
    expect(el.querySelector('pre code')!.textContent).toBe('line1\nline2\nline3\nline4\nline5\nline6');
    const showAll = el.querySelector('.show-all')!;
    expect(showAll.textContent).toContain('Show all (9 lines)');
    expect(showAll.getAttribute('href')).toBe('/sql/queries/9');
  });

  it('shows the Not read-only badge only when read_only is false', () => {
    expect(render(savedQuery({ read_only: false })).querySelector('.badge-not-read-only')!.textContent).toContain('Not read-only');
    expect(render(savedQuery({ read_only: true })).querySelector('.badge-not-read-only')).toBeNull();
    expect(render(savedQuery({ read_only: null })).querySelector('.badge-not-read-only')).toBeNull();
  });

  it('shows the not-read-only notice under the card after copying a not-read-only query', fakeAsync(() => {
    spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    const el = render(savedQuery({ id: 5, read_only: false }));
    expect(el.querySelector('.copy-notice')).toBeNull();
    copyBtn(el).click();
    flushMicrotasks();
    fixture.detectChanges();
    expect(el.querySelector('.copy-notice')!.textContent).toContain('Copied. This query is not read-only — it modifies data.');
    backend.expectOne((r) => r.url.endsWith('/sql/queries/5/usage')).flush(null);
    tick(2000);
  }));

  it('shows no notice after copying a read-only query', fakeAsync(() => {
    spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    const el = render(savedQuery({ id: 6, read_only: true }));
    copyBtn(el).click();
    flushMicrotasks();
    fixture.detectChanges();
    expect(el.querySelector('.copy-notice')).toBeNull();
    backend.expectOne((r) => r.url.endsWith('/sql/queries/6/usage')).flush(null);
    tick(2000);
  }));
});
