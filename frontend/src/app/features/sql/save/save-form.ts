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

/**
 * Post-mutation toast rule (EXPERIENCE.md): warnings[] first, then not-read-only/deprecated, then indexed.
 * The API has no `read_only` yet (Epic 4); it is honoured when present.
 */
export function mutationToast(saved: SavedQuery, verb = 'Saved'): { kind: ToastKind; text: string } {
  if (saved.warnings?.length) {
    return { kind: 'warning', text: `${verb} · not yet indexed (team examples unavailable)` };
  }
  const readOnly = (saved as SavedQuery & { read_only?: boolean }).read_only;
  if (readOnly === false || saved.deprecated) {
    return { kind: 'success', text: verb };
  }
  return { kind: 'success', text: `${verb} · added to team examples` };
}
