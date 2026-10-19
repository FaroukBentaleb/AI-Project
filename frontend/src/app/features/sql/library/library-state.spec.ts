import { convertToParamMap } from '@angular/router';
import { DEFAULT_STATE, LibraryState, hasFilters, normalizeTags, parseState, toApiParams, toQueryParams } from './library-state';

describe('library-state', () => {
  it('parses an empty query string to defaults', () => {
    expect(parseState(convertToParamMap({}))).toEqual(DEFAULT_STATE);
  });

  it('round-trips through the query string', () => {
    const s: LibraryState = { q: 'orders', tags: ['billing', 'kpi'], author: 'sami', llmOnly: true, showDeprecated: true, sort: 'most_used', page: 3 };
    const qp = toQueryParams(s);
    expect(qp).toEqual({ q: 'orders', tags: 'billing,kpi', author: 'sami', llm: 'true', deprecated: 'show', sort: 'most_used', page: 3 });
    expect(parseState(convertToParamMap(qp))).toEqual(s);
  });

  it('omits defaults from the query string', () => {
    expect(Object.values(toQueryParams(DEFAULT_STATE)).every((v) => v === null)).toBeTrue();
  });

  it('falls back on malformed values', () => {
    const s = parseState(convertToParamMap({ sort: 'evil', page: '-2', llm: 'yes', tags: ' A, ,a,b ' }));
    expect(s.sort).toBe('newest');
    expect(s.page).toBe(1);
    expect(s.llmOnly).toBeFalse();
    expect(s.tags).toEqual(['a', 'b']);
  });

  it('maps to API params: 0-based page, size 20, newest first, deprecated hidden by default', () => {
    expect(toApiParams(DEFAULT_STATE)).toEqual({
      page: 0, size: 20, sort: 'created_at,desc', q: '', tags: null, author: '', llm_generated: null, deprecated: false
    });
  });

  it('maps each sort option and the filters', () => {
    const base: LibraryState = { ...DEFAULT_STATE, tags: ['a', 'b'], llmOnly: true, showDeprecated: true, page: 2 };
    expect(toApiParams({ ...base, sort: 'most_used' })).toEqual(
      jasmine.objectContaining({ sort: 'use_count,desc', page: 1, tags: 'a,b', llm_generated: true, deprecated: null })
    );
    expect(toApiParams({ ...base, sort: 'recently_used' })['sort']).toBe('last_used_at,desc');
    expect(toApiParams({ ...base, sort: 'title' })['sort']).toBe('title,asc');
  });

  it('detects filters; Deprecated: Hide and sort are not filters', () => {
    expect(hasFilters({ ...DEFAULT_STATE, sort: 'title', page: 4 })).toBeFalse();
    expect(hasFilters({ ...DEFAULT_STATE, showDeprecated: true })).toBeTrue();
    expect(hasFilters({ ...DEFAULT_STATE, author: 'x' })).toBeTrue();
  });

  it('normalizes tags', () => {
    expect(normalizeTags([' Billing ', 'billing', '', 'KPI'])).toEqual(['billing', 'kpi']);
  });
});
