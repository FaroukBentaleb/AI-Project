import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { InAppHistory } from '../core/in-app-history.service';
import { GenerateResponse } from '../core/models';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import { SaveQueryComponent, confirmLeaveSaveForm } from '../save/save-query.component';
import { ToastService } from '../shared/toast.service';
import { GenerateComponent } from './generate.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

const PROMPT = 'weekly orders by region';
const SQL = 'SELECT region, count(*) FROM orders GROUP BY region';

/** Story 7.2: Generate -> Save form (prefill 1) -> save / cancel. */
describe('Save generated SQL (Story 7.2)', () => {
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
    TestBed.inject(Router).initialNavigation(); // popstate listener, as bootstrap does (Cancel uses Back)
    h = await RouterTestingHarness.create('/sql/generate');
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
  const response = (over: Partial<GenerateResponse> = {}): GenerateResponse => ({
    sql: SQL,
    dialect: 'postgresql',
    source: 'llm-generated',
    original_prompt: PROMPT,
    examples_used: [],
    warnings: [],
    safety_note: 'note',
    valid: true,
    prompt_version: 'v1',
    ...over
  });

  function type(sel: string, value: string): void {
    const f = q<HTMLInputElement>(sel)!;
    f.value = value;
    f.dispatchEvent(new Event('input'));
    h.detectChanges();
  }

  async function generateThenSave(over: Partial<GenerateResponse> = {}): Promise<void> {
    type('#sql-generate-prompt', PROMPT);
    q<HTMLButtonElement>('.generate-btn')!.click();
    backend.expectOne(`${api.baseUrl}/generate`).flush(response(over));
    await settle();
    q<HTMLButtonElement>('.panel-actions .save-btn')!.click();
    await settle();
  }

  function fillAndSubmit(): void {
    type('#sql-save-title', 'Weekly orders');
    type('#sql-save-description', 'Orders per region');
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    h.detectChanges();
  }

  const saved = (over: object = {}) => ({ id: 42, title: 'Weekly orders', warnings: [], deprecated: false, read_only: true, ...over });

  it('Save sits next to Copy on the SQL tab and opens the short form with prefill 1', async () => {
    await generateThenSave();
    expect(url()).toBe('/sql/queries/new?from=generate');
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe(SQL);
    expect(q<HTMLInputElement>('#sql-save-title')!.value).toBe('');
    expect(q<HTMLTextAreaElement>('#sql-save-description')!.value).toBe('');
    expect(q('.original-prompt')!.textContent).toContain(PROMPT);
    expect(q('.more-toggle')!.getAttribute('aria-expanded')).toBe('false');
    expect(q('#sql-save-notes')).toBeNull();
  });

  it('creates the query with is_llm_generated = true and original_nl_prompt, then toasts and opens it', async () => {
    await generateThenSave();
    fillAndSubmit();
    const req = backend.expectOne(`${api.baseUrl}/queries`);
    expect(req.request.body).toEqual(
      jasmine.objectContaining({
        title: 'Weekly orders',
        description: 'Orders per region',
        sql_content: SQL,
        is_llm_generated: true,
        original_nl_prompt: PROMPT,
        notes: null
      })
    );
    req.flush(saved(), { status: 201, statusText: 'Created' });
    await settle();
    expect(toasts.current()).toEqual(jasmine.objectContaining({ kind: 'success', text: 'Saved · added to team examples' }));
    expect(url()).toBe('/sql/queries/42');
  });

  it('shows "not yet indexed" when the 201 carries the indexing Warning', async () => {
    await generateThenSave();
    fillAndSubmit();
    backend
      .expectOne(`${api.baseUrl}/queries`)
      .flush(saved({ warnings: ['Not yet indexed: team examples unavailable (Qdrant unreachable).'] }), {
        status: 201,
        statusText: 'Created'
      });
    await settle();
    expect(toasts.current()).toEqual(
      jasmine.objectContaining({ kind: 'warning', text: 'Saved · not yet indexed (team examples unavailable)' })
    );
  });

  it('consumes the prefill once: a second open of ?from=generate is blank', async () => {
    await generateThenSave();
    expect(session.takePrefill('generate')).toBeNull();
    await h.navigateByUrl('/sql/library');
    await h.navigateByUrl('/sql/queries/new?from=generate');
    h.detectChanges();
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe('');
    expect(q('.original-prompt')).toBeNull();
  });

  it('Cancel returns to Generate with prompt and result intact', async () => {
    await generateThenSave();
    q<HTMLButtonElement>('.cancel-btn')!.click();
    await settle();
    expect(url()).toBe('/sql/generate');
    expect(q<HTMLTextAreaElement>('#sql-generate-prompt')!.value).toBe(PROMPT);
    expect(q('.generated-sql')!.textContent).toContain(SQL);
  });

  it('maps a 400 on original_nl_prompt (no form field) to the form banner and stays', async () => {
    await generateThenSave();
    fillAndSubmit();
    backend
      .expectOne(`${api.baseUrl}/queries`)
      .flush(
        { error: 'VALIDATION_FAILED', message: 'original_nl_prompt must be at most 1000 characters', field: 'original_nl_prompt', details: null },
        { status: 400, statusText: 'Bad Request' }
      );
    await settle();
    expect(url()).toBe('/sql/queries/new?from=generate');
    expect(q('.form-banner')!.textContent).toContain('original_nl_prompt must be at most 1000 characters');
    expect(toasts.current()).toBeNull();
  });

  it('falls back to the session prompt when the response echoes none', async () => {
    await generateThenSave({ original_prompt: '' });
    fillAndSubmit();
    const req = backend.expectOne(`${api.baseUrl}/queries`);
    expect(req.request.body.original_nl_prompt).toBe(PROMPT);
    req.flush(saved(), { status: 201, statusText: 'Created' });
    await settle();
  });
});
