import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Page, SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { LibraryComponent, SEARCH_DEBOUNCE_MS, pageWindow } from './library.component';
import { savedQuery } from './query-card.component.spec';

describe('LibraryComponent', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'sql/library', component: LibraryComponent, providers: [SqlApiService] },
          { path: 'sql/generate', children: [] },
          { path: 'sql/queries/new', children: [] },
          { path: 'sql/queries/:id', children: [] }
        ])
      ]
    });
    backend = TestBed.inject(HttpTestingController);
  });
  afterEach(() => backend.verify());

  const el = (h: RouterTestingHarness) => h.routeNativeElement as HTMLElement;
  const pending = (): TestRequest => backend.expectOne((r) => r.url.endsWith('/sql/queries'));
  const page = (content: SavedQuery[], over: Partial<Page<SavedQuery>> = {}): Page<SavedQuery> => ({
    content, page: 0, size: 20, total_elements: content.length, total_pages: content.length ? 1 : 0, ...over
  });
  const params = (r: TestRequest) => r.request.params;
  const url = () => TestBed.inject(Router).url;

  async function open(path = '/sql/library'): Promise<RouterTestingHarness> {
    const h = await RouterTestingHarness.create(path);
    return h;
  }

  async function flush(h: RouterTestingHarness, body: Page<SavedQuery>): Promise<void> {
    pending().flush(body);
    h.detectChanges();
    await h.fixture.whenStable();
  }

  it('loads 20 newest first, hiding deprecated, with skeletons while loading', async () => {
    const h = await open();
    expect(el(h).querySelectorAll('.skeleton-card').length).toBe(3);
    expect(el(h).querySelector('.list')!.getAttribute('aria-hidden')).toBe('true');
    expect(el(h).querySelector('[aria-live="polite"]')!.textContent).toContain('Loading');
    const req = pending();
    expect(params(req).get('page')).toBe('0');
    expect(params(req).get('size')).toBe('20');
    expect(params(req).get('sort')).toBe('created_at,desc');
    expect(params(req).get('deprecated')).toBe('false');
    req.flush(page([savedQuery({ id: 1 }), savedQuery({ id: 2, title: 'Second', sql_content: 'SELECT 2' })]));
    h.detectChanges();
    expect(el(h).querySelectorAll('.skeleton-card').length).toBe(0);
    const cards = el(h).querySelectorAll('sql-query-card');
    expect(cards.length).toBe(2);
    expect(cards[1].querySelector('pre code')!.textContent).toBe('SELECT 2');
    expect(el(h).querySelector('[aria-live="polite"]')!.textContent).toContain('2 queries');
    expect(el(h).querySelector('.pager')).toBeNull();
  });

  it('restores state from the URL and sends combined filters', async () => {
    await open('/sql/library?q=orders&tags=billing,kpi&author=sami&llm=true&deprecated=show&sort=title&page=2');
    const req = pending();
    expect(params(req).get('q')).toBe('orders');
    expect(params(req).get('tags')).toBe('billing,kpi');
    expect(params(req).get('author')).toBe('sami');
    expect(params(req).get('llm_generated')).toBe('true');
    expect(params(req).has('deprecated')).toBeFalse();
    expect(params(req).get('sort')).toBe('title,asc');
    expect(params(req).get('page')).toBe('1');
    req.flush(page([]));
  });

  it('debounces search, writes it to the URL and resets to page 1', fakeAsync(() => {
    let h!: RouterTestingHarness;
    RouterTestingHarness.create('/sql/library?page=3').then((x) => (h = x));
    tick();
    pending().flush(page([], { page: 2, total_elements: 50, total_pages: 3 }));
    h.detectChanges();
    const search = el(h).querySelector<HTMLInputElement>('#sql-library-search')!;
    search.value = 'orders';
    search.dispatchEvent(new Event('input'));
    tick(SEARCH_DEBOUNCE_MS - 10);
    backend.expectNone((r) => r.url.endsWith('/sql/queries'));
    tick(10);
    expect(url()).toBe('/sql/library?q=orders');
    const req = pending();
    expect(params(req).get('q')).toBe('orders');
    expect(params(req).get('page')).toBe('0');
    req.flush(page([]));
  }));

  it('applies search immediately on Enter', async () => {
    const h = await open();
    await flush(h, page([savedQuery()]));
    const search = el(h).querySelector<HTMLInputElement>('#sql-library-search')!;
    search.value = ' kpi ';
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library?q=kpi');
    expect(params(pending()).get('q')).toBe('kpi');
  });

  it('applies filter controls and sort, each resetting the page', async () => {
    const h = await open('/sql/library?page=2');
    await flush(h, page([savedQuery()], { page: 1, total_elements: 21, total_pages: 2 }));

    el(h).querySelector<HTMLButtonElement>('[role="switch"]')!.click();
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library?llm=true');
    expect(params(pending()).get('llm_generated')).toBe('true');

    const show = el(h).querySelectorAll<HTMLButtonElement>('[role="radio"]')[1];
    show.click();
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library?llm=true&deprecated=show');
    pending();

    const author = el(h).querySelector<HTMLInputElement>('[aria-label="Filter by author"]')!;
    author.value = 'leila';
    author.dispatchEvent(new Event('blur'));
    await h.fixture.whenStable();
    expect(params(pending()).get('author')).toBe('leila');

    const tags = el(h).querySelector<HTMLInputElement>('#sql-filter-tags')!;
    tags.value = 'Billing';
    tags.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
    await h.fixture.whenStable();
    expect(params(pending()).get('tags')).toBe('billing');

    const sort = el(h).querySelector<HTMLSelectElement>('#sql-library-sort')!;
    sort.value = 'most_used';
    sort.dispatchEvent(new Event('change'));
    await h.fixture.whenStable();
    const req = pending();
    expect(params(req).get('sort')).toBe('use_count,desc');
    expect(params(req).get('page')).toBe('0');
    expect(url()).toContain('sort=most_used');
    expect(url()).not.toContain('page=');
  });

  it('moves the Deprecated segmented control with arrow keys', async () => {
    const h = await open();
    await flush(h, page([savedQuery()]));
    el(h).querySelector('[role="radiogroup"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true }));
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library?deprecated=show');
    pending();
  });

  it('shows the no-results state with Clear filters and Generate "<q>"', async () => {
    const h = await open('/sql/library?q=zzz&llm=true&sort=title');
    await flush(h, page([]));
    const empty = el(h).querySelector('.empty-state')!;
    expect(empty.textContent).toContain("No queries match 'zzz'.");
    const generate = Array.from(empty.querySelectorAll('a')).find((a) => a.textContent!.includes('Generate "zzz"'))!;
    expect(generate.getAttribute('href')).toBe('/sql/generate');

    Array.from(empty.querySelectorAll('button')).find((b) => b.textContent!.includes('Clear filters'))!.click();
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library?sort=title');
    pending();
  });

  it('hands the search term to Generate without submitting it', async () => {
    const h = await open('/sql/library?q=late%20orders');
    await flush(h, page([]));
    const generate = Array.from(el(h).querySelectorAll<HTMLAnchorElement>('.empty-state a')).find((a) => a.textContent!.includes('Generate'))!;
    generate.click();
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/generate');
    expect(TestBed.inject(Router).lastSuccessfulNavigation?.extras.state).toEqual({ prompt: 'late orders' });
  });

  it('shows the empty-library state when nothing is filtered', async () => {
    const h = await open();
    await flush(h, page([]));
    const empty = el(h).querySelector('.empty-state')!;
    expect(empty.textContent).toContain('No queries yet.');
    expect(empty.querySelector('a[href="/sql/queries/new"]')).not.toBeNull();
    expect(empty.querySelector('a[href="/sql/generate"]')).not.toBeNull();
    expect(el(h).querySelector('.clear')).toBeNull();
  });

  it('shows a danger banner with the generic message and retries', async () => {
    const h = await open();
    pending().error(new ProgressEvent('error'), { status: 0 });
    h.detectChanges();
    const banner = el(h).querySelector('.banner-danger')!;
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.textContent).toContain('Service unavailable. Try again in a moment.');
    banner.querySelector('button')!.click();
    h.detectChanges();
    expect(el(h).querySelectorAll('.skeleton-card').length).toBe(3);
    await flush(h, page([savedQuery()]));
    expect(el(h).querySelector('.banner-danger')).toBeNull();
    expect(el(h).querySelectorAll('sql-query-card').length).toBe(1);
  });

  it('pages with range text and page buttons', async () => {
    const h = await open('/sql/library?page=2');
    const rows = Array.from({ length: 3 }, (_, i) => savedQuery({ id: 21 + i }));
    await flush(h, page(rows, { page: 1, total_elements: 23, total_pages: 2 }));
    const pager = el(h).querySelector('.pager')!;
    expect(pager.querySelector('.range')!.textContent).toBe('21–23 of 23');
    expect(pager.querySelector('[aria-current="page"]')!.textContent).toBe('2');
    expect(pager.querySelector<HTMLButtonElement>('[aria-label="Next page"]')!.disabled).toBeTrue();
    pager.querySelector<HTMLButtonElement>('[aria-label="Page 1"]')!.click();
    await h.fixture.whenStable();
    expect(url()).toBe('/sql/library');
    expect(params(pending()).get('page')).toBe('0');
  });

  it('builds a compact page window', () => {
    expect(pageWindow(1, 1)).toEqual([1]);
    expect(pageWindow(5, 10)).toEqual([1, null, 4, 5, 6, null, 10]);
    expect(pageWindow(1, 4)).toEqual([1, 2, null, 4]);
  });
});
