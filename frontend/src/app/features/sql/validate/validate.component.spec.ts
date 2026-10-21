import { Component } from '@angular/core';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { InAppHistory } from '../core/in-app-history.service';
import { ValidateResponse } from '../core/models';
import { SqlSessionState } from '../core/session-state.service';
import { SqlApiService } from '../core/sql-api.service';
import { SaveQueryComponent, confirmLeaveSaveForm } from '../save/save-query.component';
import { ToastService } from '../shared/toast.service';
import { VALIDATE_EXPLAINER, ValidateComponent } from './validate.component';

@Component({ standalone: true, template: '<p class="stub">stub</p>' })
class StubComponent {}

const SAFETY = 'This is a structural check, not a guarantee. Run SQL as a read-only database user.';
const CTE_ATTACK = 'WITH x AS (INSERT INTO audit_log VALUES (1) RETURNING *) SELECT 1';
const WOW = 'SELECT 1 AS w, count(*) FROM orders UNION ALL SELECT 2, count(*) FROM orders';

describe('ValidateComponent', () => {
  let backend: HttpTestingController;
  let h: RouterTestingHarness;
  let api: SqlApiService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'sql/library', component: StubComponent },
          { path: 'sql/validate', component: ValidateComponent },
          { path: 'sql/queries/new', component: SaveQueryComponent, canDeactivate: [confirmLeaveSaveForm] }
        ]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        SqlApiService,
        ToastService,
        InAppHistory,
        SqlSessionState
      ]
    });
    backend = TestBed.inject(HttpTestingController);
    api = TestBed.inject(SqlApiService);
    h = await RouterTestingHarness.create('/sql/validate');
  });

  afterEach(() => {
    backend.match((r) => r.url.endsWith('/queries/similar')); // Story 8.4 suggestions are advisory
    backend.verify();
  });

  const el = () => h.routeNativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const area = () => q<HTMLTextAreaElement>('#sql-validate-input')!;
  const button = () => q<HTMLButtonElement>('.validate-btn')!;
  const url = () => TestBed.inject(Router).url;
  const wait = () => new Promise((r) => setTimeout(r));
  const pending = (): TestRequest => backend.expectOne(`${api.baseUrl}/validate`);

  function paste(sql: string): void {
    area().value = sql;
    area().dispatchEvent(new Event('input'));
    h.detectChanges();
  }
  function ctrlEnter(): void {
    area().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, cancelable: true }));
    h.detectChanges();
  }
  function respond(req: TestRequest, over: Partial<ValidateResponse>): void {
    req.flush({ accepted: true, normalized_sql: req.request.body.sql_content, rejections: [], warnings: [], safety_note: SAFETY, ...over });
    h.detectChanges();
  }

  it('Idle: explainer, empty paste area and a disabled Validate with the shortcut hint', () => {
    expect(q('h1')!.textContent).toContain('Validate');
    expect(q('.explainer')!.textContent).toContain(VALIDATE_EXPLAINER);
    expect(area().value).toBe('');
    expect(button().disabled).toBeTrue();
    expect(q('.hint')!.textContent).toContain('Ctrl/⌘+Enter');
    expect(q('.result')).toBeNull();
    expect(q('.safety-note')).toBeNull();
    paste('   ');
    expect(button().disabled).toBeTrue();
  });

  it('Over limit: red counter, invalid field, disabled button, nothing sent', () => {
    paste('x'.repeat(19_001));
    expect(q('.counter')!.textContent).toContain('19001/20000');
    expect(q('.counter')!.classList).not.toContain('over');
    paste('x'.repeat(20_001));
    expect(q('.counter')!.classList).toContain('over');
    expect(area().getAttribute('aria-invalid')).toBe('true');
    expect(button().disabled).toBeTrue();
    ctrlEnter();
    backend.expectNone(`${api.baseUrl}/validate`);
  });

  it('Validating: posts sql_content and disables the button while in flight', () => {
    paste(CTE_ATTACK);
    button().click();
    h.detectChanges();
    const req = pending();
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ sql_content: CTE_ATTACK });
    expect(button().disabled).toBeTrue();
    expect(button().textContent).toContain('Validating…');
    respond(req, {});
    expect(button().disabled).toBeFalse();
  });

  it('Rejected: header, DATA_MODIFYING_CTE chip with reason, Normalized SQL, Safety Note; no Copy or Save', async () => {
    paste(CTE_ATTACK);
    ctrlEnter();
    respond(pending(), { accepted: false, rejections: [{ code: 'DATA_MODIFYING_CTE', reason: "CTE 'x' contains an INSERT" }] });
    await wait();
    const header = q('.result-header')!;
    expect(header.textContent).toContain('✕ Rejected · 1 problem');
    expect(header.classList).toContain('rejected');
    expect(document.activeElement).toBe(header);
    const item = q('.rejections li')!;
    expect(item.querySelector('.code-chip')!.textContent).toBe('DATA_MODIFYING_CTE');
    expect(item.textContent).toContain("CTE 'x' contains an INSERT");
    expect(q('.normalized-sql')!.textContent).toBe(CTE_ATTACK);
    expect(q('.safety-note')!.textContent).toContain(SAFETY);
    expect(q('sql-copy-button')).toBeNull();
    expect(q('.save-btn')).toBeNull();
  });

  it('pluralises the problem count', () => {
    paste('SELECT 1; DROP TABLE t');
    ctrlEnter();
    respond(pending(), {
      accepted: false,
      rejections: [{ code: 'MULTIPLE_STATEMENTS', reason: 'a' }, { code: 'NON_SELECT_STATEMENT', reason: 'b' }]
    });
    expect(q('.result-header')!.textContent).toContain('✕ Rejected · 2 problems');
    expect(el().querySelectorAll('.rejections li').length).toBe(2);
  });

  it('Accepted without Warnings: header, Normalized SQL, Copy and Save to library, Safety Note', () => {
    paste(WOW);
    ctrlEnter();
    respond(pending(), {});
    const header = q('.result-header')!;
    expect(header.textContent).toContain('✓ Accepted · single read-only SELECT');
    expect(header.classList).toContain('accepted');
    expect(q('.rejections')).toBeNull();
    expect(q('.warnings')).toBeNull();
    expect(q('.normalized-sql')!.textContent).toBe(WOW);
    expect(q('sql-copy-button')).not.toBeNull();
    expect(q('.save-btn')!.textContent).toContain('Save to library');
    expect(q('.safety-note')!.textContent).toContain(SAFETY);
  });

  it('Accepted with Warnings: shows Warnings (n) with each text', () => {
    paste('SELECT pg_sleep(60)');
    ctrlEnter();
    respond(pending(), { warnings: ['pg_sleep can hold a connection for a long time'] });
    expect(q('.result-header')!.textContent).toContain('✓ Accepted');
    expect(q('.warnings-title')!.textContent).toContain('Warnings (1)');
    expect(q('.warnings')!.textContent).toContain('pg_sleep can hold a connection for a long time');
  });

  it('Copy on an accepted result copies the normalized SQL and records no usage', async () => {
    const write = spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    paste('ＳＥＬＥＣＴ 1');
    ctrlEnter();
    respond(pending(), { normalized_sql: 'SELECT 1' });
    q<HTMLButtonElement>('sql-copy-button button')!.click();
    await wait();
    h.detectChanges();
    expect(write).toHaveBeenCalledOnceWith('SELECT 1');
    backend.expectNone((r) => r.url.endsWith('/usage'));
  });

  it('400: shows the server message inline and no result', () => {
    paste('SELECT 1');
    ctrlEnter();
    pending().flush(
      { error: 'VALIDATION_FAILED', message: 'sql_content must not be blank', field: 'sql_content', details: null },
      { status: 400, statusText: 'Bad Request' }
    );
    h.detectChanges();
    expect(q('.field-error')!.textContent).toContain('sql_content must not be blank');
    expect(area().getAttribute('aria-invalid')).toBe('true');
    expect(q('.result')).toBeNull();
  });

  it('Network failure: generic danger banner, no result panel, and an earlier result is not kept', () => {
    paste(WOW);
    ctrlEnter();
    respond(pending(), {});
    expect(q('.result')).not.toBeNull();
    ctrlEnter();
    expect(q('.result')).toBeNull();
    pending().error(new ProgressEvent('error'), { status: 0 });
    h.detectChanges();
    expect(q('.validate-error')!.textContent).toContain('Service unavailable. Try again in a moment.');
    expect(q('.validate-error')!.getAttribute('role')).toBe('alert');
    expect(q('.result')).toBeNull();
    expect(q('.safety-note')).toBeNull();
  });

  it('keeps the input and result in session state across module navigation', async () => {
    paste(WOW);
    ctrlEnter();
    respond(pending(), {});
    await h.navigateByUrl('/sql/library');
    await h.navigateByUrl('/sql/validate');
    h.detectChanges();
    expect(area().value).toBe(WOW);
    expect(q('.result-header')!.textContent).toContain('✓ Accepted');
  });

  it('Save to library opens the Save form prefilled with the normalized SQL', async () => {
    paste('```sql\nSELECT 1\n```');
    ctrlEnter();
    respond(pending(), { normalized_sql: 'SELECT 1' });
    q<HTMLButtonElement>('.save-btn')!.click();
    await h.fixture.whenStable();
    h.detectChanges();
    expect(url()).toBe('/sql/queries/new?from=validate');
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe('SELECT 1');
    expect(q<HTMLInputElement>('#sql-save-title')!.value).toBe('');
    // One-shot: a later visit to the blank form gets no prefill.
    expect(TestBed.inject(SqlSessionState).takePrefill('validate')).toBeNull();
  });

  it('a Save form opened without a pending prefill stays blank', async () => {
    await h.navigateByUrl('/sql/queries/new?from=validate');
    h.detectChanges();
    expect(q<HTMLTextAreaElement>('#sql-save-sql_content')!.value).toBe('');
  });

  it('inserts two spaces on Tab in the paste area', () => {
    paste('SELECT');
    area().setSelectionRange(6, 6);
    const ev = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    area().dispatchEvent(ev);
    h.detectChanges();
    expect(ev.defaultPrevented).toBeTrue();
    expect(area().value).toBe('SELECT  ');
  });
});
