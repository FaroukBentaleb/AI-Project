import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Page, SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { EXPORT_FALLBACK_FILENAME, LibraryComponent, exportFilename } from './library.component';

/** Story 8.5 (FR35): Export .sql on the Library screen. */
describe('Library export', () => {
  let backend: HttpTestingController;
  let clicked: HTMLAnchorElement[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'sql/library', component: LibraryComponent, providers: [SqlApiService] }])
      ]
    });
    backend = TestBed.inject(HttpTestingController);
    clicked = [];
    spyOn(HTMLAnchorElement.prototype, 'click').and.callFake(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });
    spyOn(URL, 'createObjectURL').and.returnValue('blob:export');
    spyOn(URL, 'revokeObjectURL');
  });
  afterEach(() => backend.verify());

  const empty: Page<SavedQuery> = { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 };
  const el = (h: RouterTestingHarness) => h.routeNativeElement as HTMLElement;
  const button = (h: RouterTestingHarness) => el(h).querySelector<HTMLButtonElement>('button.export')!;

  async function open(path: string): Promise<RouterTestingHarness> {
    const h = await RouterTestingHarness.create(path);
    backend.expectOne((r) => r.url.endsWith('/sql/queries')).flush(empty);
    h.detectChanges();
    return h;
  }

  it('downloads the current filters and sort as a .sql file, without paging', async () => {
    const h = await open('/sql/library?q=users&tags=kpi&author=bob&llm=true&deprecated=show&ro=yes&sort=title&page=3');
    expect(button(h).textContent).toContain('Export .sql');
    button(h).click();
    h.detectChanges();
    expect(button(h).disabled).toBeTrue();
    expect(button(h).textContent).toContain('Exporting');
    const req = backend.expectOne((r) => r.url.endsWith('/sql/queries/export'));
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    const p = req.request.params;
    expect(p.get('q')).toBe('users');
    expect(p.get('author')).toBe('bob');
    expect(p.get('tags')).toBe('kpi');
    expect(p.get('llm_generated')).toBe('true');
    expect(p.get('read_only')).toBe('true');
    expect(p.has('deprecated')).toBeFalse();
    expect(p.has('page')).toBeFalse();
    expect(p.has('size')).toBeFalse();
    expect(p.get('sort')).toMatch(/^title/);
    req.flush(new Blob(['-- export'], { type: 'text/plain' }), {
      headers: { 'Content-Disposition': 'attachment; filename="sql-library-2026-10-20.sql"' }
    });
    h.detectChanges();
    expect(clicked.length).toBe(1);
    expect(clicked[0].download).toBe('sql-library-2026-10-20.sql');
    expect(clicked[0].href).toBe('blob:export');
    expect(button(h).disabled).toBeFalse();
    expect(el(h).querySelector('.export-error')).toBeNull();
  });

  it('default view exports with deprecated hidden', async () => {
    const h = await open('/sql/library');
    button(h).click();
    const req = backend.expectOne((r) => r.url.endsWith('/sql/queries/export'));
    expect(req.request.params.get('deprecated')).toBe('false');
    req.flush(new Blob(['x']));
    expect(clicked[0].download).toBe(EXPORT_FALLBACK_FILENAME);
  });

  it('shows an error banner on failure, no download, and can be dismissed and retried', async () => {
    const h = await open('/sql/library');
    button(h).click();
    backend
      .expectOne((r) => r.url.endsWith('/sql/queries/export'))
      .flush(new Blob(['{}']), { status: 503, statusText: 'Unavailable' });
    h.detectChanges();
    const banner = el(h).querySelector('.export-error');
    expect(banner).not.toBeNull();
    expect(banner!.getAttribute('role')).toBe('alert');
    expect(banner!.textContent).toContain('Export failed');
    expect(clicked.length).toBe(0);
    expect(button(h).disabled).toBeFalse();
    banner!.querySelector<HTMLButtonElement>('button')!.click();
    h.detectChanges();
    expect(el(h).querySelector('.export-error')).toBeNull();
    button(h).click();
    backend.expectOne((r) => r.url.endsWith('/sql/queries/export')).flush(new Blob(['ok']));
    expect(clicked.length).toBe(1);
  });

  it('ignores a second click while exporting', async () => {
    const h = await open('/sql/library');
    button(h).click();
    (h.routeDebugElement!.componentInstance as LibraryComponent).exportSql();
    const reqs = backend.match((r) => r.url.endsWith('/sql/queries/export'));
    expect(reqs.length).toBe(1);
    reqs[0].flush(new Blob(['x']));
  });

  it('sanitises the filename from Content-Disposition', () => {
    expect(exportFilename('attachment; filename="a.sql"')).toBe('a.sql');
    expect(exportFilename('attachment; filename=b-1.sql')).toBe('b-1.sql');
    expect(exportFilename('attachment; filename="../../evil.exe"')).toBe(EXPORT_FALLBACK_FILENAME);
    expect(exportFilename(null)).toBe(EXPORT_FALLBACK_FILENAME);
  });
});
