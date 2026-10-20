import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ERROR_MESSAGES } from '../core/error-mapper';
import { InAppHistory } from '../core/in-app-history.service';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import { SaveQueryComponent, confirmLeaveSaveForm } from '../save/save-query.component';
import { ToastService } from '../shared/toast.service';
import { GenerateComponent } from './generate.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

const PROMPT = 'weekly orders by region';
const MANUAL_SQL = 'SELECT region, count(*) FROM orders GROUP BY region';
const NO_HINT = { retry_after_seconds: null, rejected_sql: null, reasons: null, llm_raw_response: null };

/** Story 7.3: every generation error state, the retry countdown and the manual-save path. */
describe('Generate error states (Story 7.3)', () => {
  let backend: HttpTestingController;
  let h: RouterTestingHarness;
  let api: SqlApiService;
  let session: SqlSessionState;
  let toasts: ToastService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/generate', component: GenerateComponent },
          { path: 'sql/queries/new', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm] },
          { path: 'sql/queries/:id', component: StubComponent }
        ]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        SqlApiService,
        SqlSessionState,
        ToastService,
        InAppHistory
      ]
    });
    backend = TestBed.inject(HttpTestingController);
    api = TestBed.inject(SqlApiService);
    session = TestBed.inject(SqlSessionState);
    toasts = TestBed.inject(ToastService);
    TestBed.inject(InAppHistory);
    TestBed.inject(Router).initialNavigation();
    h = await RouterTestingHarness.create('/sql/generate');
  });

  afterEach(() => {
    toasts.dismiss();
    backend.verify();
  });

  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const url = () => TestBed.inject(Router).url;
  const banner = () => q('.generate-error')!;
  const settle = async () => {
    for (let i = 0; i < 3; i++) {
      h.detectChanges();
      await new Promise((r) => setTimeout(r));
    }
    h.detectChanges();
  };

  function type(sel: string, value: string): void {
    const f = q<HTMLInputElement>(sel)!;
    f.value = value;
    f.dispatchEvent(new Event('input'));
    h.detectChanges();
  }
  function generate(): void {
    type('#sql-generate-prompt', PROMPT);
    q<HTMLButtonElement>('.generate-btn')!.click();
    h.detectChanges();
  }
  function failWith(status: number, error: string | null, details: object | null = NO_HINT, headers?: HttpHeaders): void {
    generate();
    const body = error ? { error, message: 'server text', field: null, details } : '<html>Gateway Timeout</html>';
    backend.expectOne(`${api.baseUrl}/generate`).flush(body, { status, statusText: 'Err', headers });
    h.detectChanges();
  }

  const cases = [
    { name: 'LLM_UNAVAILABLE', status: 503, code: 'LLM_UNAVAILABLE', kind: 'banner-warning', retry: true },
    { name: 'LLM_QUOTA_EXCEEDED', status: 503, code: 'LLM_QUOTA_EXCEEDED', kind: 'banner-warning', retry: true },
    { name: 'LLM_NOT_CONFIGURED', status: 503, code: 'LLM_NOT_CONFIGURED', kind: 'banner-danger', retry: false },
    { name: 'gateway timeout (504)', status: 504, code: null, kind: 'banner-warning', retry: true }
  ];
  for (const c of cases) {
    it(`${c.name}: message and "Save a query manually"${c.retry ? ' plus Retry' : ', no Retry'}`, () => {
      failWith(c.status, c.code);
      expect(banner().classList).toContain(c.kind);
      expect(banner().textContent).toContain(ERROR_MESSAGES[c.code ?? 'LLM_UNAVAILABLE']);
      expect(q('.manual-save-btn')!.textContent).toContain('Save a query manually');
      expect(!!q('.retry-btn')).toBe(c.retry);
      if (c.retry) {
        expect(q<HTMLButtonElement>('.retry-btn')!.disabled).toBeFalse();
        expect(q('.retry-btn')!.textContent!.trim()).toBe('Retry');
      }
      expect(q('.rephrase-btn')).toBeNull();
      expect(q('[role="tablist"]')).toBeNull();
    });
  }

  it('network failure: unavailable message with "Save a query manually" and Retry', () => {
    generate();
    backend.expectOne(`${api.baseUrl}/generate`).error(new ProgressEvent('error'));
    h.detectChanges();
    expect(banner().textContent).toContain(ERROR_MESSAGES['LLM_UNAVAILABLE']);
    expect(q('.manual-save-btn')).not.toBeNull();
    expect(q('.retry-btn')).not.toBeNull();
  });

  it('SQL_REJECTED: danger banner, rejected SQL without Copy or Save, each reason, Rephrase and retry', () => {
    failWith(422, 'SQL_REJECTED', {
      rejected_sql: 'DELETE FROM orders',
      reasons: [
        { code: 'NOT_SELECT', reason: 'Only SELECT statements are allowed' },
        { code: 'DATA_MODIFYING', reason: 'DELETE modifies data' }
      ],
      llm_raw_response: 'DELETE FROM orders',
      retry_after_seconds: null,
      safety_note: 'Server safety note text.'
    });
    expect(banner().classList).toContain('banner-danger');
    expect(banner().textContent).toContain(ERROR_MESSAGES['SQL_REJECTED']);
    expect(q('.rejected-sql')!.textContent).toContain('DELETE FROM orders');
    const reasons = Array.from(el().querySelectorAll('.rejected-reasons li')).map((li) => li.textContent);
    expect(reasons).toEqual(['NOT_SELECTOnly SELECT statements are allowed', 'DATA_MODIFYINGDELETE modifies data']);
    expect(q('sql-copy-button')).toBeNull();
    expect(q('.save-btn')).toBeNull();
    expect(q('.manual-save-btn')).toBeNull();
    expect(q('.retry-btn')).toBeNull();
    expect(q('.safety-note')!.textContent).toContain('Server safety note text.');
    q<HTMLButtonElement>('.rephrase-btn')!.click();
    expect(document.activeElement).toBe(q('#sql-generate-prompt'));
  });

  it('SQL_REJECTED without safety_note in details: no Safety Note rendered (the UI holds no copy)', () => {
    failWith(422, 'SQL_REJECTED', { rejected_sql: 'DELETE FROM orders', reasons: [], llm_raw_response: null, retry_after_seconds: null });
    expect(q('.rejected-sql')).not.toBeNull();
    expect(q('.safety-note')).toBeNull();
  });

  for (const code of ['LLM_REFUSED', 'SQL_PARSE_ERROR']) {
    it(`${code}: warning banner suggesting rephrasing, no actions, raw reply not shown`, () => {
      failWith(422, code, { ...NO_HINT, llm_raw_response: 'raw model text' });
      expect(banner().classList).toContain('banner-warning');
      expect(banner().textContent).toContain(ERROR_MESSAGES[code]);
      expect(banner().textContent).toContain('Try rephrasing.');
      expect(el().textContent).not.toContain('raw model text');
      expect(q('.manual-save-btn')).toBeNull();
      expect(q('.retry-btn')).toBeNull();
      expect(q('.rejected')).toBeNull();
      expect(q('.safety-note')).toBeNull();
    });
  }

  describe('Retry countdown', () => {
    beforeEach(() => {
      jasmine.clock().install();
      jasmine.clock().mockDate(new Date(2026, 9, 20, 12, 0, 0));
    });
    afterEach(() => jasmine.clock().uninstall());

    it('counts down from retry_after_seconds, enables at 0, then retries', () => {
      failWith(503, 'LLM_UNAVAILABLE', { ...NO_HINT, retry_after_seconds: 3 });
      const retry = () => q<HTMLButtonElement>('.retry-btn')!;
      expect(retry().textContent!.trim()).toBe('Retry in ~3s');
      expect(retry().disabled).toBeTrue();
      jasmine.clock().tick(1000);
      h.detectChanges();
      expect(retry().textContent!.trim()).toBe('Retry in ~2s');
      jasmine.clock().tick(2000);
      h.detectChanges();
      expect(retry().textContent!.trim()).toBe('Retry');
      expect(retry().disabled).toBeFalse();
      retry().click();
      h.detectChanges();
      const req = backend.expectOne(`${api.baseUrl}/generate`);
      expect(req.request.body.prompt).toBe(PROMPT);
      req.flush({
        sql: 'SELECT 1', dialect: 'postgresql', source: 'llm-generated', original_prompt: PROMPT,
        examples_used: [], warnings: [], safety_note: 'n', valid: true, prompt_version: 'v1'
      });
      h.detectChanges();
      expect(q('.generate-error')).toBeNull();
    });

    it('uses the Retry-After header when the body has no hint (quota)', () => {
      failWith(503, 'LLM_QUOTA_EXCEEDED', NO_HINT, new HttpHeaders({ 'Retry-After': '20' }));
      expect(q('.retry-btn')!.textContent!.trim()).toBe('Retry in ~20s');
      expect(q('.manual-save-btn')).not.toBeNull();
    });
  });

  it('manual save end to end: prompt in Notes, More options open, is_llm_generated false, saved', async () => {
    failWith(503, 'LLM_UNAVAILABLE');
    q<HTMLButtonElement>('.manual-save-btn')!.click();
    await settle();
    expect(url()).toBe('/sql/queries/new?from=manual');
    expect(q('.more-toggle')!.getAttribute('aria-expanded')).toBe('true');
    expect(q<HTMLTextAreaElement>('#sql-save-notes')!.value).toBe(PROMPT);
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe('');
    expect(q('.original-prompt')).toBeNull();

    type('#sql-save-title', 'Weekly orders');
    type('#sql-save-description', 'Orders per region');
    type('#sql-save-sql_content', MANUAL_SQL);
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
    const req = backend.expectOne(`${api.baseUrl}/queries`);
    expect(req.request.body).toEqual(
      jasmine.objectContaining({
        title: 'Weekly orders',
        sql_content: MANUAL_SQL,
        notes: PROMPT,
        is_llm_generated: false,
        original_nl_prompt: null
      })
    );
    req.flush(
      { id: 42, title: 'Weekly orders', warnings: ['Not yet indexed: team examples unavailable.'], deprecated: false, read_only: true },
      { status: 201, statusText: 'Created' }
    );
    await settle();
    expect(toasts.current()).toEqual(
      jasmine.objectContaining({ kind: 'warning', text: 'Saved · not yet indexed (team examples unavailable)' })
    );
    expect(url()).toBe('/sql/queries/42');
  });

  it('manual prefill is one-shot; Cancel returns to Generate with prompt and error intact', async () => {
    failWith(503, 'LLM_NOT_CONFIGURED');
    q<HTMLButtonElement>('.manual-save-btn')!.click();
    await settle();
    expect(session.takePrefill('manual')).toBeNull();
    q<HTMLButtonElement>('.cancel-btn')!.click();
    await settle();
    expect(url()).toBe('/sql/generate');
    expect(q<HTMLTextAreaElement>('#sql-generate-prompt')!.value).toBe(PROMPT);
    expect(banner().textContent).toContain(ERROR_MESSAGES['LLM_NOT_CONFIGURED']);
    await h.navigateByUrl('/sql/queries/new?from=manual');
    h.detectChanges();
    expect(q('#sql-save-notes')).toBeNull();
  });
});
