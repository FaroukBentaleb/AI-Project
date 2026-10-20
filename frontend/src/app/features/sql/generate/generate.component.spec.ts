import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ERROR_MESSAGES, GENERIC_ERROR_MESSAGE } from '../core/error-mapper';
import { GenerateResponse } from '../core/models';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import {
  GENERATING_TEXT,
  GenerateComponent,
  NO_EXAMPLES_TEXT,
  PERSONAL_DATA_NOTICE
} from './generate.component';
import { generateError } from './generate-error';
import { HttpErrorResponse } from '@angular/common/http';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

const SAFETY = 'This is a structural check, not a guarantee. Run SQL as a read-only database user.';
const NO_SIMILAR = 'No similar queries found — verify table names in the generated SQL.';
const PROMPT = 'weekly orders by region';
const SQL = 'SELECT region, count(*) FROM orders GROUP BY region';

describe('GenerateComponent', () => {
  let backend: HttpTestingController;
  let h: RouterTestingHarness;
  let api: SqlApiService;
  let session: SqlSessionState;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/generate', component: GenerateComponent },
          { path: 'sql/queries/:id', component: StubComponent }
        ]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        SqlApiService,
        SqlSessionState
      ]
    });
    backend = TestBed.inject(HttpTestingController);
    api = TestBed.inject(SqlApiService);
    session = TestBed.inject(SqlSessionState);
    h = await RouterTestingHarness.create('/sql/generate');
  });

  afterEach(() => backend.verify());

  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el().querySelectorAll<HTMLElement>(sel));
  const area = () => q<HTMLTextAreaElement>('#sql-generate-prompt')!;
  const button = () => q<HTMLButtonElement>('.generate-btn')!;
  const tab = (t: string) => q<HTMLButtonElement>(`#gen-tab-${t}`)!;
  const wait = () => new Promise((r) => setTimeout(r));
  const pending = (): TestRequest => backend.expectOne(`${api.baseUrl}/generate`);

  function type(text: string): void {
    area().value = text;
    area().dispatchEvent(new Event('input'));
    h.detectChanges();
  }
  function ctrlEnter(): void {
    area().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, cancelable: true }));
    h.detectChanges();
  }
  function response(over: Partial<GenerateResponse> = {}): GenerateResponse {
    return {
      sql: SQL,
      dialect: 'postgresql',
      source: 'llm-generated',
      original_prompt: PROMPT,
      examples_used: [
        { id: 7, title: 'Weekly orders by region' },
        { id: 9, title: 'Orders per day' }
      ],
      warnings: [],
      safety_note: SAFETY,
      valid: true,
      prompt_version: 'v1',
      ...over
    };
  }
  function fail(req: TestRequest, status: number, body: string | object, headers?: HttpHeaders): void {
    req.flush(body, { status, statusText: 'Err', headers });
    h.detectChanges();
  }
  async function generate(over: Partial<GenerateResponse> = {}): Promise<void> {
    type(PROMPT);
    ctrlEnter();
    pending().flush(response(over));
    h.detectChanges();
    await wait();
    h.detectChanges();
  }

  it('Idle: command bar, personal-data notice, 0/1000 counter and a disabled Generate', () => {
    expect(q('h1')!.textContent).toContain('Generate');
    expect(q('.notice')!.textContent).toContain(PERSONAL_DATA_NOTICE);
    expect(q('.counter')!.textContent).toContain('0/1000');
    expect(area().rows).toBe(1);
    expect(button().disabled).toBeTrue();
    expect(q('.hint')!.textContent).toContain('Ctrl/⌘+Enter');
    expect(q('.result')).toBeNull();
    expect(q('.safety-note')).toBeNull();
    type('   ');
    expect(button().disabled).toBeTrue();
  });

  it('Blank or over 1000 chars: disabled, counter over-limit, nothing sent', () => {
    type('x'.repeat(1000));
    expect(button().disabled).toBeFalse();
    expect(q('.counter')!.classList).not.toContain('over');
    type('x'.repeat(1001));
    expect(q('.counter')!.textContent).toContain('1001/1000');
    expect(q('.counter')!.classList).toContain('over');
    expect(area().getAttribute('aria-invalid')).toBe('true');
    expect(button().disabled).toBeTrue();
    ctrlEnter();
    backend.expectNone(`${api.baseUrl}/generate`);
  });

  it('grows the prompt with its content', () => {
    type('a\nb\nc');
    expect(area().rows).toBe(3);
    type('x\n'.repeat(20));
    expect(area().rows).toBe(8);
  });

  it('Generating: posts the prompt; button disabled, prompt read-only, indicator shown', () => {
    type(PROMPT);
    button().click();
    h.detectChanges();
    const req = pending();
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ prompt: PROMPT });
    expect(button().disabled).toBeTrue();
    expect(button().textContent).toContain('Generating…');
    expect(area().readOnly).toBeTrue();
    expect(q('.generating')!.textContent).toContain(GENERATING_TEXT);
    expect(q('.generating')!.getAttribute('aria-live')).toBe('polite');
    expect(q('.notice')!.textContent).toContain(PERSONAL_DATA_NOTICE);
    ctrlEnter();
    backend.expectNone(`${api.baseUrl}/generate`); // no double submit
    req.flush(response());
    h.detectChanges();
    expect(button().disabled).toBeFalse();
    expect(area().readOnly).toBeFalse();
    expect(q('.generating')!.textContent!.trim()).toBe('');
  });

  it('Success: SQL tab with SQL and Copy, tab counts, Safety Note outside the tabs, focus on the result heading', async () => {
    await generate();
    expect(tab('sql').getAttribute('aria-selected')).toBe('true');
    expect(tab('examples').textContent).toContain('Examples (2)');
    expect(tab('warnings').textContent).toContain('Warnings (0)');
    expect(tab('warnings').classList).not.toContain('has-warnings');
    expect(tab('examples').getAttribute('aria-label')).toBe('Examples, 2 items');
    expect(q('.generated-sql')!.textContent).toContain(SQL);
    expect(q('#gen-panel-sql sql-copy-button')).not.toBeNull();
    const note = q('.safety-note')!;
    expect(note.textContent).toContain(SAFETY);
    expect(note.closest('[role="tabpanel"]')).toBeNull();
    expect(document.activeElement).toBe(q('.result-title'));
    expect(q('.generate-error')).toBeNull();
  });

  it('Copy records no usage (the draft is not a saved query)', async () => {
    await generate();
    spyOn(navigator.clipboard, 'writeText').and.returnValue(Promise.resolve());
    q<HTMLButtonElement>('.copy-btn')!.click();
    await wait();
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(SQL);
    backend.expectNone(() => true);
  });

  it('Examples tab: title-only links to each query detail', async () => {
    await generate();
    tab('examples').click();
    h.detectChanges();
    const links = qa('.example-link');
    expect(links.map((a) => a.textContent!.trim())).toEqual(['Weekly orders by region', 'Orders per day']);
    expect(links[0].getAttribute('href')).toBe('/sql/queries/7');
    expect(q('.generated-sql')).toBeNull();
    expect(q('.safety-note')).not.toBeNull();
    links[0].click();
    await h.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/sql/queries/7');
  });

  it('No examples: Examples (0) with the empty text; the no-similar Warning is warning-styled and apart from the SQL', async () => {
    await generate({ examples_used: [], warnings: [NO_SIMILAR] });
    expect(tab('examples').textContent).toContain('Examples (0)');
    expect(tab('warnings').textContent).toContain('Warnings (1)');
    expect(tab('warnings').classList).toContain('has-warnings');
    expect(tab('warnings').getAttribute('aria-label')).toBe('Warnings, 1 item');
    tab('examples').click();
    h.detectChanges();
    expect(q('.no-examples')!.textContent).toContain(NO_EXAMPLES_TEXT);
    tab('warnings').click();
    h.detectChanges();
    const row = q('.warning-row')!;
    expect(row.textContent).toContain(NO_SIMILAR);
    expect(row.closest('pre')).toBeNull();
    expect(q('.generated-sql')).toBeNull();
    expect(q('.safety-note')!.textContent).toContain(SAFETY);
  });

  it('Tabs: arrows, Home and End move and select; a new result resets to SQL', async () => {
    await generate({ warnings: ['RAG down'] });
    const key = (k: string) => {
      q('.result-tab.active')!.dispatchEvent(new KeyboardEvent('keydown', { key: k, cancelable: true }));
      h.detectChanges();
    };
    key('ArrowRight');
    expect(tab('examples').getAttribute('aria-selected')).toBe('true');
    expect(tab('examples').tabIndex).toBe(0);
    expect(tab('sql').tabIndex).toBe(-1);
    key('End');
    expect(tab('warnings').getAttribute('aria-selected')).toBe('true');
    key('ArrowRight');
    expect(tab('sql').getAttribute('aria-selected')).toBe('true');
    key('ArrowLeft');
    expect(tab('warnings').getAttribute('aria-selected')).toBe('true');
    key('Home');
    expect(tab('sql').getAttribute('aria-selected')).toBe('true');
    key('End');
    ctrlEnter();
    pending().flush(response());
    h.detectChanges();
    expect(tab('sql').getAttribute('aria-selected')).toBe('true');
  });

  it('keeps the previous result dimmed while a new run is in flight', async () => {
    await generate();
    type('another prompt');
    ctrlEnter();
    const wrap = q('.result-wrap')!;
    expect(wrap.classList).toContain('dimmed');
    expect(q('.generated-sql')!.textContent).toContain(SQL);
    pending().flush(response({ sql: 'SELECT 2' }));
    h.detectChanges();
    expect(q('.result-wrap')!.classList).not.toContain('dimmed');
    expect(q('.generated-sql')!.textContent).toContain('SELECT 2');
  });

  it('Session state: prompt and result survive navigating away and back', async () => {
    await generate();
    await h.navigateByUrl('/sql/library');
    await h.navigateByUrl('/sql/generate');
    h.detectChanges();
    expect(area().value).toBe(PROMPT);
    expect(q('.generated-sql')!.textContent).toContain(SQL);
    expect(session.generateResult()?.sql).toBe(SQL);
  });

  it('Library handoff: history.state.prompt prefills the prompt without submitting', async () => {
    await h.navigateByUrl('/sql/library');
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/sql/generate', { state: { prompt: 'orders by region' } });
    h.detectChanges();
    expect(area().value).toBe('orders by region');
    expect(button().disabled).toBeFalse();
    backend.expectNone(`${api.baseUrl}/generate`);
  });

  it('400: server message inline under the prompt, no banner; typing clears it', () => {
    type(PROMPT);
    ctrlEnter();
    fail(pending(), 400, { error: 'VALIDATION_FAILED', message: 'prompt: size must be between 0 and 1000', field: 'prompt', details: null });
    expect(q('#sql-generate-error')!.textContent).toContain('prompt: size must be between 0 and 1000');
    expect(area().getAttribute('aria-invalid')).toBe('true');
    expect(q('.generate-error')).toBeNull();
    type(PROMPT + '!');
    expect(q('#sql-generate-error')).toBeNull();
  });

  it('Failure: assertive banner with the mapped message, no tabs, previous result cleared', async () => {
    await generate();
    ctrlEnter();
    fail(pending(), 422, { error: 'LLM_REFUSED', message: 'x', field: null, details: null });
    const banner = q('.generate-error')!;
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.classList).toContain('banner-warning');
    expect(banner.textContent).toContain(ERROR_MESSAGES['LLM_REFUSED']);
    expect(q('[role="tablist"]')).toBeNull();
    expect(q('.safety-note')).toBeNull();
    expect(button().disabled).toBeFalse();
  });

  it('LLM_NOT_CONFIGURED shows a danger banner', () => {
    type(PROMPT);
    ctrlEnter();
    fail(pending(), 503, { error: 'LLM_NOT_CONFIGURED', message: 'x', field: null, details: null });
    expect(q('.generate-error')!.classList).toContain('banner-danger');
    expect(q('.generate-error')!.textContent).toContain(ERROR_MESSAGES['LLM_NOT_CONFIGURED']);
  });

  it('Gateway timeout (504 without envelope) reads as temporarily unavailable', () => {
    type(PROMPT);
    ctrlEnter();
    fail(pending(), 504, '<html>Gateway Timeout</html>');
    expect(q('.generate-error')!.textContent).toContain(ERROR_MESSAGES['LLM_UNAVAILABLE']);
  });

  it('Network failure reads as temporarily unavailable (Story 7.3)', () => {
    type(PROMPT);
    ctrlEnter();
    pending().error(new ProgressEvent('error'));
    h.detectChanges();
    expect(q('.generate-error')!.textContent).toContain(ERROR_MESSAGES['LLM_UNAVAILABLE']);
    expect(q('.generate-error')!.textContent).not.toContain(GENERIC_ERROR_MESSAGE);
  });
});

describe('generateError', () => {
  const err = (status: number, body: unknown, headers?: HttpHeaders) =>
    new HttpErrorResponse({ status, error: body, headers });

  it('reads retry_after_seconds from details, else the Retry-After header', () => {
    const fromBody = generateError(
      err(503, { error: 'LLM_UNAVAILABLE', message: 'm', field: null, details: { retry_after_seconds: 12 } })
    );
    expect(fromBody).toEqual(jasmine.objectContaining({ kind: 'warning', code: 'LLM_UNAVAILABLE', retryAfterSeconds: 12 }));
    const fromHeader = generateError(
      err(503, { error: 'LLM_QUOTA_EXCEEDED', message: 'm', field: null, details: null }, new HttpHeaders({ 'Retry-After': '30' }))
    );
    expect(fromHeader.retryAfterSeconds).toBe(30);
    expect(generateError(err(503, { error: 'LLM_UNAVAILABLE', message: 'm', field: null, details: null })).retryAfterSeconds).toBeNull();
  });

  it('classifies kinds and keeps details for Story 7.3', () => {
    const rejected = generateError(
      err(422, { error: 'SQL_REJECTED', message: 'm', field: null, details: { sql: 'DELETE FROM x', rejections: [] } })
    );
    expect(rejected.kind).toBe('danger');
    expect(rejected.details).toEqual({ sql: 'DELETE FROM x', rejections: [] });
    expect(generateError(err(422, { error: 'SQL_PARSE_ERROR', message: 'm', field: null, details: null })).kind).toBe('warning');
    expect(generateError(err(400, { error: 'VALIDATION_FAILED', message: 'bad', field: 'prompt', details: null })).kind).toBe('inline');
    expect(generateError(err(502, null)).code).toBe('LLM_UNAVAILABLE');
    expect(generateError(err(0, null))).toEqual(jasmine.objectContaining({ code: 'LLM_UNAVAILABLE', canSaveManually: true }));
    expect(generateError(err(500, null))).toEqual(jasmine.objectContaining({ code: null, message: GENERIC_ERROR_MESSAGE, canSaveManually: false }));
  });
});
