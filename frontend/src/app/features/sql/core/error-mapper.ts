import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorEnvelope } from './models';

export const GENERIC_ERROR_MESSAGE = 'Service unavailable. Try again in a moment.';

/** Client copy keyed by envelope `error` code (EXPERIENCE.md error-copy map). */
export const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  LLM_UNAVAILABLE: 'SQL generation temporarily unavailable. You can save a query manually.',
  LLM_QUOTA_EXCEEDED: "The team's AI quota is used up for now. You can save a query manually.",
  LLM_NOT_CONFIGURED: "SQL generation isn't configured on this server. You can save a query manually.",
  LLM_REFUSED: 'The model declined to generate SQL for this request. Try rephrasing.',
  SQL_PARSE_ERROR: "The model's reply couldn't be read as SQL. Try rephrasing.",
  SQL_REJECTED:
    'The generated SQL was rejected by the read-only check. It is shown for reference only and cannot be copied or saved from here.',
  CONFLICT:
    'Someone else saved changes to this query. We loaded their version and kept your changes — review and save again.',
  NOT_FOUND: "This query doesn't exist or was deleted."
};

/** Codes whose server `message` is the user-facing text (shown inline on `field`). */
const SERVER_MESSAGE_CODES = new Set(['VALIDATION_FAILED']);

export interface UiError {
  /** Envelope code, or null for network / non-JSON failures. */
  code: string | null;
  message: string;
  /** snake_case field name for inline display, when the server named one. */
  field: string | null;
  /** HTTP status, 0 for network failures. */
  status: number;
  details: unknown;
}

function envelopeOf(body: unknown): ApiErrorEnvelope | null {
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      return null;
    }
  }
  if (body && typeof body === 'object' && typeof (body as ApiErrorEnvelope).error === 'string') {
    return body as ApiErrorEnvelope;
  }
  return null;
}

/** Turns any failure into user-facing copy: client map, then server message for known codes, then generic. */
export function toUiError(err: unknown): UiError {
  const status = err instanceof HttpErrorResponse ? err.status : 0;
  const envelope = err instanceof HttpErrorResponse ? envelopeOf(err.error) : null;
  if (!envelope) {
    return { code: null, message: GENERIC_ERROR_MESSAGE, field: null, status, details: null };
  }
  const code = envelope.error;
  const field = typeof envelope.field === 'string' ? envelope.field : null;
  const details = envelope.details ?? null;
  let message = ERROR_MESSAGES[code];
  if (!message && SERVER_MESSAGE_CODES.has(code) && typeof envelope.message === 'string' && envelope.message.trim()) {
    message = envelope.message;
  }
  return { code, message: message ?? GENERIC_ERROR_MESSAGE, field, status, details };
}
