export interface CommitSummary {
  id: number;
  project: string;
  created_at: string;
  message: string;
  message_edited: boolean;
  initial: boolean;
  truncated: boolean;
  file_count: number;
  files_changed: number;
}

export interface CommitDetail extends CommitSummary {
  diff: string;
}

/** Error envelope returned by every service: {error, message, field, details}. */
export interface ApiError {
  error: string;
  message: string;
  field: string | null;
}
