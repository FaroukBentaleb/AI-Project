import { SavedQuery } from '../core/models';

/** Client limits mirror CreateSavedQueryRequest (sql-service, Story 1.2). */
export const SAVE_LIMITS = {
  title: 200,
  description: 2000,
  sql_content: 20000,
  notes: 2000,
  original_nl_prompt: 1000,
  tags: 10,
  tag: 50
} as const;

/** The SQL counter only shows near the limit (EXPERIENCE.md SQL textarea). */
export const SQL_COUNTER_FROM = 19000;

export const DEFAULT_DIALECT = 'postgresql';

/** Form fields in DOM order; keys are the API's snake_case names so server `field` maps 1:1. */
export const FORM_FIELDS = ['title', 'description', 'sql_content', 'tags', 'notes', 'dialect'] as const;
export type FormField = (typeof FORM_FIELDS)[number];

/** Fields inside the collapsed "More options" section. */
export const MORE_FIELDS: readonly FormField[] = ['tags', 'notes', 'dialect'];

export interface SaveDraft {
  title: string;
  description: string;
  sql_content: string;
  tags: string[];
  notes: string;
  dialect: string;
  is_llm_generated: boolean;
  original_nl_prompt: string | null;
}

export interface CreateQueryRequest {
  title: string;
  description: string;
  sql_content: string;
  tags: string[];
  notes: string | null;
  dialect: string;
  is_llm_generated: boolean;
  original_nl_prompt: string | null;
}

export type FieldErrors = Partial<Record<FormField, string>>;

export function emptyDraft(): SaveDraft {
  return {
    title: '',
    description: '',
    sql_content: '',
    tags: [],
    notes: '',
    dialect: DEFAULT_DIALECT,
    is_llm_generated: false,
    original_nl_prompt: null
  };
}

const LABELS: Record<FormField, string> = {
  title: 'Title',
  description: 'Description',
  sql_content: 'SQL',
  tags: 'Tags',
  notes: 'Notes',
  dialect: 'Dialect'
};

function required(value: string, field: FormField): string | null {
  return value.trim() ? null : `${LABELS[field]} is required.`;
}

function maxLen(value: string, field: FormField, max: number): string | null {
  return value.length > max ? `${LABELS[field]} must be at most ${max.toLocaleString('en-US')} characters.` : null;
}

/** Same rules as the API: required = not blank, lengths counted on the raw value. */
export function validateField(draft: SaveDraft, field: FormField): string | null {
  switch (field) {
    case 'title':
    case 'description':
    case 'sql_content':
      return required(draft[field], field) ?? maxLen(draft[field], field, SAVE_LIMITS[field]);
    case 'notes':
      return maxLen(draft.notes, 'notes', SAVE_LIMITS.notes);
    case 'tags':
      if (draft.tags.length > SAVE_LIMITS.tags) {
        return `Up to ${SAVE_LIMITS.tags} tags.`;
      }
      return draft.tags.some((t) => !t.trim() || t.length > SAVE_LIMITS.tag)
        ? `Each tag must be 1–${SAVE_LIMITS.tag} characters.`
        : null;
    case 'dialect':
      return null;
  }
}

export function validateDraft(draft: SaveDraft): FieldErrors {
  const errors: FieldErrors = {};
  for (const f of FORM_FIELDS) {
    const e = validateField(draft, f);
    if (e) {
      errors[f] = e;
    }
  }
  return errors;
}

export function firstInvalid(errors: FieldErrors): FormField | null {
  return FORM_FIELDS.find((f) => !!errors[f]) ?? null;
}

export function toCreateRequest(draft: SaveDraft): CreateQueryRequest {
  const notes = draft.notes.trim() ? draft.notes : null;
  return {
    title: draft.title.trim(),
    description: draft.description.trim(),
    sql_content: draft.sql_content,
    tags: [...draft.tags],
    notes,
    dialect: draft.dialect || DEFAULT_DIALECT,
    is_llm_generated: draft.is_llm_generated,
    original_nl_prompt: draft.original_nl_prompt
  };
}

export function sameDraft(a: SaveDraft, b: SaveDraft): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

export type ToastKind = 'success' | 'warning';

/** Start of the server's indexing-failure Warning (`QueryIndexer.WARNING_PREFIX`). */
export const NOT_INDEXED_WARNING_PREFIX = 'Not yet indexed';

export function isIndexingWarning(w: string): boolean {
  return w.trim().startsWith(NOT_INDEXED_WARNING_PREFIX);
}

/**
 * Post-mutation toast rule (EXPERIENCE.md): an indexing Warning first, then not-read-only/deprecated, then indexed.
 * Other Warnings (read-only check, e.g. a risky function) keep the rule 2/3 text, append a count and make the
 * toast a warning, since `warnings[]` also carries validator Warnings (Story 7.1 fix).
 */
export function mutationToast(saved: SavedQuery, verb = 'Saved'): { kind: ToastKind; text: string } {
  const warnings = saved.warnings ?? [];
  if (warnings.some(isIndexingWarning)) {
    return { kind: 'warning', text: `${verb} · not yet indexed (team examples unavailable)` };
  }
  const base = saved.read_only === false || saved.deprecated ? verb : `${verb} · added to team examples`;
  const others = warnings.length;
  if (others) {
    return { kind: 'warning', text: `${base} · ${others} read-only check ${others === 1 ? 'warning' : 'warnings'}` };
  }
  return { kind: 'success', text: base };
}

/** Fields an edit can change (is_llm_generated and the original prompt are fixed at creation). */
export const EDITABLE_FIELDS: readonly FormField[] = FORM_FIELDS;

export interface PatchQueryRequest {
  title?: string;
  description?: string;
  sql_content?: string;
  tags?: string[];
  notes?: string;
  dialect?: string;
  version: number;
}

export function draftFromQuery(q: SavedQuery): SaveDraft {
  return {
    title: q.title ?? '',
    description: q.description ?? '',
    sql_content: q.sql_content ?? '',
    tags: [...(q.tags ?? [])],
    notes: q.notes ?? '',
    dialect: q.dialect || DEFAULT_DIALECT,
    is_llm_generated: !!q.is_llm_generated,
    original_nl_prompt: q.original_nl_prompt ?? null
  };
}

export function sameField(a: SaveDraft, b: SaveDraft, f: FormField): boolean {
  return JSON.stringify(a[f]) === JSON.stringify(b[f]);
}

/** PATCH body: only the fields that differ from `base` (null means unchanged on the API; "" clears notes). */
export function toPatchRequest(draft: SaveDraft, base: SaveDraft, version: number): PatchQueryRequest {
  const body: PatchQueryRequest = { version };
  const full = toCreateRequest(draft);
  for (const f of EDITABLE_FIELDS) {
    if (sameField(draft, base, f)) {
      continue;
    }
    if (f === 'notes') {
      body.notes = full.notes ?? '';
    } else if (f === 'tags') {
      body.tags = full.tags;
    } else {
      (body as unknown as Record<string, unknown>)[f] = full[f];
    }
  }
  return body;
}

export interface FieldMarker {
  /** Set when someone else also changed the field: their value, as text. */
  server: string | null;
}
export type FieldMarkers = Partial<Record<FormField, FieldMarker>>;

export function fieldText(draft: SaveDraft, f: FormField): string {
  const v = draft[f];
  return Array.isArray(v) ? (v.length ? v.join(', ') : '(no tags)') : String(v ?? '');
}

/**
 * Merge on Reload (EXPERIENCE.md Edit 409): untouched fields take the latest server values; fields the user
 * changed keep the user's value and get a marker, flagged when the server changed them too.
 */
export function mergeOnReload(base: SaveDraft, mine: SaveDraft, latest: SaveDraft): { draft: SaveDraft; markers: FieldMarkers } {
  const draft: SaveDraft = { ...latest, tags: [...latest.tags] };
  const markers: FieldMarkers = {};
  for (const f of EDITABLE_FIELDS) {
    if (sameField(mine, base, f) || sameField(mine, latest, f)) {
      continue;
    }
    (draft as unknown as Record<string, unknown>)[f] = f === 'tags' ? [...mine.tags] : mine[f];
    markers[f] = { server: sameField(latest, base, f) ? null : fieldText(latest, f) };
  }
  return { draft, markers };
}

/** "More options" label on edit summarizes the values (EXPERIENCE.md Save form). */
export function moreSummary(draft: SaveDraft): string {
  const parts: string[] = [];
  if (draft.tags.length) {
    parts.push(`${draft.tags.length} tag${draft.tags.length === 1 ? '' : 's'}`);
  }
  if (draft.notes.trim()) {
    parts.push('notes');
  }
  parts.push(draft.dialect === DEFAULT_DIALECT ? 'PostgreSQL' : draft.dialect);
  return parts.join(' · ');
}
