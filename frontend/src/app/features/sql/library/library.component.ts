import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, map, of, startWith, switchMap, combineLatest } from 'rxjs';
import { toUiError } from '../core/error-mapper';
import { Page, SavedQuery } from '../core/models';
import { SqlApiService } from '../core/sql-api.service';
import { TagsInputComponent } from '../shared/tags-input.component';
import {
  DEFAULT_STATE,
  LibraryState,
  PAGE_SIZE,
  SORT_OPTIONS,
  hasFilters,
  parseState,
  toApiParams,
  toQueryParams
} from './library-state';
import { QueryCardComponent } from './query-card.component';

type LoadResult =
  | { status: 'loading' }
  | { status: 'ready'; page: Page<SavedQuery> }
  | { status: 'error'; message: string };

export const SEARCH_DEBOUNCE_MS = 300;

/** Library screen (FR-27): search, filters, sort and page live in the query string; 20 cards per page. */
@Component({
  selector: 'sql-library',
  standalone: true,
  imports: [RouterLink, QueryCardComponent, TagsInputComponent],
  template: `
    @let s = state();
    <section class="library">
      <h1 class="page-title">Library</h1>

      <div class="search-row">
        <label class="sr-only" for="sql-library-search">Search queries</label>
        <input
          #search
          id="sql-library-search"
          class="text-input search"
          type="search"
          placeholder="Search title and description…"
          maxlength="200"
          [value]="s.q"
          (input)="searchInput$.next(search.value)"
          (keydown.enter)="applySearch(search.value)"
        />
        <label class="sr-only" for="sql-library-sort">Sort by</label>
        <select id="sql-library-sort" class="select" [value]="s.sort" (change)="update({ sort: $any($event.target).value })">
          @for (o of sortOptions; track o.value) {
            <option [value]="o.value" [selected]="o.value === s.sort">{{ o.label }}</option>
          }
        </select>
      </div>

      <div class="filter-bar" role="group" aria-label="Filters">
        <sql-tags-input
          class="tag-filter"
          inputId="sql-filter-tags"
          ariaLabel="Filter by tag"
          placeholder="Filter by tag…"
          [tags]="s.tags"
          (tagsChange)="update({ tags: $event })"
        />
        <input
          #author
          class="text-input author"
          type="text"
          aria-label="Filter by author"
          placeholder="Author"
          maxlength="100"
          [value]="s.author"
          (keydown.enter)="applyAuthor(author.value)"
          (blur)="applyAuthor(author.value)"
        />
        <button
          type="button"
          class="switch"
          role="switch"
          [attr.aria-checked]="s.llmOnly"
          (click)="update({ llmOnly: !s.llmOnly })"
        >
          <span class="track" aria-hidden="true"><span class="knob"></span></span>
          LLM-generated only
        </button>
        <div class="segmented" role="radiogroup" aria-label="Deprecated" (keydown)="onSegmentKeydown($event)">
          <span class="segmented-label" aria-hidden="true">Deprecated</span>
          <button
            type="button"
            role="radio"
            class="segment"
            [attr.aria-checked]="!s.showDeprecated"
            [attr.tabindex]="s.showDeprecated ? -1 : 0"
            (click)="update({ showDeprecated: false })"
          >Hide</button>
          <button
            type="button"
            role="radio"
            class="segment"
            [attr.aria-checked]="s.showDeprecated"
            [attr.tabindex]="s.showDeprecated ? 0 : -1"
            (click)="update({ showDeprecated: true })"
          >Show</button>
        </div>
        @if (filtered()) {
          <button type="button" class="btn btn-ghost clear" (click)="clearFilters()">Clear filters</button>
        }
      </div>

      <p class="sr-only" aria-live="polite">{{ announcement() }}</p>

      @switch (result().status) {
        @case ('loading') {
          <div class="list" aria-hidden="true">
            @for (i of skeletons; track i) {
              <div class="skeleton-card">
                <div class="bar title"></div>
                <div class="bar"></div>
                <div class="bar short"></div>
                <div class="bar code"></div>
              </div>
            }
          </div>
        }
        @case ('error') {
          <div class="banner banner-danger" role="alert">
            <span class="banner-glyph" aria-hidden="true">!</span>
            <span class="banner-text">{{ errorMessage() }}</span>
            <button type="button" class="btn btn-secondary btn-sm" (click)="retry()">Retry</button>
          </div>
        }
        @case ('ready') {
          @if (items().length === 0) {
            <div class="empty-state">
              @if (filtered()) {
                <p>{{ s.q ? "No queries match '" + s.q + "'." : 'No queries match these filters.' }}</p>
                <div class="empty-actions">
                  <button type="button" class="btn btn-secondary" (click)="clearFilters()">Clear filters</button>
                  @if (s.q) {
                    <a class="btn btn-secondary" routerLink="/sql/generate" [state]="{ prompt: s.q }">Generate "{{ s.q }}"</a>
                  }
                </div>
              } @else {
                <p>No queries yet.</p>
                <div class="empty-actions">
                  <a class="btn btn-secondary" routerLink="/sql/queries/new">+ New query</a>
                  <a class="btn btn-secondary" routerLink="/sql/generate">Generate</a>
                </div>
              }
            </div>
          } @else {
            <div class="list">
              @for (q of items(); track q.id) {
                <sql-query-card [query]="q" />
              }
            </div>
          }
          @if (totalPages() > 1 || s.page > 1) {
            <nav class="pager" aria-label="Pages">
              <span class="range">{{ rangeText() }}</span>
              <span class="pages">
                <button type="button" class="page-btn" [disabled]="s.page <= 1" (click)="goTo(s.page - 1)" aria-label="Previous page">‹</button>
                @for (p of pageNumbers(); track $index) {
                  @if (p === null) {
                    <span class="gap" aria-hidden="true">…</span>
                  } @else {
                    <button
                      type="button"
                      class="page-btn"
                      [class.active]="p === s.page"
                      [attr.aria-current]="p === s.page ? 'page' : null"
                      [attr.aria-label]="'Page ' + p"
                      (click)="goTo(p)"
                    >{{ p }}</button>
                  }
                }
                <button type="button" class="page-btn" [disabled]="s.page >= totalPages()" (click)="goTo(s.page + 1)" aria-label="Next page">›</button>
              </span>
            </nav>
          }
        }
      }
    </section>
  `,
  styleUrls: ['../shell/sql-theme.css', './library.component.css']
})
export class LibraryComponent {
  private readonly api = inject(SqlApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly sortOptions = SORT_OPTIONS;
  readonly skeletons = [0, 1, 2];
  readonly searchInput$ = new Subject<string>();
  private readonly retry$ = new Subject<void>();

  readonly state = toSignal(this.route.queryParamMap.pipe(map(parseState)), { initialValue: DEFAULT_STATE });

  readonly result = toSignal(
    combineLatest([this.route.queryParamMap.pipe(map(parseState)), this.retry$.pipe(startWith(undefined))]).pipe(
      switchMap(([s]) =>
        this.api.listQueries(toApiParams(s)).pipe(
          map((page): LoadResult => ({ status: 'ready', page })),
          catchError((err) => of<LoadResult>({ status: 'error', message: toUiError(err).message })),
          startWith<LoadResult>({ status: 'loading' })
        )
      )
    ),
    { initialValue: { status: 'loading' } as LoadResult }
  );

  readonly filtered = computed(() => hasFilters(this.state()));
  readonly page = computed(() => {
    const r = this.result();
    return r.status === 'ready' ? r.page : null;
  });
  readonly items = computed(() => this.page()?.content ?? []);
  readonly totalPages = computed(() => this.page()?.total_pages ?? 0);
  readonly errorMessage = computed(() => {
    const r = this.result();
    return r.status === 'error' ? r.message : '';
  });
  readonly rangeText = computed(() => {
    const p = this.page();
    if (!p || p.content.length === 0) {
      return `0 of ${p?.total_elements ?? 0}`;
    }
    const from = p.page * PAGE_SIZE + 1;
    return `${from}–${from + p.content.length - 1} of ${p.total_elements}`;
  });
  readonly announcement = computed(() => {
    const r = this.result();
    if (r.status === 'loading') {
      return 'Loading';
    }
    if (r.status === 'error') {
      return '';
    }
    const n = r.page.total_elements;
    return n === 1 ? '1 query' : `${n} queries`;
  });
  /** 1-based page numbers with null gaps: first, last, and current ±1. */
  readonly pageNumbers = computed(() => pageWindow(this.state().page, this.totalPages()));

  constructor() {
    this.searchInput$
      .pipe(debounceTime(SEARCH_DEBOUNCE_MS), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((q) => this.applySearch(q));
  }

  applySearch(raw: string): void {
    const q = raw.trim();
    if (q !== this.state().q) {
      this.update({ q });
    }
  }

  applyAuthor(raw: string): void {
    const author = raw.trim();
    if (author !== this.state().author) {
      this.update({ author });
    }
  }

  /** Any filter/search/sort change resets to page 1. */
  update(patch: Partial<Omit<LibraryState, 'page'>>): void {
    this.navigate({ ...this.state(), ...patch, page: 1 });
  }

  goTo(page: number): void {
    this.navigate({ ...this.state(), page });
  }

  clearFilters(): void {
    this.navigate({ ...DEFAULT_STATE, sort: this.state().sort });
  }

  retry(): void {
    this.retry$.next();
  }

  onSegmentKeydown(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
      return;
    }
    event.preventDefault();
    this.update({ showDeprecated: !this.state().showDeprecated });
    const group = event.currentTarget as HTMLElement;
    setTimeout(() => group.querySelector<HTMLElement>('[aria-checked="true"]')?.focus());
  }

  private navigate(s: LibraryState): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: toQueryParams(s) });
  }
}

export function pageWindow(current: number, total: number): Array<number | null> {
  const out: Array<number | null> = [];
  for (let p = 1; p <= total; p++) {
    if (p === 1 || p === total || Math.abs(p - current) <= 1) {
      out.push(p);
    } else if (out[out.length - 1] !== null) {
      out.push(null);
    }
  }
  return out;
}
