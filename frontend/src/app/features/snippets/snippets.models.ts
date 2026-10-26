export type Language = 'python' | 'java' | 'javascript';

export interface SnippetSummary {
  id: number;
  title: string;
  language: Language;
  docstring: string | null;
  docstring_edited: boolean;
  created_at: string;
  updated_at: string;
}

export interface SnippetDetail extends SnippetSummary {
  code: string;
  truncated: boolean;
}

/** Body of create (POST) and update (PUT). `generate` only applies to create. */
export interface SnippetRequest {
  title: string;
  language: Language;
  code: string;
  docstring?: string | null;
  generate?: boolean;
}

/** Error envelope returned by every service: {error, message, field, details}. */
export interface ApiError {
  error: string;
  message: string;
  field: string | null;
}
