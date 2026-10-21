import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { SqlApiService } from '../core/sql-api.service';
import { PICKER_DEBOUNCE_MS, QueryRef, ReplacedByPickerComponent } from './replaced-by-picker.component';

describe('ReplacedByPickerComponent', () => {
  let fixture: ComponentFixture<ReplacedByPickerComponent>;
  let backend: HttpTestingController;
  let api: SqlApiService;
  let picked: (QueryRef | null)[];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [SqlApiService, provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
    api = TestBed.inject(SqlApiService);
    fixture = TestBed.createComponent(ReplacedByPickerComponent);
    fixture.componentInstance.excludeId = 1;
    picked = [];
    fixture.componentInstance.selectedChange.subscribe((r) => picked.push(r));
    fixture.detectChanges();
  });

  afterEach(() => backend.verify());

  const input = () => fixture.nativeElement.querySelector('input') as HTMLInputElement;
  const key = (k: string) => {
    const e = new KeyboardEvent('keydown', { key: k, cancelable: true, bubbles: true });
    input().dispatchEvent(e);
    fixture.detectChanges();
    return e;
  };
  function typeText(v: string): void {
    input().value = v;
    input().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
  const page = (titles: [number, string][]) => ({ content: titles.map(([id, title]) => ({ id, title })), page: 0, size: 9, total_elements: titles.length, total_pages: 1 });

  it('waits for 2 characters', fakeAsync(() => {
    typeText('v');
    tick(PICKER_DEBOUNCE_MS);
    backend.expectNone(() => true);
    expect(input().getAttribute('aria-expanded')).toBe('false');
  }));

  it('searches, excludes this query, moves with arrows and picks with Enter', fakeAsync(() => {
    typeText('or');
    expect(input().getAttribute('aria-expanded')).toBe('true');
    tick(PICKER_DEBOUNCE_MS);
    backend.expectOne((r) => r.url === `${api.baseUrl}/queries`).flush(page([[1, 'Self'], [2, 'A'], [3, 'B']]));
    fixture.detectChanges();
    const options = fixture.nativeElement.querySelectorAll('[role="option"]');
    expect(Array.from(options).map((o) => (o as HTMLElement).textContent!.trim())).toEqual(['A', 'B']);
    key('ArrowDown');
    expect(input().getAttribute('aria-activedescendant')).toBe('sql-replaced-by-opt-1');
    key('Enter');
    expect(picked).toEqual([{ id: 3, title: 'B' }]);
  }));

  it('shows "No matching queries" and Esc closes only the list', fakeAsync(() => {
    typeText('zz');
    tick(PICKER_DEBOUNCE_MS);
    backend.expectOne(() => true).flush(page([]));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No matching queries');
    const esc = key('Escape');
    expect(esc.defaultPrevented).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="listbox"]')).toBeNull();
  }));

  it('shows the pick as a removable chip', () => {
    fixture.componentRef.setInput('selected', { id: 5, title: 'V2' });
    fixture.detectChanges();
    const remove = fixture.nativeElement.querySelector('.tag-remove') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe('Remove replacement V2');
    remove.click();
    expect(picked).toEqual([null]);
  });
});
