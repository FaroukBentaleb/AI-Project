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
  warnings: string[];
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
