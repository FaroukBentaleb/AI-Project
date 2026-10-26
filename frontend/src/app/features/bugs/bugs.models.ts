export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;
export const CATEGORIES = ['UI', 'FUNCTIONAL', 'PERFORMANCE', 'SECURITY', 'CRASH', 'DATA', 'INTEGRATION', 'OTHER'] as const;
export const STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const;
export const SORTS = ['NEWEST', 'OLDEST', 'UPDATED', 'SEVERITY'] as const;

export type Severity = (typeof SEVERITIES)[number];
export type Category = (typeof CATEGORIES)[number];
export type Status = (typeof STATUSES)[number];
export type Sort = (typeof SORTS)[number];

export interface AiTriage {
  severity: Severity;
  category: Category;
  suggested_title: string;
  rationale: string | null;
  model: string;
  prompt_version: string;
  triaged_at: string;
}

export interface Bug {
  id: number;
  title: string;
  description: string;
  steps_to_reproduce: string | null;
  expected_result: string | null;
  actual_result: string | null;
  stack_trace: string | null;
  environment: string | null;
  app_version: string | null;
  severity: Severity;
  category: Category;
  status: Status;
  reporter: string | null;
  assignee: string | null;
  tags: string[];
  ai: AiTriage | null;
  created_at: string;
  updated_at: string;
  resolved_at: string | null;
}

/** Body of create and update; omitted severity/category/status keep their value on update. */
export interface BugInput {
  title: string;
  description: string;
  steps_to_reproduce?: string | null;
  expected_result?: string | null;
  actual_result?: string | null;
  stack_trace?: string | null;
  environment?: string | null;
  app_version?: string | null;
  severity?: Severity;
  category?: Category;
  status?: Status;
  reporter?: string | null;
  assignee?: string | null;
  tags?: string[];
}

export type TriageInput = Pick<BugInput, 'description' | 'steps_to_reproduce' | 'expected_result' |
  'actual_result' | 'stack_trace' | 'environment' | 'app_version'> & { title?: string | null };

export interface TriageResult {
  severity: Severity;
  category: Category;
  suggested_title: string;
  rationale: string | null;
  model: string;
  prompt_version: string;
  redactions: number;
}

export interface TriageStatus {
  configured: boolean;
  model: string;
  provider: string;
  prompt_version: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total_items: number;
  total_pages: number;
}

export interface BugStats {
  total: number;
  open: number;
  by_status: Record<Status, number>;
  open_by_severity: Record<Severity, number>;
  by_category: Record<Category, number>;
}

export interface BugFilter {
  status?: Status;
  severity?: Severity;
  category?: Category;
  q?: string;
  sort: Sort;
  page: number;
}

export interface ApiError {
  error: string;
  message: string;
  field: string | null;
  details: unknown;
}
