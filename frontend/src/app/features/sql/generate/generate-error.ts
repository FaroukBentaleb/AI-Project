import { HttpErrorResponse } from '@angular/common/http';
import { ERROR_MESSAGES, toUiError } from '../core/error-mapper';
import { Rejection } from '../core/models';

export type GenerateErrorKind = 'inline' | 'warning' | 'danger';

/**
 * View model of a failed generation (EXPERIENCE.md Generate states). Story 7.1 renders `message`
 * (inline for 400, else a banner); Story 7.3 adds the per-code actions from `code`, `retryAfterSeconds`
 * and `details` (rejected SQL and reasons) without changing the request flow.
 */
export interface GenerateError {
  kind: GenerateErrorKind;
  code: string | null;
  message: string;
  status: number;
  /** From `details.retry_after_seconds` or the `Retry-After` header; null when not hinted. */
  retryAfterSeconds: number | null;
  details: unknown;
  /** Epoch ms at which *Retry* enables (null: no retry hint). Survives in-app navigation via session state. */
  retryAt: number | null;
  /** Story 7.3 actions (EXPERIENCE.md Generate states). */
  canSaveManually: boolean;
  canRetry: boolean;
  canRephrase: boolean;
  /** `SQL_REJECTED` only: the rejected SQL (reference only) and each reason. */
  rejectedSql: string | null;
  reasons: Rejection[];
  /** `SQL_REJECTED` only: the server's `details.safety_note`, rendered verbatim (Story 8.1). */
  safetyNote: string | null;
}

/** 503 outage codes (plus gateway timeout / network failure, mapped to LLM_UNAVAILABLE). */
const OUTAGE_CODES = new Set(['LLM_UNAVAILABLE', 'LLM_QUOTA_EXCEEDED', 'LLM_NOT_CONFIGURED']);
const RETRY_CODES = new Set(['LLM_UNAVAILABLE', 'LLM_QUOTA_EXCEEDED']);

const DANGER_CODES = new Set(['LLM_NOT_CONFIGURED', 'SQL_REJECTED']);
/**
 * Failures without an envelope read as "temporarily unavailable": gateway route timeout or upstream down
 * (502/503/504) and network failures (status 0), per Story 7.3.
 */
const GATEWAY_STATUSES = new Set([0, 502, 503, 504]);

export function generateError(err: unknown, now: number = Date.now()): GenerateError {
  const ui = toUiError(err);
  let code = ui.code;
  let message = ui.message;
  if (!code && GATEWAY_STATUSES.has(ui.status)) {
    code = 'LLM_UNAVAILABLE';
    message = ERROR_MESSAGES['LLM_UNAVAILABLE'];
  }
  const kind: GenerateErrorKind =
    code === 'VALIDATION_FAILED' ? 'inline' : code && DANGER_CODES.has(code) ? 'danger' : 'warning';
  const retryAfterSeconds = retryAfter(err, ui.details);
  const canRetry = !!code && RETRY_CODES.has(code);
  const rejected = code === 'SQL_REJECTED' ? rejectedParts(ui.details) : { sql: null, reasons: [], safetyNote: null };
  return {
    kind,
    code,
    message,
    status: ui.status,
    retryAfterSeconds,
    details: ui.details,
    retryAt: canRetry && retryAfterSeconds ? now + retryAfterSeconds * 1000 : null,
    canSaveManually: !!code && OUTAGE_CODES.has(code),
    canRetry,
    canRephrase: code === 'SQL_REJECTED',
    rejectedSql: rejected.sql,
    reasons: rejected.reasons,
    safetyNote: rejected.safetyNote
  };
}

/** Seconds left before *Retry* enables, rounded up; 0 when enabled. */
export function retrySecondsLeft(e: GenerateError, now: number = Date.now()): number {
  return e.retryAt == null ? 0 : Math.max(0, Math.ceil((e.retryAt - now) / 1000));
}

/** 6.3 `SQL_REJECTED` details: `rejected_sql`, `reasons[{code, reason}]` and (8.1) `safety_note`. */
function rejectedParts(details: unknown): { sql: string | null; reasons: Rejection[]; safetyNote: string | null } {
  const d = details && typeof details === 'object' ? (details as Record<string, unknown>) : {};
  const sql = typeof d['rejected_sql'] === 'string' && d['rejected_sql'].trim() ? (d['rejected_sql'] as string) : null;
  const reasons = Array.isArray(d['reasons'])
    ? (d['reasons'] as unknown[])
        .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
        .map((r) => ({ code: String(r['code'] ?? ''), reason: String(r['reason'] ?? '') }))
    : [];
  const note = d['safety_note'];
  const safetyNote = typeof note === 'string' && note.trim() ? note : null;
  return { sql, reasons, safetyNote };
}

/** `details.retry_after_seconds` (6.2 envelope), else the `Retry-After` header. */
function retryAfter(err: unknown, details: unknown): number | null {
  const fromBody = details && typeof details === 'object' ? (details as Record<string, unknown>)['retry_after_seconds'] : null;
  const header = err instanceof HttpErrorResponse ? err.headers?.get('Retry-After') : null;
  const raw = typeof fromBody === 'number' ? fromBody : header != null && header.trim() !== '' ? Number(header) : NaN;
  return Number.isFinite(raw) && raw >= 0 ? Math.ceil(raw) : null;
}
