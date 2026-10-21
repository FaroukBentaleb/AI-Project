/** API shapes of sql-service (snake_case, as sent on the wire). */
export interface SavedQuery {
  id: number;
  title: string;
  description: string;
  sql_content: string;
  tags: string[];
  dialect: string;
  notes: string | null;
  author: string;
  created_at: string;
  updated_at: string;
  is_llm_generated: boolean;
  original_nl_prompt: string | null;
  deprecated: boolean;
  deprecated_reason: string | null;
  deprecated_at: string | null;
  replaced_by: number | null;
  use_count: number;
  last_used_at: string | null;
  version: number;
  /** Story 4.4: null only before the startup backfill ran. */
  read_only?: boolean | null;
  /** `"CODE: reason"` strings; empty when read-only. */
  read_only_reasons?: string[];
  /** Epic 5 indexing status, honoured when present. */
  indexed?: boolean;
  warnings: string[];
}

export interface Rejection {
  code: string;
  reason: string;
}

/** `POST /api/sql/validate` response (addendum §B). */
export interface ValidateResponse {
  accepted: boolean;
  normalized_sql: string;
  rejections: Rejection[];
  warnings: string[];
  safety_note: string;
}

/** Story 8.4: an Indexed Query similar to a draft on the Save form. */
export interface SimilarQuery {
  id: number;
  title: string;
}

/** Story 8.4 draft body for `POST /queries/similar`. */
export interface SimilarQueryDraft {
  title: string;
  description: string;
  sql_content: string;
}

/** A Saved Query injected as an Example (6.4). */
export interface ExampleRef {
  id: number;
  title: string;
}

/** `POST /api/sql/generate` 200 response (FR-18). */
export interface GenerateResponse {
  sql: string;
  dialect: string;
  source: string;
  original_prompt: string;
  examples_used: ExampleRef[];
  warnings: string[];
  safety_note: string;
  valid: boolean;
  prompt_version: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

/** Error envelope returned by sql-service for every non-2xx response. */
export interface ApiErrorEnvelope {
  error: string;
  message: string | null;
  field: string | null;
  details: unknown;
}

export type QueryParams = Record<string, string | number | boolean | readonly string[] | undefined | null>;
