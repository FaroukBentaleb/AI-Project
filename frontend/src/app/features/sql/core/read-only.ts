import { Rejection } from './models';

/** FR28 copy notice (EXPERIENCE.md "Do" copy), shared by the card, the detail screen and Validate. */
export const NOT_READ_ONLY_COPY_NOTICE = 'Copied. This query is not read-only — it modifies data.';

const CODE_PREFIX = /^([A-Z][A-Z0-9_]*):\s+([\s\S]*)$/;

/**
 * Story 4.4 stores reasons as `"CODE: reason"`. Splits them into a chip code and a reason;
 * anything else is shown whole as the reason, with no chip.
 */
export function parseReason(raw: string): { code: string | null; reason: string } {
  const m = CODE_PREFIX.exec(raw ?? '');
  return m ? { code: m[1], reason: m[2] } : { code: null, reason: raw ?? '' };
}

/** Result header copy (EXPERIENCE.md Validation result). */
export function resultHeader(accepted: boolean, rejections: readonly Rejection[]): string {
  if (accepted) {
    return '✓ Accepted · single read-only SELECT';
  }
  const n = rejections.length;
  return `✕ Rejected · ${n} problem${n === 1 ? '' : 's'}`;
}
