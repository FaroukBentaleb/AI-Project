import { ParamMap, Params } from '@angular/router';
import { QueryParams } from '../core/models';

export const PAGE_SIZE = 20;

export type ReadOnlyFilter = 'any' | 'yes' | 'no';

export type LibrarySort = 'newest' | 'most_used' | 'recently_used' | 'title';

/** UI sort option -> API `sort` value (EXPERIENCE.md Select (sort)). */
export const SORT_OPTIONS: ReadonlyArray<{ value: LibrarySort; label: string; api: string }> = [
  { value: 'newest', label: 'Newest', api: 'created_at,desc' },
  { value: 'most_used', label: 'Most used', api: 'use_count,desc' },
  { value: 'recently_used', label: 'Recently used', api: 'last_used_at,desc' },
  { value: 'title', label: 'Title', api: 'title,asc' }
];

/** Library view state. Mirrors the query string 1:1 so a link reproduces the view. */
export interface LibraryState {
  q: string;
  tags: string[];
  author: string;
  llmOnly: boolean;
  showDeprecated: boolean;
  /** Read-only filter (FR-4): Any · Yes · No. */
  readOnly: ReadOnlyFilter;
  sort: LibrarySort;
  /** 1-based, as shown to the user. */
  page: number;
}

export const DEFAULT_STATE: LibraryState = {
  q: '',
  tags: [],
  author: '',
  llmOnly: false,
  showDeprecated: false,
  readOnly: 'any',
  sort: 'newest',
  page: 1
};

/** Lenient parse: unknown or malformed values fall back to defaults. */
export function parseState(params: ParamMap): LibraryState {
  const sort = params.get('sort');
  const page = Number(params.get('page'));
  return {
    q: (params.get('q') ?? '').trim(),
    tags: normalizeTags((params.get('tags') ?? '').split(',')),
    author: (params.get('author') ?? '').trim(),
    llmOnly: params.get('llm') === 'true',
    showDeprecated: params.get('deprecated') === 'show',
    readOnly: params.get('ro') === 'yes' || params.get('ro') === 'no' ? (params.get('ro') as ReadOnlyFilter) : 'any',
    sort: SORT_OPTIONS.some((o) => o.value === sort) ? (sort as LibrarySort) : 'newest',
    page: Number.isInteger(page) && page >= 1 ? page : 1
  };
}

/** Query string for a state; defaults are omitted (null removes the param). */
export function toQueryParams(s: LibraryState): Params {
  return {
    q: s.q || null,
    tags: s.tags.length ? s.tags.join(',') : null,
    author: s.author || null,
    llm: s.llmOnly ? 'true' : null,
    deprecated: s.showDeprecated ? 'show' : null,
    ro: s.readOnly === 'any' ? null : s.readOnly,
    sort: s.sort === 'newest' ? null : s.sort,
    page: s.page > 1 ? s.page : null
  };
}

/** sql-service list params (0-based page; deprecated hidden unless shown). */
export function toApiParams(s: LibraryState): QueryParams {
  return {
    page: s.page - 1,
    size: PAGE_SIZE,
    sort: SORT_OPTIONS.find((o) => o.value === s.sort)!.api,
    q: s.q,
    tags: s.tags.length ? s.tags.join(',') : null,
    author: s.author,
    llm_generated: s.llmOnly ? true : null,
    deprecated: s.showDeprecated ? null : false,
    read_only: s.readOnly === 'any' ? null : s.readOnly === 'yes'
  };
}

/** True when any narrowing filter or search is set (Deprecated: Hide is the default, not a filter). */
export function hasFilters(s: LibraryState): boolean {
  return !!s.q || s.tags.length > 0 || !!s.author || s.llmOnly || s.showDeprecated || s.readOnly !== 'any';
}

/** Trim, lowercase, drop empties and duplicates. */
export function normalizeTags(raw: readonly string[]): string[] {
  const out: string[] = [];
  for (const t of raw) {
    const v = t.trim().toLowerCase();
    if (v && !out.includes(v)) {
      out.push(v);
    }
  }
  return out;
}
