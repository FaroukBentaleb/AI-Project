import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { SqlApiService } from '../core/sql-api.service';
import { COPIED_MS, COPY_FAILED_MESSAGE, CopyButtonComponent } from './copy-button.component';

describe('CopyButtonComponent', () => {
  let fixture: ComponentFixture<CopyButtonComponent>;
  let backend: HttpTestingController;
  let write: jasmine.Spy;

  const el = () => fixture.nativeElement as HTMLElement;
  const button = () => el().querySelector<HTMLButtonElement>('button')!;
  const status = () => el().querySelector('[role="status"]')!;

  function create(text: string, queryId: number | null = 7): void {
    fixture = TestBed.createComponent(CopyButtonComponent);
    fixture.componentRef.setInput('text', text);
    fixture.componentRef.setInput('queryId', queryId);
    fixture.detectChanges();
  }

  function click(): void {
    button().click();
    flushMicrotasks();
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [SqlApiService, provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
    write = spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    spyOn(console, 'debug');
  });
  afterEach(() => backend.verify());

  it('copies the exact text, flips to "Copied ✓", announces it and records usage', fakeAsync(() => {
    create('SELECT *\nFROM orders');
    let copied = 0;
    fixture.componentInstance.copied.subscribe(() => copied++);
    click();

    expect(write).toHaveBeenCalledOnceWith('SELECT *\nFROM orders');
    expect(button().textContent).toContain('Copied ✓');
    expect(status().getAttribute('aria-live')).toBe('polite');
    expect(status().textContent).toContain('Copied');
    expect(copied).toBe(1);
    const req = backend.expectOne((r) => r.url.endsWith('/sql/queries/7/usage'));
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });

    tick(COPIED_MS);
    fixture.detectChanges();
    expect(button().textContent!.trim()).toBe('Copy');
    expect(status().textContent!.trim()).toBe('');
  }));

  it('keeps the copy when the usage call fails, with no error shown', fakeAsync(() => {
    create('SELECT 1');
    click();
    backend.expectOne((r) => r.url.endsWith('/usage')).flush({ error: 'X' }, { status: 500, statusText: 'err' });
    fixture.detectChanges();
    expect(write).toHaveBeenCalled();
    expect(button().textContent).toContain('Copied ✓');
    expect(el().querySelector('[role="alert"]')).toBeNull();
    expect(console.debug).toHaveBeenCalled();
    tick(COPIED_MS);
  }));

  it('confirms without waiting for the usage call', fakeAsync(() => {
    create('SELECT 1');
    click();
    expect(button().textContent).toContain('Copied ✓');
    backend.expectOne((r) => r.url.endsWith('/usage')); // still pending
    tick(COPIED_MS);
  }));

  it('records no usage without a query id', fakeAsync(() => {
    create('SELECT 1', null);
    click();
    expect(write).toHaveBeenCalled();
    backend.expectNone((r) => r.url.endsWith('/usage'));
    tick(COPIED_MS);
  }));

  it('falls back with a message when the clipboard is denied, recording no usage', fakeAsync(() => {
    write.and.rejectWith(new Error('denied'));
    create('SELECT 1');
    let failed = 0;
    fixture.componentInstance.failed.subscribe(() => failed++);
    click();
    expect(failed).toBe(1);
    expect(el().querySelector('[role="alert"]')!.textContent).toContain(COPY_FAILED_MESSAGE);
    expect(button().textContent).not.toContain('Copied');
    backend.expectNone((r) => r.url.endsWith('/usage'));
  }));

  it('is a real button with an accessible name', () => {
    create('SELECT 1');
    fixture.componentRef.setInput('label', 'Copy SQL of Weekly orders');
    fixture.detectChanges();
    expect(button().type).toBe('button');
    expect(button().getAttribute('aria-label')).toBe('Copy SQL of Weekly orders');
  });
});
